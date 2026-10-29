package dnsproxy

import (
	"encoding/binary"
	"errors"
	"io"
	"sync"
	"time"
)

type Resolver struct {
	Dialer func(host string, port int) (io.ReadWriteCloser, error)
	Server string
	cache  sync.Map
}

type entry struct {
	answer []byte
	until  time.Time
}

func (r *Resolver) Resolve(query []byte) ([]byte, error) {
	key := question(query)
	if key != "" {
		if cached, ok := r.cache.Load(key); ok {
			item := cached.(entry)
			if time.Now().Before(item.until) {
				return patchID(query, item.answer), nil
			}
			r.cache.Delete(key)
		}
	}
	answer, err := r.exchange(query)
	if err != nil {
		return nil, err
	}
	if key != "" {
		copied := append([]byte{}, answer...)
		r.cache.Store(key, entry{answer: copied, until: time.Now().Add(5 * time.Minute)})
	}
	return answer, nil
}

func (r *Resolver) exchange(query []byte) ([]byte, error) {
	connection, err := r.Dialer(r.Server, 53)
	if err != nil {
		return nil, err
	}
	defer connection.Close()
	framed := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(framed, uint16(len(query)))
	copy(framed[2:], query)
	if _, err := connection.Write(framed); err != nil {
		return nil, err
	}
	size := make([]byte, 2)
	if _, err := io.ReadFull(connection, size); err != nil {
		return nil, err
	}
	length := int(binary.BigEndian.Uint16(size))
	if length == 0 || length > 65535 {
		return nil, errors.New("the resolver sent a bad answer")
	}
	answer := make([]byte, length)
	if _, err := io.ReadFull(connection, answer); err != nil {
		return nil, err
	}
	return answer, nil
}

func question(query []byte) string {
	if len(query) < 13 {
		return ""
	}
	offset := 12
	var name []byte
	for offset < len(query) {
		length := int(query[offset])
		offset++
		if length == 0 {
			break
		}
		if length > 63 || offset+length > len(query) {
			return ""
		}
		name = append(name, query[offset:offset+length]...)
		name = append(name, '.')
		offset += length
	}
	if offset+4 > len(query) {
		return ""
	}
	kind := binary.BigEndian.Uint16(query[offset:])
	return string(name) + string(rune(kind>>8)) + string(rune(kind&0xFF))
}

func patchID(query, answer []byte) []byte {
	if len(query) < 2 || len(answer) < 2 {
		return answer
	}
	copied := append([]byte{}, answer...)
	copied[0] = query[0]
	copied[1] = query[1]
	return copied
}

const Address = "1.1.1.1"
