package mtu

import (
	"encoding/binary"
	"testing"
)

func TestClampSYN(t *testing.T) {
	packet := make([]byte, 60)
	packet[0] = 0x45
	packet[9] = 6
	binary.BigEndian.PutUint16(packet[2:4], 60)
	copy(packet[12:16], []byte{10, 0, 0, 1})
	copy(packet[16:20], []byte{10, 0, 0, 2})
	tcp := packet[20:]
	tcp[13] = 0x02
	tcp[12] = 0x60
	tcp[20] = 2
	tcp[21] = 4
	binary.BigEndian.PutUint16(tcp[22:24], 1460)
	if !ClampSYN(packet, 1280) {
		t.Fatal("SYN MSS was not clamped")
	}
	if got := binary.BigEndian.Uint16(tcp[22:24]); got != 1240 {
		t.Fatalf("bad MSS: %d", got)
	}
	if ClampSYN(packet, 1280) {
		t.Fatal("already clamped SYN changed again")
	}
}

func TestClampBounds(t *testing.T) {
	if Clamp(100) != 1280 || Clamp(2000) != 1360 || Clamp(1300) != 1300 {
		t.Fatal("MTU clamp bounds failed")
	}
}
