package tunnel

import (
	"bufio"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

type Target struct {
	Host string
	Port int
}

type Dup interface {
	io.ReadWriteCloser
}

var (
	h2Mu       sync.Mutex
	h2DownTill time.Time
)

func Open(target Target, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	var firstErr error
	if !h2Broken() {
		conn, err := openH2(target, host, port, pass, timeout)
		if err == nil {
			return conn, nil
		}
		firstErr = err
		markH2Broken()
	}
	conn, err := openH1(target, host, port, pass, timeout)
	if err == nil {
		return conn, nil
	}
	if firstErr != nil {
		return nil, firstErr
	}
	return nil, err
}

func h2Broken() bool {
	h2Mu.Lock()
	defer h2Mu.Unlock()
	return time.Now().Before(h2DownTill)
}

func markH2Broken() {
	h2Mu.Lock()
	h2DownTill = time.Now().Add(10 * time.Minute)
	h2Mu.Unlock()
}

func dialTLS(target Target, protocols []string, timeout time.Duration) (*tls.Conn, error) {
	raw, err := net.DialTimeout("tcp", net.JoinHostPort(target.Host, strconv.Itoa(target.Port)), timeout)
	if err != nil {
		return nil, err
	}
	conn := tls.Client(raw, &tls.Config{
		ServerName: target.Host,
		NextProtos: protocols,
		MinVersion: tls.VersionTLS12,
	})
	conn.SetDeadline(time.Now().Add(timeout))
	if err := conn.Handshake(); err != nil {
		raw.Close()
		return nil, err
	}
	conn.SetDeadline(time.Time{})
	return conn, nil
}

func openH1(target Target, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	conn, err := dialTLS(target, []string{"http/1.1"}, timeout)
	if err != nil {
		return nil, err
	}
	authority := net.JoinHostPort(host, strconv.Itoa(port))
	request := "CONNECT " + authority + " HTTP/1.1\r\n" +
		"Host: " + authority + "\r\n" +
		"Proxy-Authorization: Bearer " + pass + "\r\n" +
		"User-Agent: " + agent + "\r\n\r\n"
	if _, err := conn.Write([]byte(request)); err != nil {
		conn.Close()
		return nil, err
	}
	reader := bufio.NewReader(conn)
	conn.SetDeadline(time.Now().Add(timeout))
	status, err := reader.ReadString('\n')
	if err != nil {
		conn.Close()
		return nil, err
	}
	code := 0
	if fields := strings.Fields(status); len(fields) >= 2 {
		code, _ = strconv.Atoi(fields[1])
	}
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			conn.Close()
			return nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	conn.SetDeadline(time.Time{})
	if code != 200 {
		conn.Close()
		return nil, reason(code)
	}
	return &stream{conn: conn, reader: reader}, nil
}

type stream struct {
	conn   *tls.Conn
	reader *bufio.Reader
}

func (s *stream) Read(p []byte) (int, error)  { return s.reader.Read(p) }
func (s *stream) Write(p []byte) (int, error) { return s.conn.Write(p) }
func (s *stream) Close() error                { return s.conn.Close() }

func reason(code int) error {
	switch code {
	case 401, 403:
		return errors.New("Mozilla refused the proxy pass; sign in again")
	case 407:
		return errors.New("Mozilla needs a proxy password")
	case 429:
		return errors.New("too many connections; wait a minute")
	case 0:
		return errors.New("the proxy did not answer")
	}
	return fmt.Errorf("the proxy answered %d", code)
}

func status(payload []byte) int {
	if len(payload) < 3 {
		return 0
	}
	return int(payload[0])<<8 | int(payload[1])
}

func frame(ftype, flags byte, id uint32, payload []byte) []byte {
	out := make([]byte, 9+len(payload))
	out[0] = byte(len(payload) >> 16)
	out[1] = byte(len(payload) >> 8)
	out[2] = byte(len(payload))
	out[3] = ftype
	out[4] = flags
	binary.BigEndian.PutUint32(out[5:9], id&0x7FFFFFFF)
	copy(out[9:], payload)
	return out
}

func pair(name, value string) []byte {
	block := []byte{0}
	block = append(block, []byte(name)...)
	block = append(block, []byte(value)...)
	return append(block, 0)
}

func settings() []byte {
	payload := make([]byte, 6)
	binary.BigEndian.PutUint16(payload[0:2], 0x0003)
	binary.BigEndian.PutUint32(payload[2:6], 1<<20)
	return payload
}

func openH2(target Target, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	conn, err := dialTLS(target, []string{"h2"}, timeout)
	if err != nil {
		return nil, err
	}
	authority := net.JoinHostPort(host, strconv.Itoa(port))
	var block []byte
	block = append(block, pair(":method", "CONNECT")...)
	block = append(block, pair(":authority", authority)...)
	block = append(block, pair("proxy-authorization", "Bearer "+pass)...)
	block = append(block, pair("user-agent", agent)...)

	intro := append([]byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"), frame(0x04, 0, 0, settings())...)
	intro = append(intro, frame(0x01, 0x04, 1, block)...)
	if _, err := conn.Write(intro); err != nil {
		conn.Close()
		return nil, err
	}

	reader := bufio.NewReader(conn)
	conn.SetDeadline(time.Now().Add(timeout))
	header := make([]byte, 9)
	for {
		if _, err := io.ReadFull(reader, header); err != nil {
			conn.Close()
			return nil, errors.New("the proxy closed the connection")
		}
		size := int(header[0])<<16 | int(header[1])<<8 | int(header[2])
		ftype := header[3]
		flags := header[4]
		id := binary.BigEndian.Uint32(header[5:9]) & 0x7FFFFFFF
		payload := make([]byte, size)
		if size > 0 {
			if _, err := io.ReadFull(reader, payload); err != nil {
				conn.Close()
				return nil, errors.New("the proxy closed the connection")
			}
		}
		switch ftype {
		case 0x04:
			if flags&0x01 == 0 {
				conn.Write(frame(0x04, 0x01, 0, nil))
			}
		case 0x06:
			if flags&0x01 == 0 {
				conn.Write(frame(0x06, 0x01, 0, payload))
			}
		case 0x01:
			if id != 1 {
				continue
			}
			code := status(payload)
			if code == 200 {
				conn.SetDeadline(time.Time{})
				return newH2Stream(conn, reader, 1), nil
			}
			conn.Close()
			return nil, reason(code)
		case 0x03:
			conn.Close()
			return nil, errors.New("the proxy reset the tunnel")
		case 0x07:
			conn.Close()
			return nil, errors.New("the proxy closed the connection")
		}
	}
}

type h2Stream struct {
	conn   *tls.Conn
	reader *bufio.Reader
	id     uint32
	write  sync.Mutex
	window sync.Mutex
	cond   *sync.Cond
	sendW  int64
	connW  int64
	pending []byte
	eof    bool
}

func newH2Stream(conn *tls.Conn, reader *bufio.Reader, id uint32) *h2Stream {
	s := &h2Stream{conn: conn, reader: reader, id: id, sendW: 65535, connW: 65535}
	s.cond = sync.NewCond(&s.window)
	return s
}

func (s *h2Stream) send(bytes []byte) error {
	s.write.Lock()
	defer s.write.Unlock()
	_, err := s.conn.Write(bytes)
	return err
}

func (s *h2Stream) Read(p []byte) (int, error) {
	if len(s.pending) > 0 {
		n := copy(p, s.pending)
		s.pending = s.pending[n:]
		return n, nil
	}
	if s.eof {
		return 0, io.EOF
	}
	header := make([]byte, 9)
	for {
		if _, err := io.ReadFull(s.reader, header); err != nil {
			return 0, err
		}
		size := int(header[0])<<16 | int(header[1])<<8 | int(header[2])
		ftype := header[3]
		flags := header[4]
		id := binary.BigEndian.Uint32(header[5:9]) & 0x7FFFFFFF
		payload := make([]byte, size)
		if size > 0 {
			if _, err := io.ReadFull(s.reader, payload); err != nil {
				return 0, err
			}
		}
		switch ftype {
		case 0x00:
			if id != s.id || size == 0 {
				continue
			}
			s.grow(size)
			if len(p) >= size {
				copy(p, payload)
				return size, nil
			}
			copy(p, payload[:len(p)])
			s.pending = append([]byte{}, payload[len(p):]...)
			return len(p), nil
		case 0x01:
			if id == s.id && flags&0x01 != 0 {
				s.eof = true
				return 0, io.EOF
			}
		case 0x03, 0x07:
			return 0, io.EOF
		case 0x04:
			if flags&0x01 == 0 {
				s.send(frame(0x04, 0x01, 0, nil))
			}
		case 0x06:
			if flags&0x01 == 0 {
				s.send(frame(0x06, 0x01, 0, payload))
			}
		case 0x08:
			if len(payload) >= 4 {
				amount := int64(binary.BigEndian.Uint32(payload[:4]) & 0x7FFFFFFF)
				s.window.Lock()
				if id == 0 {
					s.connW += amount
				} else if id == s.id {
					s.sendW += amount
				}
				s.cond.Broadcast()
				s.window.Unlock()
			}
		}
	}
}

func (s *h2Stream) grow(size int) {
	update := make([]byte, 4)
	binary.BigEndian.PutUint32(update, uint32(size)&0x7FFFFFFF)
	s.send(frame(0x08, 0, 0, update))
	s.send(frame(0x08, 0, s.id, update))
}

func (s *h2Stream) Write(p []byte) (int, error) {
	written := 0
	for written < len(p) {
		size := len(p) - written
		if size > 16384 {
			size = 16384
		}
		if !s.waitWindow(int64(size)) {
			return written, errors.New("upload stalled")
		}
		if err := s.send(frame(0x00, 0, s.id, p[written:written+size])); err != nil {
			return written, err
		}
		s.window.Lock()
		s.sendW -= int64(size)
		s.connW -= int64(size)
		s.window.Unlock()
		written += size
	}
	return written, nil
}

func (s *h2Stream) waitWindow(size int64) bool {
	deadline := time.Now().Add(25 * time.Second)
	s.window.Lock()
	defer s.window.Unlock()
	for s.sendW < size || s.connW < size {
		if time.Now().After(deadline) {
			return false
		}
		s.cond.Wait()
	}
	return true
}

func (s *h2Stream) Close() error {
	s.send(frame(0x03, 0, s.id, []byte{0, 0, 0, 0x08}))
	s.send(frame(0x07, 0, 0, nil))
	return s.conn.Close()
}

func Probe(target Target, pass string, timeout time.Duration) (string, error) {
	conn, err := Open(target, "ifconfig.me", 80, pass, timeout)
	if err != nil {
		return "", err
	}
	defer conn.Close()
	request := "GET /ip HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: curl/8\r\nAccept: */*\r\nConnection: close\r\n\r\n"
	if _, err := conn.Write([]byte(request)); err != nil {
		return "", err
	}
	data, err := io.ReadAll(io.LimitReader(conn, 4096))
	if err != nil && len(data) == 0 {
		return "", err
	}
	text := string(data)
	if index := strings.Index(text, "\r\n\r\n"); index >= 0 {
		text = text[index+4:]
	}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			if len(line) > 64 {
				line = line[:64]
			}
			return line, nil
		}
	}
	return "", errors.New("the probe returned nothing")
}
