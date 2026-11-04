package packet

import (
	"bytes"
	"net/netip"
	"testing"
)

func TestUDPFragmentReassembly(t *testing.T) {
	src := netip.MustParseAddr("10.111.0.2")
	dst := netip.MustParseAddr("10.111.0.3")
	for _, size := range []int{100, 500, 1180, 1400, 1500} {
		payload := bytes.Repeat([]byte{byte(size % 251)}, size)
		original := BuildUDP(src, dst, 40000, 3074, payload)
		if original == nil {
			t.Fatal("build failed")
		}
		fragments := Fragment(original, 1180)
		if len(original) > 1180 && len(fragments) < 2 {
			t.Fatalf("size %d was not fragmented", size)
		}
		reassembler := NewReassembler()
		var rebuilt []byte
		for _, fragment := range fragments {
			if len(fragment) > 1180 {
				t.Fatalf("fragment exceeds mtu: %d", len(fragment))
			}
			if packet, ok := reassembler.Add(fragment); ok {
				rebuilt = packet
			}
		}
		if rebuilt == nil {
			t.Fatalf("size %d was not reassembled", size)
		}
		_, _, _, _, got, err := ParseUDP(rebuilt)
		if err != nil || !bytes.Equal(got, payload) {
			t.Fatalf("size %d payload mismatch: %v", size, err)
		}
	}
}
