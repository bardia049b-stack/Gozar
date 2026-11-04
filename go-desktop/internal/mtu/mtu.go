package mtu

import "encoding/binary"

const (
	MinTunnelMTU = 1280
	MaxTunnelMTU = 1360
	DefaultMTU   = 1280
	WarpMTU      = 1180
)

func Clamp(value int) int {
	if value < MinTunnelMTU {
		return MinTunnelMTU
	}
	if value > MaxTunnelMTU {
		return MaxTunnelMTU
	}
	return value
}

func MSS(mtu int) int {
	return Clamp(mtu) - 40
}

func ClampSYN(packet []byte, mtu int) bool {
	if len(packet) < 40 || packet[0]>>4 != 4 {
		return false
	}
	ihl := int(packet[0]&0x0f) * 4
	if ihl < 20 || len(packet) < ihl+20 || packet[9] != 6 {
		return false
	}
	tcp := packet[ihl:]
	if tcp[13]&0x02 == 0 || tcp[13]&0x10 != 0 {
		return false
	}
	offset := int(tcp[12]>>4) * 4
	if offset < 20 || offset > len(tcp) {
		return false
	}
	limit := uint16(MSS(mtu))
	options := tcp[20:offset]
	changed := false
	for i := 0; i < len(options); {
		kind := options[i]
		if kind == 0 {
			break
		}
		if kind == 1 {
			i++
			continue
		}
		if i+1 >= len(options) {
			break
		}
		length := int(options[i+1])
		if length < 2 || i+length > len(options) {
			break
		}
		if kind == 2 && length == 4 {
			current := binary.BigEndian.Uint16(options[i+2 : i+4])
			if current > limit {
				binary.BigEndian.PutUint16(options[i+2:i+4], limit)
				changed = true
			}
		}
		i += length
	}
	if changed {
		tcp[16], tcp[17] = 0, 0
		binary.BigEndian.PutUint16(tcp[16:18], checksum(packet, ihl, tcp))
	}
	return changed
}

func checksum(packet []byte, ihl int, tcp []byte) uint16 {
	var sum uint32
	sum += uint32(packet[12])<<8 | uint32(packet[13])
	sum += uint32(packet[14])<<8 | uint32(packet[15])
	sum += uint32(packet[16])<<8 | uint32(packet[17])
	sum += uint32(packet[18])<<8 | uint32(packet[19])
	sum += 6
	sum += uint32(len(tcp))
	for i := 0; i+1 < len(tcp); i += 2 {
		sum += uint32(tcp[i])<<8 | uint32(tcp[i+1])
	}
	if len(tcp)%2 == 1 {
		sum += uint32(tcp[len(tcp)-1]) << 8
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
