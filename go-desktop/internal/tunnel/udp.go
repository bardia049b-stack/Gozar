package tunnel

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/quic-go/qpack"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/quicvarint"
)

type DatagramSession interface {
	Send([]byte) error
	Receive(context.Context) ([]byte, error)
	Close()
}

var (
	ErrUDPRefused     = errors.New("the proxy refused the udp tunnel")
	ErrUDPUnsupported = errors.New("the proxy does not offer datagram tunnels")
)

type session struct {
	sock    quic.Connection
	stream  quic.Stream
	quarter uint64
	inbox   chan []byte
	once    sync.Once
	closed  chan struct{}
}

func newSession(sock quic.Connection, stream quic.Stream) *session {
	s := &session{
		sock:    sock,
		stream:  stream,
		quarter: uint64(stream.StreamID()) / 4,
		inbox:   make(chan []byte, 128),
		closed:  make(chan struct{}),
	}
	go s.pump()
	return s
}

func (s *session) pump() {
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		raw, err := s.sock.ReceiveDatagram(ctx)
		cancel()
		if err != nil {
			s.once.Do(func() { close(s.closed) })
			return
		}
		quarter, used, err := quicvarint.Parse(raw)
		if err != nil || quarter != s.quarter {
			continue
		}
		rest := raw[used:]
		if len(rest) == 0 {
			continue
		}
		select {
		case s.inbox <- append([]byte(nil), rest...):
		default:
		}
	}
}

func (s *session) Send(payload []byte) error {
	out := quicvarint.Append(nil, s.quarter)
	out = append(out, payload...)
	return s.sock.SendDatagram(out)
}

func (s *session) Receive(ctx context.Context) ([]byte, error) {
	select {
	case payload, ok := <-s.inbox:
		if !ok {
			return nil, net.ErrClosed
		}
		return payload, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-s.closed:
		return nil, net.ErrClosed
	}
}

func (s *session) Close() {
	_ = s.stream.Close()
	go func() {
		time.Sleep(2 * time.Second)
		_ = s.sock.CloseWithError(0, "")
	}()
}

func settingsFrame() []byte {
	payload := quicvarint.Append(nil, 0x33)
	payload = quicvarint.Append(payload, 1)
	frame := quicvarint.Append(nil, 0x04)
	frame = quicvarint.Append(frame, uint64(len(payload)))
	frame = append(frame, payload...)
	control := quicvarint.Append(nil, 0x00)
	return append(control, frame...)
}

type sliceWriter struct{ slots *[]byte }

func (w sliceWriter) Write(p []byte) (int, error) {
	*w.slots = append(*w.slots, p...)
	return len(p), nil
}

func readHeaderFrame(stream interface{ Read([]byte) (int, error) }) (uint64, []byte, error) {
	kind, err := quicvarint.Read(quicvarint.NewReader(stream))
	if err != nil {
		return 0, nil, err
	}
	length, err := quicvarint.Read(quicvarint.NewReader(stream))
	if err != nil {
		return 0, nil, err
	}
	if length > 1<<17 {
		return 0, nil, errors.New("the proxy sent an oversized frame")
	}
	body := make([]byte, length)
	read := 0
	for read < len(body) {
		n, err := stream.Read(body[read:])
		if err != nil {
			return 0, nil, err
		}
		read += n
	}
	return kind, body, nil
}

func OpenUDP(target Target, host string, port int, pass string, timeout time.Duration) (DatagramSession, error) {
	if pass == "" {
		return nil, errors.New("the tunnel needs a proxy pass")
	}
	address := target.DialHost
	if address == "" {
		address = target.Host
	}
	deadline := time.Now().Add(timeout)
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()

	var socket quic.Connection
	remote, err := net.ResolveUDPAddr("udp", net.JoinHostPort(address, strconv.Itoa(target.Port)))
	if err != nil {
		return nil, err
	}
	local, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, err
	}
	quicConfig := &quic.Config{
		EnableDatagrams:         true,
		HandshakeIdleTimeout:    time.Until(deadline),
		MaxIdleTimeout:          5 * time.Minute,
		InitialPacketSize:       1200,
		DisablePathMTUDiscovery: true,
		KeepAlivePeriod:         20 * time.Second,
	}
	socket, err = quic.Dial(ctx, local, remote, &tls.Config{
		ServerName:         target.Host,
		NextProtos:         []string{"h3"},
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: target.insecureForTests || target.TrustAnyCertificate,
	}, quicConfig)
	if err != nil {
		local.Close()
		return nil, err
	}
	if !socket.ConnectionState().SupportsDatagrams {
		_ = socket.CloseWithError(0, "")
		return nil, ErrUDPUnsupported
	}
	control, err := socket.OpenUniStreamSync(ctx)
	if err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, err
	}
	if _, err := control.Write(settingsFrame()); err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, err
	}
	time.Sleep(120 * time.Millisecond)
	stream, err := socket.OpenStreamSync(ctx)
	if err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, err
	}
	path := "/.well-known/masque/udp/" + host + "/" + strconv.Itoa(port) + "/"
	parsed, err := url.Parse("https://" + net.JoinHostPort(target.Host, strconv.Itoa(target.Port)) + path)
	if err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, err
	}
	var block []byte
	encoder := qpack.NewEncoder(sliceWriter{slots: &block})
	for _, field := range []qpack.HeaderField{
		{Name: ":method", Value: "CONNECT"},
		{Name: ":protocol", Value: "connect-udp"},
		{Name: ":scheme", Value: "https"},
		{Name: ":authority", Value: parsed.Host},
		{Name: ":path", Value: parsed.Path},
		{Name: "capsule-protocol", Value: "?1"},
		{Name: "proxy-authorization", Value: "Bearer " + pass},
	} {
		if err := encoder.WriteField(field); err != nil {
			_ = socket.CloseWithError(0, "")
			return nil, err
		}
	}
	head := quicvarint.Append(nil, 0x01)
	head = quicvarint.Append(head, uint64(len(block)))
	head = append(head, block...)
	if _, err := stream.Write(head); err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, err
	}
	_ = stream.SetReadDeadline(time.Now().Add(timeout))
	kind, body, err := readHeaderFrame(stream)
	if err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, fmt.Errorf("%w: %v", ErrUDPRefused, err)
	}
	if kind != 0x01 {
		_ = socket.CloseWithError(0, "")
		return nil, ErrUDPRefused
	}
	decoder := qpack.NewDecoder(func(qpack.HeaderField) {})
	fields, err := decoder.DecodeFull(body)
	if err != nil {
		_ = socket.CloseWithError(0, "")
		return nil, fmt.Errorf("%w: %v", ErrUDPRefused, err)
	}
	status := ""
	for _, field := range fields {
		if field.Name == ":status" {
			status = field.Value
		}
	}
	if status != "200" {
		_ = socket.CloseWithError(0, "")
		return nil, fmt.Errorf("%w: status %s", ErrUDPRefused, status)
	}
	_ = stream.SetReadDeadline(time.Time{})
	return newSession(socket, stream), nil
}
