package advanced

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// WebSocketGUID is the RFC 6455 handshake constant.
const WebSocketGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// WSSProtocol is the subprotocol Gozar negotiates for its byte tunnel.
const WSSProtocol = "gozar-tunnel"

// TargetHeader carries the CONNECT destination inside the upgrade request.
const TargetHeader = "X-Gozar-Target"

// DialWSS opens a TLS connection, performs a real RFC 6455 upgrade and returns a
// net.Conn whose bytes are carried in masked binary WebSocket frames.
func DialWSS(ctx context.Context, address string, config *tls.Config, host string, port int, pass string, params Params, timeout time.Duration) (net.Conn, error) {
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	if host == "" || port < 1 || port > 65535 {
		return nil, errors.New("invalid tunnel destination")
	}
	params = params.WithDefaults()
	if config == nil {
		config = &tls.Config{}
	} else {
		config = config.Clone()
	}
	if config.ServerName == "" {
		serverName, _, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		config.ServerName = serverName
	}
	config.NextProtos = []string{"http/1.1"}
	dialer := &tls.Dialer{Config: config}
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	keyBytes := make([]byte, 16)
	if _, err := rand.Read(keyBytes); err != nil {
		conn.Close()
		return nil, err
	}
	key := base64.StdEncoding.EncodeToString(keyBytes)
	var request strings.Builder
	fmt.Fprintf(&request, "GET %s HTTP/1.1\r\n", params.WSSPath)
	fmt.Fprintf(&request, "Host: %s\r\n", config.ServerName)
	request.WriteString("Upgrade: websocket\r\nConnection: Upgrade\r\n")
	fmt.Fprintf(&request, "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n", key)
	fmt.Fprintf(&request, "Sec-WebSocket-Protocol: %s\r\n", WSSProtocol)
	fmt.Fprintf(&request, "%s: %s\r\n", TargetHeader, net.JoinHostPort(host, strconv.Itoa(port)))
	if pass != "" {
		fmt.Fprintf(&request, "Proxy-Authorization: Bearer %s\r\n", pass)
	}
	request.WriteString("\r\n")
	if _, err := conn.Write([]byte(request.String())); err != nil {
		conn.Close()
		return nil, err
	}
	reader := bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, nil)
	if err != nil {
		conn.Close()
		return nil, err
	}
	if response.StatusCode != http.StatusSwitchingProtocols {
		response.Body.Close()
		conn.Close()
		return nil, fmt.Errorf("the WebSocket upgrade answered %d", response.StatusCode)
	}
	// On a 101 the body is the tunnel itself: never close or drain it.
	if !strings.EqualFold(response.Header.Get("Upgrade"), "websocket") {
		conn.Close()
		return nil, errors.New("the peer did not upgrade to websocket")
	}
	if response.Header.Get("Sec-WebSocket-Accept") != AcceptKey(key) {
		conn.Close()
		return nil, errors.New("the WebSocket accept key does not match")
	}
	_ = conn.SetDeadline(time.Time{})
	return &wsConn{conn: conn, reader: reader, mask: true}, nil
}

// AcceptKey computes the Sec-WebSocket-Accept value for a client key.
func AcceptKey(key string) string {
	sum := sha1.Sum([]byte(key + WebSocketGUID))
	return base64.StdEncoding.EncodeToString(sum[:])
}

// wsConn adapts a WebSocket byte stream to net.Conn. Client frames are masked as
// RFC 6455 requires; server frames are not.
type wsConn struct {
	conn   net.Conn
	reader *bufio.Reader
	mask   bool

	writeMu   sync.Mutex
	pending   []byte
	closeOnce sync.Once
}

func (c *wsConn) Read(p []byte) (int, error) {
	for len(c.pending) == 0 {
		payload, opcode, err := c.readFrame()
		if err != nil {
			return 0, err
		}
		switch opcode {
		case 0x8: // close
			return 0, io.EOF
		case 0x9: // ping -> pong
			if err := c.writeFrame(0xA, payload); err != nil {
				return 0, err
			}
			continue
		case 0xA: // pong
			continue
		}
		if len(payload) == 0 {
			// An empty binary frame is Gozar's half-close marker.
			return 0, io.EOF
		}
		c.pending = payload
	}
	count := copy(p, c.pending)
	c.pending = c.pending[count:]
	return count, nil
}

func (c *wsConn) readFrame() ([]byte, byte, error) {
	var header [2]byte
	if _, err := io.ReadFull(c.reader, header[:]); err != nil {
		return nil, 0, err
	}
	opcode := header[0] & 0x0f
	masked := header[1]&0x80 != 0
	length := uint64(header[1] & 0x7f)
	switch length {
	case 126:
		var extended [2]byte
		if _, err := io.ReadFull(c.reader, extended[:]); err != nil {
			return nil, 0, err
		}
		length = uint64(binary.BigEndian.Uint16(extended[:]))
	case 127:
		var extended [8]byte
		if _, err := io.ReadFull(c.reader, extended[:]); err != nil {
			return nil, 0, err
		}
		length = binary.BigEndian.Uint64(extended[:])
	}
	if length > 1<<24 {
		return nil, 0, errors.New("the WebSocket frame is too large")
	}
	var maskKey [4]byte
	if masked {
		if _, err := io.ReadFull(c.reader, maskKey[:]); err != nil {
			return nil, 0, err
		}
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(c.reader, payload); err != nil {
		return nil, 0, err
	}
	if masked {
		for index := range payload {
			payload[index] ^= maskKey[index%4]
		}
	}
	return payload, opcode, nil
}

func (c *wsConn) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if err := c.writeFrame(0x2, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wsConn) writeFrame(opcode byte, payload []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	frame := []byte{0x80 | opcode}
	length := len(payload)
	maskBit := byte(0)
	if c.mask {
		maskBit = 0x80
	}
	switch {
	case length < 126:
		frame = append(frame, maskBit|byte(length))
	case length <= 0xffff:
		frame = append(frame, maskBit|126, byte(length>>8), byte(length))
	default:
		var extended [8]byte
		binary.BigEndian.PutUint64(extended[:], uint64(length))
		frame = append(frame, maskBit|127)
		frame = append(frame, extended[:]...)
	}
	if c.mask {
		var maskKey [4]byte
		if _, err := rand.Read(maskKey[:]); err != nil {
			return err
		}
		frame = append(frame, maskKey[:]...)
		masked := make([]byte, length)
		for index := range payload {
			masked[index] = payload[index] ^ maskKey[index%4]
		}
		frame = append(frame, masked...)
	} else {
		frame = append(frame, payload...)
	}
	for len(frame) > 0 {
		count, err := c.conn.Write(frame)
		if err != nil {
			return err
		}
		frame = frame[count:]
	}
	return nil
}

// CloseWrite sends the empty-frame half-close marker so the peer can finish
// sending its own response, matching the other Gozar transports.
func (c *wsConn) CloseWrite() error { return c.writeFrame(0x2, nil) }

func (c *wsConn) Close() error {
	var err error
	c.closeOnce.Do(func() {
		_ = c.writeFrame(0x8, []byte{0x03, 0xe8})
		err = c.conn.Close()
	})
	return err
}

func (c *wsConn) LocalAddr() net.Addr                { return c.conn.LocalAddr() }
func (c *wsConn) RemoteAddr() net.Addr               { return c.conn.RemoteAddr() }
func (c *wsConn) SetDeadline(t time.Time) error      { return c.conn.SetDeadline(t) }
func (c *wsConn) SetReadDeadline(t time.Time) error  { return c.conn.SetReadDeadline(t) }
func (c *wsConn) SetWriteDeadline(t time.Time) error { return c.conn.SetWriteDeadline(t) }

// NewWebSocketConn wraps an already upgraded connection (used by the test
// server and by the tunnel when a proxy performed the upgrade).
func NewWebSocketConn(conn net.Conn, reader *bufio.Reader, mask bool) net.Conn {
	if reader == nil {
		reader = bufio.NewReader(conn)
	}
	return &wsConn{conn: conn, reader: reader, mask: mask}
}
