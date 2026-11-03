package warp

import (
	"encoding/binary"
	"net/netip"
)

func ipv4UDP(source, destination netip.Addr, sourcePort, destinationPort int, payload []byte) []byte {
	length := 20 + 8 + len(payload)
	packet := make([]byte, length)
	packet[0] = 0x45
	packet[1] = 0
	binary.BigEndian.PutUint16(packet[2:4], uint16(length))
	binary.BigEndian.PutUint16(packet[4:6], 0x1234)
	binary.BigEndian.PutUint16(packet[6:8], 0x4000)
	packet[8] = 64
	packet[9] = 17
	from := source.As4()
	copy(packet[12:16], from[:])
	to := destination.As4()
	copy(packet[16:20], to[:])
	binary.BigEndian.PutUint16(packet[10:12], checksum(packet[:20], 0))
	udp := packet[20:]
	binary.BigEndian.PutUint16(udp[0:2], uint16(sourcePort))
	binary.BigEndian.PutUint16(udp[2:4], uint16(destinationPort))
	binary.BigEndian.PutUint16(udp[4:6], uint16(8+len(payload)))
	copy(udp[8:], payload)
	pseudo := make([]byte, 12+8+len(payload))
	copy(pseudo[0:4], from[:])
	copy(pseudo[4:8], to[:])
	pseudo[8] = 0
	pseudo[9] = 17
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(8+len(payload)))
	copy(pseudo[12:], udp[:8])
	copy(pseudo[20:], payload)
	binary.BigEndian.PutUint16(udp[6:8], checksum(pseudo, 0))
	return packet
}

func checksum(data []byte, initial uint32) uint16 {
	sum := initial
	for index := 0; index+1 < len(data); index += 2 {
		sum += uint32(data[index])<<8 | uint32(data[index+1])
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func appendVarint(b []byte, value uint64) []byte {
	switch {
	case value < 1<<6:
		return append(b, byte(value))
	case value < 1<<14:
		return append(b, byte(value>>8)|0x40, byte(value))
	case value < 1<<30:
		return append(b, byte(value>>24)|0x80, byte(value>>16), byte(value>>8), byte(value))
	default:
		return append(b, byte(value>>56)|0xc0, byte(value>>48), byte(value>>40), byte(value>>32),
			byte(value>>24), byte(value>>16), byte(value>>8), byte(value))
	}
}

func parseVarint(b []byte) (uint64, int, bool) {
	if len(b) == 0 {
		return 0, 0, false
	}
	switch b[0] >> 6 {
	case 0:
		return uint64(b[0] & 0x3f), 1, true
	case 1:
		if len(b) < 2 {
			return 0, 0, false
		}
		return uint64(b[0]&0x3f)<<8 | uint64(b[1]), 2, true
	case 2:
		if len(b) < 4 {
			return 0, 0, false
		}
		return uint64(b[0]&0x3f)<<24 | uint64(b[1])<<16 | uint64(b[2])<<8 | uint64(b[3]), 4, true
	default:
		if len(b) < 8 {
			return 0, 0, false
		}
		value := uint64(b[0] & 0x3f)
		for index := 1; index < 8; index++ {
			value = value<<8 | uint64(b[index])
		}
		return value, 8, true
	}
}
