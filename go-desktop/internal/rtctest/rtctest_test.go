package rtctest

import (
	"bytes"
	"crypto/rand"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestSTUNBindingSuccess(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go ServeBinding(server)
	client, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	mapped, err := ExchangeBinding(client, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	local := client.LocalAddr().(*net.UDPAddr)
	if mapped.Addr() != netip.MustParseAddr("127.0.0.1") || int(mapped.Port()) != local.Port {
		t.Fatalf("unexpected mapped address %s", mapped)
	}
}

func TestICEUDPZeroLossAndLatency(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := server.ReadFromUDP(buf)
			if err != nil {
				return
			}
			server.WriteToUDP(buf[:n], addr)
		}
	}()
	client, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	payloads := [][]byte{
		bytes.Repeat([]byte{0x01}, 160),
		bytes.Repeat([]byte{0x02}, 1200),
	}
	var worst time.Duration
	for i := 0; i < 100; i++ {
		payload := payloads[i%len(payloads)]
		packet := make([]byte, 12+len(payload))
		packet[0] = 0x80
		packet[1] = byte(96 + i%2)
		binaryPutUint16(packet[2:4], uint16(i))
		binaryPutUint32(packet[4:8], uint32(i*960))
		binaryPutUint32(packet[8:12], uint32(i+1))
		copy(packet[12:], payload)
		started := time.Now()
		if _, err := client.Write(packet); err != nil {
			t.Fatal(err)
		}
		client.SetReadDeadline(time.Now().Add(time.Second))
		reply := make([]byte, 2048)
		n, err := client.Read(reply)
		if err != nil {
			t.Fatalf("packet %d lost: %v", i, err)
		}
		if !bytes.Equal(reply[:n], packet) {
			t.Fatalf("packet %d corrupted", i)
		}
		if elapsed := time.Since(started); elapsed > worst {
			worst = elapsed
		}
	}
	if worst >= 100*time.Millisecond {
		t.Fatalf("ICE latency too high: %s", worst)
	}
}

func TestGamingUDPPorts(t *testing.T) {
	for _, port := range []int{3074, 3478, 3479, 27015} {
		server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: port})
		if err != nil {
			t.Fatal(err)
		}
		go func(conn *net.UDPConn) {
			defer conn.Close()
			buf := make([]byte, 2048)
			n, addr, err := conn.ReadFromUDP(buf)
			if err == nil {
				conn.WriteToUDP(buf[:n], addr)
			}
		}(server)
		client, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
		if err != nil {
			t.Fatal(err)
		}
		payload := make([]byte, 128)
		rand.Read(payload)
		if _, err := client.Write(payload); err != nil {
			t.Fatal(err)
		}
		client.SetReadDeadline(time.Now().Add(2 * time.Second))
		reply := make([]byte, 2048)
		n, err := client.Read(reply)
		if err != nil || !bytes.Equal(reply[:n], payload) {
			t.Fatalf("gaming port %d failed: %v", port, err)
		}
		client.Close()
		server.Close()
	}
}

func TestNATTypeOpen(t *testing.T) {
	servers := make([]*net.UDPConn, 0, 2)
	for i := 0; i < 2; i++ {
		server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
		if err != nil {
			t.Fatal(err)
		}
		defer server.Close()
		go ServeBinding(server)
		servers = append(servers, server)
	}
	client, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	var first netip.AddrPort
	for i, server := range servers {
		if _, err := client.WriteToUDP(mustBindingRequest(t), server.LocalAddr().(*net.UDPAddr)); err != nil {
			t.Fatal(err)
		}
		client.SetReadDeadline(time.Now().Add(2 * time.Second))
		buf := make([]byte, 1500)
		n, _, err := client.ReadFromUDP(buf)
		if err != nil {
			t.Fatal(err)
		}
		tx := [12]byte{}
		copy(tx[:], buf[8:20])
		mapped, err := ParseBindingSuccess(buf[:n], tx)
		if err != nil {
			t.Fatal(err)
		}
		if i == 0 {
			first = mapped
		} else if mapped != first {
			t.Fatalf("NAT is not open: %s != %s", mapped, first)
		}
	}
}

func mustBindingRequest(t *testing.T) []byte {
	t.Helper()
	request, _, err := NewBindingRequest()
	if err != nil {
		t.Fatal(err)
	}
	return request
}

func binaryPutUint16(b []byte, v uint16) {
	b[0] = byte(v >> 8)
	b[1] = byte(v)
}

func binaryPutUint32(b []byte, v uint32) {
	b[0] = byte(v >> 24)
	b[1] = byte(v >> 16)
	b[2] = byte(v >> 8)
	b[3] = byte(v)
}
