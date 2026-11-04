// Package advancedtest provides real protocol servers used by the CI tests for
// the optional transports. It is only imported from tests.
package advancedtest

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"github.com/quic-go/quic-go"
)

// DoQServer is a minimal but real RFC 9250 server: QUIC + ALPN "doq", one
// length-prefixed DNS message per stream.
type DoQServer struct {
	Addr     string
	Handler  func([]byte) ([]byte, error)
	TLS      *tls.Config
	listener *quic.Listener
	once     sync.Once
	queries  int
	mu       sync.Mutex
	conns    map[*quic.Conn]struct{}
	closed   bool
}

func StartDoQ(config *tls.Config, handler func([]byte) ([]byte, error)) (*DoQServer, error) {
	clone := config.Clone()
	clone.NextProtos = []string{advanced.DoQALPN}
	clone.MinVersion = tls.VersionTLS13
	listener, err := quic.ListenAddr("127.0.0.1:0", clone, &quic.Config{MaxIdleTimeout: 30 * time.Second})
	if err != nil {
		return nil, err
	}
	server := &DoQServer{Addr: listener.Addr().String(), Handler: handler, TLS: config, listener: listener, conns: make(map[*quic.Conn]struct{})}
	go server.serve()
	return server, nil
}

func (s *DoQServer) Queries() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.queries
}

func (s *DoQServer) serve() {
	for {
		conn, err := s.listener.Accept(context.Background())
		if err != nil {
			return
		}
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			conn.CloseWithError(0, "")
			return
		}
		s.conns[conn] = struct{}{}
		s.mu.Unlock()
		go s.serveConn(conn)
	}
}

func (s *DoQServer) serveConn(conn *quic.Conn) {
	for {
		stream, err := conn.AcceptStream(context.Background())
		if err != nil {
			return
		}
		go s.serveStream(stream)
	}
}

func (s *DoQServer) serveStream(stream *quic.Stream) {
	defer stream.Close()
	_ = stream.SetDeadline(time.Now().Add(10 * time.Second))
	size := make([]byte, 2)
	if _, err := io.ReadFull(stream, size); err != nil {
		return
	}
	length := int(binary.BigEndian.Uint16(size))
	if length < 12 || length > 65535 {
		return
	}
	query := make([]byte, length)
	if _, err := io.ReadFull(stream, query); err != nil {
		return
	}
	if query[0] != 0 || query[1] != 0 {
		// RFC 9250 requires message ID 0 on the wire.
		stream.CancelWrite(0x02)
		return
	}
	s.mu.Lock()
	s.queries++
	s.mu.Unlock()
	answer, err := s.Handler(query)
	if err != nil || len(answer) < 12 {
		return
	}
	answer[0], answer[1] = 0, 0
	frame := make([]byte, 2+len(answer))
	binary.BigEndian.PutUint16(frame, uint16(len(answer)))
	copy(frame[2:], answer)
	if _, err := stream.Write(frame); err != nil {
		return
	}
	_ = stream.Close()
}

func (s *DoQServer) Close() error {
	var err error
	s.once.Do(func() {
		err = s.listener.Close()
		s.mu.Lock()
		s.closed = true
		conns := make([]*quic.Conn, 0, len(s.conns))
		for conn := range s.conns {
			conns = append(conns, conn)
		}
		s.conns = make(map[*quic.Conn]struct{})
		s.mu.Unlock()
		for _, conn := range conns {
			conn.CloseWithError(0, "server shutdown")
		}
	})
	return err
}

// WSSServer accepts a real WebSocket upgrade and bridges the framed bytes to a
// TCP destination, exactly like a WSS-capable proxy would.
type WSSServer struct {
	Addr      string
	TLS       *tls.Config
	Target    string
	Path      string
	Pass      string
	FrontName string

	listener   net.Listener
	once       sync.Once
	mu         sync.Mutex
	upgrades   int
	lastSNI    string
	lastHost   string
	lastAuth   string
	lastTarget string
}

func StartWSS(config *tls.Config, target, path, pass string) (*WSSServer, error) {
	listener, err := tls.Listen("tcp", "127.0.0.1:0", config)
	if err != nil {
		return nil, err
	}
	server := &WSSServer{Addr: listener.Addr().String(), TLS: config, Target: target, Path: path, Pass: pass, listener: listener}
	go server.serve()
	return server, nil
}

// RequestedTarget returns the X-Gozar-Target value the client asked for.
func (s *WSSServer) RequestedTarget() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastTarget
}

func (s *WSSServer) Stats() (int, string, string, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.upgrades, s.lastSNI, s.lastHost, s.lastAuth
}

func (s *WSSServer) serve() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		go s.handle(conn)
	}
}

func (s *WSSServer) handle(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	if tlsConn, ok := conn.(*tls.Conn); ok {
		if err := tlsConn.Handshake(); err != nil {
			return
		}
		s.mu.Lock()
		s.lastSNI = tlsConn.ConnectionState().ServerName
		s.mu.Unlock()
	}
	reader := bufio.NewReader(conn)
	request, err := http.ReadRequest(reader)
	if err != nil {
		return
	}
	if s.Path != "" && request.URL.Path != s.Path {
		_, _ = conn.Write([]byte("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"))
		return
	}
	if s.Pass != "" && request.Header.Get("Proxy-Authorization") != "Bearer "+s.Pass {
		_, _ = conn.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n"))
		return
	}
	key := request.Header.Get("Sec-WebSocket-Key")
	if key == "" || !strings.EqualFold(request.Header.Get("Upgrade"), "websocket") {
		_, _ = conn.Write([]byte("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"))
		return
	}
	// The mock always dials its own loopback upstream; the requested target is
	// only recorded, so a test can prove the client sent it without the mock
	// ever touching the public Internet.
	requested := request.Header.Get(advanced.TargetHeader)
	s.mu.Lock()
	s.lastTarget = requested
	s.mu.Unlock()
	target := s.Target
	if target == "" {
		target = requested
	}
	upstream, err := net.DialTimeout("tcp", target, 5*time.Second)
	if err != nil {
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"))
		return
	}
	defer upstream.Close()
	s.mu.Lock()
	s.upgrades++
	s.lastHost = request.Host
	s.lastAuth = request.Header.Get("Proxy-Authorization")
	s.mu.Unlock()
	response := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + advanced.AcceptKey(key) + "\r\n" +
		"Sec-WebSocket-Protocol: " + advanced.WSSProtocol + "\r\n\r\n"
	if _, err := conn.Write([]byte(response)); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	framed := advanced.NewWebSocketConn(conn, reader, false)
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, framed)
		if half, ok := upstream.(interface{ CloseWrite() error }); ok {
			_ = half.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(framed, upstream)
		if half, ok := framed.(interface{ CloseWrite() error }); ok {
			_ = half.CloseWrite()
		}
		done <- struct{}{}
	}()
	// Both directions must finish: closing after the first one would cut the
	// response short while the peer is still sending.
	<-done
	<-done
}

func (s *WSSServer) Close() error {
	var err error
	s.once.Do(func() { err = s.listener.Close() })
	return err
}

// ErrNoTarget is returned by helpers that need an explicit destination.
var ErrNoTarget = errors.New("no target")
