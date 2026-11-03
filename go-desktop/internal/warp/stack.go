package warp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

const nicID = 1

type PacketTunnel interface {
	WritePacket([]byte) error
	ReadPacket(context.Context) ([]byte, error)
	Addresses() []netip.Prefix
	Done() <-chan struct{}
	Close()
}

type Stack struct {
	tunnel PacketTunnel
	link   *channel.Endpoint
	stack  *stack.Stack
	ctx    context.Context
	stop   context.CancelFunc
	group  sync.WaitGroup
	once   sync.Once
	mtu    int
}

func NewStack(tunnel PacketTunnel, mtu int) (*Stack, error) {
	if tunnel == nil {
		return nil, errors.New("the warp tunnel is missing")
	}
	if mtu <= 0 {
		mtu = 1200
	}
	addresses := tunnel.Addresses()
	if len(addresses) == 0 {
		return nil, errors.New("the warp tunnel has no address")
	}
	link := channel.New(1024, uint32(mtu), "")
	created := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := created.CreateNIC(nicID, link); err != nil {
		return nil, errors.New(err.String())
	}
	var haveV4, haveV6 bool
	for _, prefix := range addresses {
		protocol := ipv4.ProtocolNumber
		if prefix.Addr().Is6() {
			protocol = ipv6.ProtocolNumber
		} else {
			haveV4 = true
		}
		if prefix.Addr().Is4() {
			haveV4 = true
		}
		if prefix.Addr().Is6() {
			haveV6 = true
		}
		address := tcpip.ProtocolAddress{
			Protocol:          protocol,
			AddressWithPrefix: tcpip.AddrFromSlice(prefix.Addr().AsSlice()).WithPrefix(),
		}
		if err := created.AddProtocolAddress(nicID, address, stack.AddressProperties{}); err != nil {
			return nil, errors.New(err.String())
		}
	}
	var routes []tcpip.Route
	if haveV4 {
		routes = append(routes, tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: nicID})
	}
	if haveV6 {
		routes = append(routes, tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: nicID})
	}
	created.SetRouteTable(routes)
	ctx, stop := context.WithCancel(context.Background())
	instance := &Stack{tunnel: tunnel, link: link, stack: created, ctx: ctx, stop: stop, mtu: mtu}
	instance.group.Add(2)
	go instance.fromTunnel()
	go instance.toTunnel()
	go func() {
		select {
		case <-tunnel.Done():
			instance.Close()
		case <-ctx.Done():
		}
	}()
	return instance, nil
}

func (s *Stack) fromTunnel() {
	defer s.group.Done()
	for {
		packet, err := s.tunnel.ReadPacket(s.ctx)
		if err != nil {
			s.stop()
			return
		}
		if len(packet) == 0 {
			continue
		}
		protocol := ipv4.ProtocolNumber
		if packet[0]>>4 == 6 {
			protocol = ipv6.ProtocolNumber
		} else if packet[0]>>4 != 4 {
			continue
		}
		payload := buffer.MakeWithData(append([]byte{}, packet...))
		received := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: payload})
		s.link.InjectInbound(protocol, received)
		received.DecRef()
	}
}

func (s *Stack) toTunnel() {
	defer s.group.Done()
	for {
		packet := s.link.ReadContext(s.ctx)
		if packet == nil {
			return
		}
		view := packet.ToView()
		raw := append([]byte{}, view.AsSlice()...)
		view.Release()
		packet.DecRef()
		if len(raw) == 0 {
			continue
		}
		if err := s.tunnel.WritePacket(raw); err != nil {
			s.stop()
			return
		}
	}
}

func (s *Stack) DialTCP(ctx context.Context, host string, port int) (io.ReadWriteCloser, error) {
	address, err := netip.ParseAddr(host)
	if err != nil {
		return nil, errors.New("the warp dialer only accepts numeric addresses")
	}
	remote := tcpip.FullAddress{
		NIC:  nicID,
		Addr: tcpip.AddrFromSlice(address.Unmap().AsSlice()),
		Port: uint16(port),
	}
	protocol := ipv4.ProtocolNumber
	if address.Is6() {
		protocol = ipv6.ProtocolNumber
	}
	conn, err := gonet.DialContextTCP(ctx, s.stack, remote, protocol)
	if err != nil {
		return nil, errors.New(err.Error())
	}
	return conn, nil
}

func (s *Stack) DialUDP(host string, port int) (net.Conn, error) {
	address, err := netip.ParseAddr(host)
	if err != nil {
		return nil, errors.New("the warp dialer only accepts numeric addresses")
	}
	remote := tcpip.FullAddress{
		NIC:  nicID,
		Addr: tcpip.AddrFromSlice(address.Unmap().AsSlice()),
		Port: uint16(port),
	}
	protocol := ipv4.ProtocolNumber
	if address.Is6() {
		protocol = ipv6.ProtocolNumber
	}
	conn, err := gonet.DialUDP(s.stack, nil, &remote, protocol)
	if err != nil {
		return nil, errors.New(err.Error())
	}
	return conn, nil
}

func (s *Stack) DialDatagram(host string, port int) (*Datagram, error) {
	conn, err := s.DialUDP(host, port)
	if err != nil {
		return nil, err
	}
	return &Datagram{conn: conn}, nil
}

func (s *Stack) Close() {
	s.once.Do(func() {
		s.stop()
		s.link.Close()
		s.stack.Close()
		s.group.Wait()
	})
}

type Datagram struct {
	conn net.Conn
}

func (d *Datagram) Send(payload []byte) error {
	_, err := d.conn.Write(payload)
	return err
}

func (d *Datagram) Receive(ctx context.Context) ([]byte, error) {
	if deadline, ok := ctx.Deadline(); ok {
		_ = d.conn.SetReadDeadline(deadline)
	} else {
		_ = d.conn.SetReadDeadline(time.Time{})
	}
	buffer := make([]byte, 65535)
	count, err := d.conn.Read(buffer)
	if err != nil {
		return nil, err
	}
	return buffer[:count], nil
}

func (d *Datagram) Close() { _ = d.conn.Close() }
