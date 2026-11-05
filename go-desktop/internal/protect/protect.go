package protect

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"sync"
	"syscall"
	"time"

	"github.com/quic-go/quic-go"
)

var (
	mu   sync.RWMutex
	hook func(fd int) bool
)

func SetHook(f func(fd int) bool) {
	mu.Lock()
	hook = f
	mu.Unlock()
}

func Control(network, address string, conn syscall.RawConn) error {
	mu.RLock()
	f := hook
	mu.RUnlock()
	if f == nil {
		return nil
	}
	var refused bool
	err := conn.Control(func(fd uintptr) {
		if !f(int(fd)) {
			refused = true
		}
	})
	if err != nil {
		return err
	}
	if refused {
		return errors.New("the platform refused to exclude this socket from the tunnel")
	}
	return nil
}

func Dialer(timeout time.Duration) *net.Dialer {
	return DialerKeepAlive(timeout, 30*time.Second)
}

func DialerKeepAlive(timeout, keepAlive time.Duration) *net.Dialer {
	if keepAlive <= 0 {
		keepAlive = 30 * time.Second
	}
	return &net.Dialer{Timeout: timeout, KeepAlive: keepAlive, Control: Control}
}

func ListenConfig() net.ListenConfig {
	return net.ListenConfig{Control: Control}
}

func DialQUIC(ctx context.Context, address string, config *tls.Config, options *quic.Config) (*quic.Conn, error) {
	target, err := net.ResolveUDPAddr("udp", address)
	if err != nil {
		return nil, err
	}
	listen := ListenConfig()
	packet, err := listen.ListenPacket(ctx, "udp", "")
	if err != nil {
		return nil, err
	}
	socket, ok := packet.(*net.UDPConn)
	if !ok {
		packet.Close()
		return nil, errors.New("a udp socket could not be opened")
	}
	transport := &quic.Transport{Conn: socket}
	session, err := transport.Dial(ctx, target, config, options)
	if err != nil {
		transport.Close()
		socket.Close()
		return nil, err
	}
	go func() {
		<-session.Context().Done()
		transport.Close()
		socket.Close()
	}()
	return session, nil
}
