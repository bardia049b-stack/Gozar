package warp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"time"
)

type Carrier struct {
	Packet net.PacketConn
	Stream Dialer
}

func Open(ctx context.Context, device *Device, carrier Carrier, options Options) (PacketTunnel, error) {
	if device == nil {
		return nil, errors.New("the warp device is missing")
	}
	if carrier.Stream != nil {
		return DialOverTCP(ctx, device, carrier.Stream, options)
	}
	if carrier.Packet != nil {
		return Dial(ctx, device, carrier.Packet, options)
	}
	return nil, errors.New("the warp tunnel needs a carrier")
}

const (
	DefaultMTU = 1180
	quicMTU    = 1252
)

func OpenStack(ctx context.Context, device *Device, carrier Carrier, mtu int) (*Stack, error) {
	if mtu <= 0 || mtu > DefaultMTU {
		mtu = DefaultMTU
	}
	options := Options{MTU: quicMTU, Deadline: 30 * time.Second}
	tunnel, err := Open(ctx, device, carrier, options)
	if err != nil {
		return nil, err
	}
	created, err := NewStack(tunnel, mtu)
	if err != nil {
		tunnel.Close()
		return nil, err
	}
	return created, nil
}

func Listen(endpoint string) (net.PacketConn, error) {
	address, err := net.ResolveUDPAddr("udp", endpoint)
	if err != nil {
		return nil, err
	}
	socket, err := net.DialUDP("udp", nil, address)
	if err != nil {
		return nil, err
	}
	return &pinned{conn: socket, remote: address}, nil
}

type pinned struct {
	conn   *net.UDPConn
	remote *net.UDPAddr
}

func (p *pinned) ReadFrom(buffer []byte) (int, net.Addr, error) {
	count, err := p.conn.Read(buffer)
	if err != nil {
		return count, p.remote, err
	}
	return count, p.remote, nil
}

func (p *pinned) WriteTo(buffer []byte, _ net.Addr) (int, error) { return p.conn.Write(buffer) }
func (p *pinned) Close() error                                   { return p.conn.Close() }
func (p *pinned) LocalAddr() net.Addr                            { return p.conn.LocalAddr() }
func (p *pinned) SetDeadline(at time.Time) error                 { return p.conn.SetDeadline(at) }
func (p *pinned) SetReadDeadline(at time.Time) error             { return p.conn.SetReadDeadline(at) }
func (p *pinned) SetWriteDeadline(at time.Time) error            { return p.conn.SetWriteDeadline(at) }

func (s *Stack) Dial(host string, port int) (io.ReadWriteCloser, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return s.DialTCP(ctx, host, port)
}

func (s *Stack) DialConn(host string, port int) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	raw, err := s.DialTCP(ctx, host, port)
	if err != nil {
		return nil, err
	}
	conn, ok := raw.(net.Conn)
	if !ok {
		raw.Close()
		return nil, errors.New("the warp dialer returned a plain stream")
	}
	return conn, nil
}

func (s *Stack) Datagram(host string, port int) (*Datagram, error) {
	return s.DialDatagram(host, port)
}

func (s *Stack) Prefixes() []netip.Prefix {
	return s.tunnel.Addresses()
}
