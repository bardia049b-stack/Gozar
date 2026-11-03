package gozarcore

import (
	"io"
	"sync"
	"time"
)

type Stream struct {
	conn     io.ReadWriteCloser
	release  func()
	once     sync.Once
	mu       sync.Mutex
	deadline time.Time
}

type deadliner interface {
	SetDeadline(time.Time) error
}

func (s *Stream) SetDeadline(timeoutMs int) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if timeoutMs <= 0 {
		s.deadline = time.Time{}
	} else {
		s.deadline = time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	}
	if conn, ok := s.conn.(deadliner); ok {
		_ = conn.SetDeadline(s.deadline)
	}
}

func (s *Stream) Read(limit int) []byte {
	if s == nil || s.conn == nil {
		return nil
	}
	if limit <= 0 || limit > 65536 {
		limit = 16384
	}
	buffer := make([]byte, limit)
	count, err := s.conn.Read(buffer)
	if count > 0 {
		return buffer[:count]
	}
	if err != nil {
		return []byte{}
	}
	return nil
}

func (s *Stream) Write(payload []byte) int {
	if s == nil || s.conn == nil || len(payload) == 0 {
		return -1
	}
	written := 0
	for written < len(payload) {
		count, err := s.conn.Write(payload[written:])
		if count > 0 {
			written += count
		}
		if err != nil {
			if written == 0 {
				return -1
			}
			return written
		}
		if count == 0 {
			break
		}
	}
	return written
}

func (s *Stream) Close() {
	if s == nil {
		return
	}
	s.once.Do(func() {
		if s.conn != nil {
			_ = s.conn.Close()
		}
		if s.release != nil {
			s.release()
		}
	})
}
