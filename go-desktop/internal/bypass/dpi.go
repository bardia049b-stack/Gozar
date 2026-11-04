package bypass

import (
	"encoding/binary"
	"math/rand"
	"net"
	"sync"
	"time"
)

type FragmentConn struct {
	net.Conn
	once  sync.Once
	Chunk int
}

func (c *FragmentConn) Write(p []byte) (int, error) {
	first := false
	c.once.Do(func() { first = true })
	if !first || c.Chunk <= 0 || len(p) < c.Chunk {
		return c.Conn.Write(p)
	}
	written := 0
	for written < len(p) {
		end := written + c.Chunk
		if end > len(p) {
			end = len(p)
		}
		n, err := c.Conn.Write(p[written:end])
		written += n
		if err != nil {
			return written, err
		}
	}
	return written, nil
}

func Pad(payload []byte, block int) []byte {
	if block <= 0 {
		block = 16
	}
	padded := make([]byte, 2+len(payload))
	binary.BigEndian.PutUint16(padded[0:2], uint16(len(payload)))
	copy(padded[2:], payload)
	for len(padded)%block != 0 {
		padded = append(padded, byte(rand.Intn(256)))
	}
	return padded
}

func Unpad(padded []byte) ([]byte, bool) {
	if len(padded) < 2 {
		return nil, false
	}
	size := int(binary.BigEndian.Uint16(padded[0:2]))
	if size > len(padded)-2 {
		return nil, false
	}
	return padded[2 : 2+size], true
}

func Jitter(max time.Duration) time.Duration {
	if max <= 0 {
		return 0
	}
	return time.Duration(rand.Int63n(int64(max)))
}
