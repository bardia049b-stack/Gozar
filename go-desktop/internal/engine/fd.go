//go:build linux || android

package engine

import (
	"errors"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/dnsproxy"
)

type FDOptions struct {
	MTU     uint32
	Packets PacketDialer
	Address [4]byte
}

func StartFD(fd int, dial Dialer, options FDOptions) (*Engine, error) {
	if fd < 0 {
		return nil, errors.New("the tunnel file descriptor is not open")
	}
	if dial == nil {
		return nil, errors.New("the tunnel needs an upstream dialer")
	}
	owned, err := unix.Dup(fd)
	if err != nil {
		return nil, err
	}
	mtu := options.MTU
	if mtu == 0 {
		mtu = 1280
	}
	address := options.Address
	if address == [4]byte{} {
		address = [4]byte{10, 111, 0, 2}
	}
	link, err := fdbased.New(&fdbased.Options{FDs: []int{owned}, MTU: mtu})
	if err != nil {
		unix.Close(owned)
		return nil, err
	}
	created := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := attachStack(created, link, address); err != nil {
		created.Close()
		link.Close()
		return nil, err
	}
	engine := &Engine{stack: created, dial: dial, managed: false, link: link}
	engine.dns = dnsproxy.New(dial)
	if options.Packets != nil {
		engine.packets = newUDPRelay(engine, options.Packets)
	}
	forwarder := tcp.NewForwarder(created, 0, 4096, engine.handleTCP)
	created.SetTransportProtocolHandler(tcp.ProtocolNumber, forwarder.HandlePacket)
	engine.listenUDP(created)
	return engine, nil
}

func attachStack(created *stack.Stack, link stack.LinkEndpoint, address [4]byte) error {
	if err := created.CreateNIC(1, link); err != nil {
		return errors.New(err.String())
	}
	if err := created.SetPromiscuousMode(1, true); err != nil {
		return errors.New(err.String())
	}
	if err := created.SetSpoofing(1, true); err != nil {
		return errors.New(err.String())
	}
	addr := tcpip.AddrFrom4(address)
	if err := created.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: addr.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		return errors.New(err.String())
	}
	created.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return nil
}
