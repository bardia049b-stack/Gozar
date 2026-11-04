package packet

import (
	"encoding/binary"
	"errors"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

var sequence atomic.Uint32

func BuildUDP(src, dst netip.Addr, srcPort, dstPort uint16, payload []byte) []byte {
	if !src.Is4() || !dst.Is4() {
		return nil
	}
	total := 28 + len(payload)
	if total > 65535 {
		return nil
	}
	p := make([]byte, total)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(total))
	binary.BigEndian.PutUint16(p[4:6], uint16(sequence.Add(1)))
	p[8] = 64
	p[9] = 17
	copy(p[12:16], src.AsSlice())
	copy(p[16:20], dst.AsSlice())
	binary.BigEndian.PutUint16(p[20:22], srcPort)
	binary.BigEndian.PutUint16(p[22:24], dstPort)
	binary.BigEndian.PutUint16(p[24:26], uint16(8+len(payload)))
	copy(p[28:], payload)
	binary.BigEndian.PutUint16(p[10:12], checksum(p[:20]))
	binary.BigEndian.PutUint16(p[26:28], udpChecksum(p))
	return p
}

func ParseUDP(p []byte) (src, dst netip.Addr, srcPort, dstPort uint16, payload []byte, err error) {
	if len(p) < 28 || p[0]>>4 != 4 || p[9] != 17 {
		return src, dst, 0, 0, nil, errors.New("not ipv4 udp")
	}
	ihl := int(p[0]&15) * 4
	if ihl < 20 || len(p) < ihl+8 {
		return src, dst, 0, 0, nil, errors.New("short udp")
	}
	src = netip.AddrFrom4([4]byte(p[12:16]))
	dst = netip.AddrFrom4([4]byte(p[16:20]))
	srcPort = binary.BigEndian.Uint16(p[ihl : ihl+2])
	dstPort = binary.BigEndian.Uint16(p[ihl+2 : ihl+4])
	length := int(binary.BigEndian.Uint16(p[ihl+4 : ihl+6]))
	if length < 8 || ihl+length > len(p) {
		return src, dst, 0, 0, nil, errors.New("bad udp length")
	}
	payload = p[ihl+8 : ihl+length]
	return src, dst, srcPort, dstPort, payload, nil
}

func Fragment(p []byte, mtu int) [][]byte {
	if len(p) == 0 || p[0]>>4 != 4 {
		return nil
	}
	ihl := int(p[0]&15) * 4
	if ihl < 20 || len(p) <= mtu || mtu <= ihl+8 {
		return [][]byte{append([]byte(nil), p...)}
	}
	payload := p[ihl:]
	chunk := (mtu - ihl) / 8 * 8
	id := binary.BigEndian.Uint16(p[4:6])
	out := make([][]byte, 0, len(payload)/chunk+1)
	for offset := 0; offset < len(payload); offset += chunk {
		end := offset + chunk
		if end > len(payload) {
			end = len(payload)
		}
		fragment := make([]byte, ihl+end-offset)
		copy(fragment[:ihl], p[:ihl])
		fragment[0] = 0x45
		binary.BigEndian.PutUint16(fragment[2:4], uint16(len(fragment)))
		binary.BigEndian.PutUint16(fragment[4:6], id)
		flags := uint16(0)
		if end < len(payload) {
			flags = 0x2000
		}
		binary.BigEndian.PutUint16(fragment[6:8], flags|uint16(offset/8))
		binary.BigEndian.PutUint16(fragment[10:12], 0)
		copy(fragment[ihl:], payload[offset:end])
		binary.BigEndian.PutUint16(fragment[10:12], checksum(fragment[:ihl]))
		out = append(out, fragment)
	}
	return out
}

type Reassembler struct {
	mu      sync.Mutex
	entries map[uint16]*entry
	created time.Time
}

type entry struct {
	frags    map[int][]byte
	last     int
	haveLast bool
	stored   time.Time
}

func NewReassembler() *Reassembler {
	return &Reassembler{entries: make(map[uint16]*entry), created: time.Now()}
}

func (r *Reassembler) Add(p []byte) ([]byte, bool) {
	if len(p) < 20 || p[0]>>4 != 4 {
		return nil, false
	}
	ihl := int(p[0]&15) * 4
	total := int(binary.BigEndian.Uint16(p[2:4]))
	if ihl < 20 || total < ihl || total > len(p) {
		return nil, false
	}
	id := binary.BigEndian.Uint16(p[4:6])
	flags := binary.BigEndian.Uint16(p[6:8])
	offset := int(flags&0x1fff) * 8
	more := flags&0x2000 != 0
	if offset == 0 && !more {
		return append([]byte(nil), p[:total]...), true
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	for key, value := range r.entries {
		if now.Sub(value.stored) > 5*time.Second {
			delete(r.entries, key)
		}
	}
	e := r.entries[id]
	if e == nil {
		if len(r.entries) > 256 {
			return nil, false
		}
		e = &entry{frags: make(map[int][]byte), stored: now}
		r.entries[id] = e
	}
	e.frags[offset] = append([]byte(nil), p[ihl:total]...)
	e.stored = now
	if !more {
		e.last = offset + total - ihl
		e.haveLast = true
	}
	if !e.haveLast {
		return nil, false
	}
	cursor := 0
	for cursor < e.last {
		chunk, ok := e.frags[cursor]
		if !ok {
			return nil, false
		}
		cursor += len(chunk)
	}
	first := e.frags[0]
	if first == nil {
		return nil, false
	}
	out := make([]byte, ihl+e.last)
	copy(out[:ihl], p[:ihl])
	out[0] = 0x45
	binary.BigEndian.PutUint16(out[2:4], uint16(len(out)))
	binary.BigEndian.PutUint16(out[6:8], 0)
	binary.BigEndian.PutUint16(out[10:12], 0)
	cursor = 0
	for cursor < e.last {
		chunk := e.frags[cursor]
		copy(out[ihl+cursor:], chunk)
		cursor += len(chunk)
	}
	binary.BigEndian.PutUint16(out[10:12], checksum(out[:ihl]))
	delete(r.entries, id)
	return out, true
}

func checksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	value := uint16(^sum)
	if value == 0 {
		return 0xffff
	}
	return value
}

func udpChecksum(p []byte) uint16 {
	ihl := int(p[0]&15) * 4
	udp := p[ihl:]
	var sum uint32
	sum += uint32(p[12])<<8 | uint32(p[13])
	sum += uint32(p[14])<<8 | uint32(p[15])
	sum += uint32(p[16])<<8 | uint32(p[17])
	sum += uint32(p[18])<<8 | uint32(p[19])
	sum += 17
	sum += uint32(len(udp))
	for i := 0; i+1 < len(udp); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(udp[i : i+2]))
	}
	if len(udp)%2 == 1 {
		sum += uint32(udp[len(udp)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	value := uint16(^sum)
	if value == 0 {
		return 0xffff
	}
	return value
}
