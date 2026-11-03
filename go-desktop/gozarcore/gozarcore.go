package gozarcore

import (
	"context"
	"errors"
	"io"
	"path/filepath"
	"sync"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

type Relay struct {
	target tunnel.Target
	pass   string
}

func OpenRelay(host string, port int, dialHost, pass string) (*Relay, error) {
	if host == "" || port < 1 || port > 65535 {
		return nil, errors.New("the relay needs a host and port")
	}
	if dialHost == "" {
		dialHost = host
	}
	return &Relay{
		target: tunnel.Target{Host: host, Port: port, DialHost: dialHost},
		pass:   pass,
	}, nil
}

func (r *Relay) Datagram(host string, port int) (*Datagram, error) {
	if r == nil {
		return nil, errors.New("the relay is closed")
	}
	session, err := tunnel.OpenUDP(r.target, host, port, r.pass, 15*time.Second)
	if err != nil {
		return nil, err
	}
	return &Datagram{send: session.Send, receive: session.Receive, close: session.Close}, nil
}

func (r *Relay) Stream(host string, port int) (*Stream, error) {
	if r == nil {
		return nil, errors.New("the relay is closed")
	}
	manager := tunnel.NewManager(r.target)
	conn, err := manager.Open(host, port, r.pass, 20*time.Second)
	if err != nil {
		manager.Close()
		return nil, err
	}
	return &Stream{conn: conn, release: func() { _ = manager.Close() }}, nil
}

type Warp struct {
	mu      sync.Mutex
	stack   *warp.Stack
	release func()
	closed  bool
}

func OpenWarp(deviceDir, via, relayHost string, relayPort int, relayDialHost, pass string, mtu, timeoutMs int) (*Warp, error) {
	if timeoutMs <= 0 {
		timeoutMs = 30000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	device, err := warp.DeviceFor(ctx, filepath.Join(deviceDir, "warp.json"))
	if err != nil {
		return nil, err
	}
	if mtu <= 0 || mtu > warp.DefaultMTU {
		mtu = warp.DefaultMTU
	}
	var carrier warp.Carrier
	var release func()
	if via == "relay" && relayHost != "" {
		if relayDialHost == "" {
			relayDialHost = relayHost
		}
		manager := tunnel.NewManager(tunnel.Target{Host: relayHost, Port: relayPort, DialHost: relayDialHost})
		carrier = warp.Carrier{Stream: func(host string, port int) (io.ReadWriteCloser, error) {
			return manager.Open(host, port, pass, 20*time.Second)
		}}
		release = func() { _ = manager.Close() }
	} else {
		socket, err := warp.Listen(device.Endpoint)
		if err != nil {
			return nil, err
		}
		carrier = warp.Carrier{Packet: socket}
		release = func() { socket.Close() }
	}
	stack, err := warp.OpenStack(ctx, device, carrier, mtu)
	if err != nil {
		release()
		return nil, err
	}
	return &Warp{stack: stack, release: release}, nil
}

func (w *Warp) alive() *warp.Stack {
	if w == nil {
		return nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed || w.stack == nil || !w.stack.Alive() {
		return nil
	}
	return w.stack
}

func (w *Warp) Alive() bool { return w.alive() != nil }

func (w *Warp) Addresses() string {
	stack := w.alive()
	if stack == nil {
		return ""
	}
	out := ""
	for _, prefix := range stack.Prefixes() {
		if out != "" {
			out += " "
		}
		out += prefix.String()
	}
	return out
}

func (w *Warp) Datagram(host string, port int) (*Datagram, error) {
	stack := w.alive()
	if stack == nil {
		return nil, errors.New("the warp tunnel is down")
	}
	flow, err := stack.Datagram(host, port)
	if err != nil {
		return nil, err
	}
	return &Datagram{
		send: flow.Send,
		receive: func(ctx context.Context) ([]byte, error) {
			return flow.Receive(ctx)
		},
		close: flow.Close,
	}, nil
}

func (w *Warp) Stream(host string, port int) (*Stream, error) {
	stack := w.alive()
	if stack == nil {
		return nil, errors.New("the warp tunnel is down")
	}
	conn, err := stack.DialConn(host, port)
	if err != nil {
		return nil, err
	}
	return &Stream{conn: conn}, nil
}

func (w *Warp) Close() {
	if w == nil {
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return
	}
	w.closed = true
	if w.stack != nil {
		w.stack.Close()
		w.stack = nil
	}
	if w.release != nil {
		w.release()
		w.release = nil
	}
}

type Datagram struct {
	send    func([]byte) error
	receive func(context.Context) ([]byte, error)
	close   func()
	once    sync.Once
}

func (d *Datagram) Send(payload []byte) error {
	if d == nil || d.send == nil {
		return errors.New("the datagram flow is closed")
	}
	return d.send(payload)
}

func (d *Datagram) Receive(timeoutMs int) []byte {
	if d == nil || d.receive == nil {
		return nil
	}
	if timeoutMs <= 0 {
		timeoutMs = 1000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	payload, err := d.receive(ctx)
	if err != nil || len(payload) == 0 {
		return nil
	}
	return payload
}

func (d *Datagram) Close() {
	if d == nil {
		return
	}
	d.once.Do(func() {
		if d.close != nil {
			d.close()
		}
	})
}

func ProbeUDP(host string, port int, dialHost, pass string, timeoutMs int) bool {
	if timeoutMs <= 0 {
		timeoutMs = 8000
	}
	relay, err := OpenRelay(host, port, dialHost, pass)
	if err != nil {
		return false
	}
	flow, err := relay.Datagram("1.1.1.1", 53)
	if err != nil {
		return false
	}
	defer flow.Close()
	query := []byte{
		0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
		0x00, 0x01, 0x00, 0x01,
	}
	if err := flow.Send(query); err != nil {
		return false
	}
	return len(flow.Receive(timeoutMs)) > 0
}
