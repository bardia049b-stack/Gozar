package warp

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sync"
	"time"

	"github.com/quic-go/quic-go/quicvarint"
	"golang.org/x/net/http2"
)

const h2DatagramCapsule = 0

type Dialer func(host string, port int) (io.ReadWriteCloser, error)

type H2Tunnel struct {
	body    io.ReadCloser
	writer  *io.PipeWriter
	client  *http2.Transport
	mu      sync.Mutex
	pending []byte
	local   []netip.Prefix
	closed  bool
	done    chan struct{}
	once    sync.Once
}

func DialOverTCP(ctx context.Context, device *Device, dial Dialer, options Options) (*H2Tunnel, error) {
	if device == nil {
		return nil, errors.New("the warp device is missing")
	}
	if dial == nil {
		return nil, errors.New("the warp tunnel needs a tcp carrier")
	}
	key, err := device.Private()
	if err != nil {
		return nil, err
	}
	peer, err := device.Peer()
	if err != nil {
		return nil, err
	}
	endpoint, err := device.EndpointAddress()
	if err != nil {
		return nil, err
	}
	host, portText, err := net.SplitHostPort(endpoint)
	if err != nil {
		return nil, err
	}
	port := 443
	if parsed, err := parsePort(portText); err == nil {
		port = parsed
	}
	tlsConfig, err := tlsFor(key, peer, device.ServerName())
	if err != nil {
		return nil, err
	}
	tlsConfig.NextProtos = []string{http2.NextProtoTLS}
	transport := &http2.Transport{
		DisableCompression: true,
		AllowHTTP:          false,
		DialTLSContext: func(ctx context.Context, _, _ string, _ *tls.Config) (net.Conn, error) {
			raw, err := dial(host, port)
			if err != nil {
				return nil, err
			}
			secure := tls.Client(&pipeConn{ReadWriteCloser: raw}, tlsConfig)
			if err := secure.HandshakeContext(ctx); err != nil {
				raw.Close()
				return nil, err
			}
			return secure, nil
		},
	}
	reader, writer := io.Pipe()
	requestURL, _ := url.Parse("https://" + requestAuthority)
	request := &http.Request{
		Method:        http.MethodConnect,
		URL:           requestURL,
		Host:          requestAuthority,
		Proto:         "HTTP/1.1",
		Header:        http.Header{"Cf-Connect-Proto": []string{"cf-connect-ip"}, "Pq-Enabled": []string{"false"}},
		Body:          reader,
		ContentLength: -1,
	}
	response, err := transport.RoundTrip(request.WithContext(ctx))
	if err != nil {
		reader.CloseWithError(err)
		writer.Close()
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode > 299 {
		reader.Close()
		writer.Close()
		response.Body.Close()
		return nil, errors.New("the warp tcp proxy refused the request")
	}
	prefixes, err := device.Prefixes()
	if err != nil {
		reader.Close()
		writer.Close()
		response.Body.Close()
		return nil, err
	}
	tunnel := &H2Tunnel{body: response.Body, writer: writer, client: transport, local: prefixes, done: make(chan struct{})}
	go tunnel.pump()
	return tunnel, nil
}

type pipeConn struct{ io.ReadWriteCloser }

func (p *pipeConn) LocalAddr() net.Addr              { return pipeAddr{} }
func (p *pipeConn) RemoteAddr() net.Addr             { return pipeAddr{} }
func (p *pipeConn) SetDeadline(time.Time) error      { return nil }
func (p *pipeConn) SetReadDeadline(time.Time) error  { return nil }
func (p *pipeConn) SetWriteDeadline(time.Time) error { return nil }

type pipeAddr struct{}

func (pipeAddr) Network() string { return "warp" }
func (pipeAddr) String() string  { return "warp" }

func (t *H2Tunnel) pump() {
	buffer := make([]byte, 32*1024)
	for {
		count, err := t.body.Read(buffer)
		if count > 0 {
			t.mu.Lock()
			t.pending = append(t.pending, buffer[:count]...)
			t.mu.Unlock()
		}
		if err != nil {
			t.close()
			return
		}
	}
}

func (t *H2Tunnel) close() {
	t.once.Do(func() {
		t.mu.Lock()
		t.closed = true
		t.mu.Unlock()
		close(t.done)
	})
}

func (t *H2Tunnel) Done() <-chan struct{} { return t.done }

func (t *H2Tunnel) Addresses() []netip.Prefix {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]netip.Prefix(nil), t.local...)
}

func (t *H2Tunnel) WritePacket(packet []byte) error {
	t.mu.Lock()
	closed := t.closed
	t.mu.Unlock()
	if closed {
		return net.ErrClosed
	}
	frame := quicvarint.Append(nil, h2DatagramCapsule)
	frame = quicvarint.Append(frame, uint64(len(packet)))
	frame = append(frame, packet...)
	_, err := t.writer.Write(frame)
	return err
}

func (t *H2Tunnel) ReadPacket(ctx context.Context) ([]byte, error) {
	deadline := time.NewTicker(20 * time.Millisecond)
	defer deadline.Stop()
	for {
		t.mu.Lock()
		buffered := append([]byte(nil), t.pending...)
		closed := t.closed
		t.mu.Unlock()
		for len(buffered) > 0 {
			kind, consumed, payload, ok, err := readCapsule(buffered)
			if err != nil {
				return nil, err
			}
			if !ok {
				break
			}
			buffered = buffered[consumed:]
			t.mu.Lock()
			t.pending = t.pending[consumed:]
			t.mu.Unlock()
			if kind != h2DatagramCapsule {
				continue
			}
			if packet := stripDatagram(payload); len(packet) > 0 {
				return packet, nil
			}
		}
		if closed {
			return nil, net.ErrClosed
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-t.done:
		case <-deadline.C:
		}
	}
}

func (t *H2Tunnel) Close() {
	t.close()
	t.writer.Close()
	t.body.Close()
	t.client.CloseIdleConnections()
}

func stripDatagram(payload []byte) []byte {
	if len(payload) >= 2 && payload[0] == 0 && isIP(payload[1]) {
		return append([]byte(nil), payload[1:]...)
	}
	if len(payload) > 0 && isIP(payload[0]) {
		return append([]byte(nil), payload...)
	}
	return nil
}

func isIP(version byte) bool { return version>>4 == 4 || version>>4 == 6 }

func readCapsule(buffer []byte) (uint64, int, []byte, bool, error) {
	kind, used, ok := parseVarint(buffer)
	if !ok {
		return 0, 0, nil, false, nil
	}
	length, extra, ok := parseVarint(buffer[used:])
	if !ok {
		return 0, 0, nil, false, nil
	}
	header := used + extra
	total := header + int(length)
	if total > len(buffer) {
		return 0, 0, nil, false, nil
	}
	if length > 1<<17 {
		return 0, 0, nil, false, errors.New("the warp proxy sent an oversized capsule")
	}
	return kind, total, buffer[header:total], true, nil
}

func parsePort(text string) (int, error) {
	value := 0
	if text == "" {
		return 0, errors.New("empty port")
	}
	for _, digit := range text {
		if digit < '0' || digit > '9' {
			return 0, errors.New("invalid port")
		}
		value = value*10 + int(digit-'0')
	}
	if value < 1 || value > 65535 {
		return 0, errors.New("invalid port")
	}
	return value, nil
}
