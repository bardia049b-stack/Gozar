package advanced

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/quic-go/quic-go"
)

// DoQALPN is the RFC 9250 ALPN token.
const DoQALPN = "doq"

const maxDNSMessage = 65535

// DoQClient is a real DNS-over-QUIC client. One bidirectional stream carries one
// query, the client sends FIN right after the query, and the message ID is zero
// on the wire as RFC 9250 requires.
type DoQClient struct {
	Server  string
	TLS     *tls.Config
	Timeout time.Duration

	mu   sync.Mutex
	conn *quic.Conn
}

func NewDoQClient(server string, config *tls.Config) *DoQClient {
	return &DoQClient{Server: server, TLS: config, Timeout: 5 * time.Second}
}

func (c *DoQClient) tlsConfig() (*tls.Config, error) {
	host, _, err := net.SplitHostPort(c.Server)
	if err != nil {
		return nil, errors.New("the DoQ server must be host:port")
	}
	config := c.TLS
	if config == nil {
		config = &tls.Config{}
	} else {
		config = config.Clone()
	}
	if config.ServerName == "" {
		config.ServerName = host
	}
	config.NextProtos = []string{DoQALPN}
	config.MinVersion = tls.VersionTLS13
	return config, nil
}

func (c *DoQClient) connection(ctx context.Context) (*quic.Conn, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		select {
		case <-c.conn.Context().Done():
			c.conn = nil
		default:
			return c.conn, nil
		}
	}
	config, err := c.tlsConfig()
	if err != nil {
		return nil, err
	}
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	conn, err := quic.DialAddr(ctx, c.Server, config, &quic.Config{
		HandshakeIdleTimeout: timeout,
		MaxIdleTimeout:       30 * time.Second,
		KeepAlivePeriod:      0,
	})
	if err != nil {
		return nil, err
	}
	c.conn = conn
	return conn, nil
}

// Resolve sends one DNS query over QUIC and returns the answer with the
// original message ID restored.
func (c *DoQClient) Resolve(ctx context.Context, query []byte) ([]byte, error) {
	if len(query) < 12 || len(query) > maxDNSMessage {
		return nil, errors.New("invalid DNS query length")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	conn, err := c.connection(ctx)
	if err != nil {
		c.reset()
		return nil, err
	}
	answer, err := c.exchange(ctx, conn, query)
	if err != nil {
		c.reset()
		return nil, err
	}
	return answer, nil
}

func (c *DoQClient) exchange(ctx context.Context, conn *quic.Conn, query []byte) ([]byte, error) {
	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		return nil, err
	}
	defer stream.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = stream.SetDeadline(deadline)
	}
	wire := append([]byte(nil), query...)
	id := binary.BigEndian.Uint16(wire[:2])
	wire[0], wire[1] = 0, 0
	frame := make([]byte, 2+len(wire))
	binary.BigEndian.PutUint16(frame, uint16(len(wire)))
	copy(frame[2:], wire)
	if _, err := stream.Write(frame); err != nil {
		return nil, err
	}
	// FIN: DoQ carries exactly one response per stream.
	if err := stream.Close(); err != nil {
		return nil, err
	}
	size := make([]byte, 2)
	if _, err := io.ReadFull(stream, size); err != nil {
		return nil, err
	}
	length := int(binary.BigEndian.Uint16(size))
	if length < 12 || length > maxDNSMessage {
		return nil, errors.New("the DoQ answer has an invalid length")
	}
	answer := make([]byte, length)
	if _, err := io.ReadFull(stream, answer); err != nil {
		return nil, err
	}
	if answer[0] != 0 || answer[1] != 0 {
		return nil, errors.New("the DoQ answer must use message ID 0")
	}
	answer[0], answer[1] = byte(id>>8), byte(id)
	return answer, nil
}

func (c *DoQClient) reset() {
	c.mu.Lock()
	if c.conn != nil {
		c.conn.CloseWithError(0, "")
		c.conn = nil
	}
	c.mu.Unlock()
}

func (c *DoQClient) Close() error {
	c.reset()
	return nil
}

// DoQResolve is the one-shot helper used when DoQ is enabled. Callers fall back
// to DNS-over-HTTPS when it returns an error.
func DoQResolve(ctx context.Context, server string, query []byte, config *tls.Config, timeout time.Duration) ([]byte, error) {
	client := NewDoQClient(server, config)
	client.Timeout = timeout
	defer client.Close()
	return client.Resolve(ctx, query)
}
