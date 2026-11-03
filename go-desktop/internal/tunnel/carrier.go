package tunnel

import (
	"context"
	"errors"
	"net"
	"strconv"
	"time"
)

type DatagramCarrier interface {
	Send([]byte) error
	Receive(context.Context) ([]byte, error)
	Close()
}

type Carrier struct {
	Session DatagramSession
	local   net.PacketConn
}

func (c *Carrier) Send(payload []byte) error { return c.Session.Send(payload) }

func (c *Carrier) Receive(ctx context.Context) ([]byte, error) { return c.Session.Receive(ctx) }

func (c *Carrier) Close() {
	c.Session.Close()
	if c.local != nil {
		c.local.Close()
	}
}

type carrierConn struct {
	session *session
}

func (c carrierConn) Read(p []byte) (int, error) {
	payload, err := c.session.Receive(context.Background())
	if err != nil {
		return 0, err
	}
	copy(p, payload)
	return len(payload), nil
}

func (c carrierConn) Close() error {
	c.session.Close()
	return nil
}

type carrierPacketConn struct {
	carrier *Carrier
}

type carrierAddr struct{}

func (carrierAddr) Network() string { return "masque" }
func (carrierAddr) String() string  { return "masque" }

func (c carrierPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	payload, err := c.carrier.Session.Receive(context.Background())
	if err != nil {
		return 0, nil, err
	}
	copy(p, payload)
	return len(payload), carrierAddr{}, nil
}

func (c carrierPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	if err := c.carrier.Session.Send(p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c carrierPacketConn) Close() error {
	c.carrier.Close()
	return nil
}

func (c carrierPacketConn) LocalAddr() net.Addr              { return carrierAddr{} }
func (c carrierPacketConn) RemoteAddr() net.Addr             { return carrierAddr{} }
func (c carrierPacketConn) SetDeadline(time.Time) error      { return nil }
func (c carrierPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (c carrierPacketConn) SetWriteDeadline(time.Time) error { return nil }

func OpenCarrier(target Target, host string, port int, pass string, timeout time.Duration) (*Carrier, error) {
	if host == "" || port < 1 || port > 65535 {
		return nil, errors.New("the carrier needs a numeric destination")
	}
	session, err := OpenUDP(target, host, port, pass, timeout)
	if err != nil {
		return nil, err
	}
	return &Carrier{Session: session}, nil
}

func (c *Carrier) PacketConn() net.PacketConn { return carrierPacketConn{carrier: c} }

func carrierTarget(host string, port int) string {
	return net.JoinHostPort(host, strconv.Itoa(port))
}
