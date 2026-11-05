package warp

import (
	"context"
	"encoding/binary"
	"net"
	"os"
	"strconv"
	"testing"
	"time"
)

func liveWarp(t *testing.T) *Client {
	t.Helper()
	if os.Getenv("GOZAR_LIVE_WARP") != "1" {
		t.Skip("set GOZAR_LIVE_WARP=1 to test against the real Cloudflare endpoint")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	identity, err := Register(ctx, nil)
	if err != nil {
		t.Fatalf("WARP registration against the real API failed: %v", err)
	}
	if identity.ID == "" || identity.PrivateKey == "" || identity.PeerKey == "" {
		t.Fatalf("the registered identity is incomplete: %+v", identity)
	}
	client := NewClient(identity)
	endpoint := os.Getenv("GOZAR_WARP_ENDPOINT")
	if endpoint == "" {
		endpoint = "162.159.198.2:443"
	}
	host, port, err := ParseEndpoint(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	client.Endpoint = host
	if port > 0 {
		client.Port = port
	}
	t.Cleanup(func() { client.Close() })
	t.Logf("registered WARP device %s, endpoint %s:%d, inner v4 %s", identity.ID, host, port, identity.IPv4)
	return client
}

func dnsQuery(name string) []byte {
	message := make([]byte, 0, 64)
	message = append(message, 0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
	for _, label := range splitLabels(name) {
		message = append(message, byte(len(label)))
		message = append(message, label...)
	}
	message = append(message, 0x00, 0x00, 0x01, 0x00, 0x01)
	return message
}

func splitLabels(name string) []string {
	var out []string
	start := 0
	for index := 0; index <= len(name); index++ {
		if index == len(name) || name[index] == '.' {
			if index > start {
				out = append(out, name[start:index])
			}
			start = index + 1
		}
	}
	return out
}

func TestLiveWarpCarriesUDPAgainstCloudflare(t *testing.T) {
	client := liveWarp(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, err := client.Dial(ctx)
	if err != nil {
		t.Fatalf("the MASQUE tunnel to the real endpoint failed: %v", err)
	}
	defer conn.Close()

	query := dnsQuery("cloudflare.com")
	packet := make([]byte, 0, 1500)
	packet = append(packet, 0x45, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00)
	packet = append(packet, 172, 16, 0, 2)
	packet = append(packet, 1, 1, 1, 1)
	udpLength := 8 + len(query)
	total := 20 + udpLength
	binary.BigEndian.PutUint16(packet[2:4], uint16(total))
	packet = append(packet, 0x30, 0x39, 0x00, 0x35)
	packet = append(packet, byte(udpLength>>8), byte(udpLength), 0x00, 0x00)
	packet = append(packet, query...)

	if icmp, err := conn.WritePacket(packet); err != nil {
		t.Fatalf("the real endpoint refused the datagram: %v", err)
	} else if len(icmp) > 0 {
		t.Fatalf("the real endpoint returned ICMP for the datagram: %x", icmp)
	}

	answer := make([]byte, 2048)
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		n, err := conn.ReadPacket(answer, false)
		if err != nil {
			t.Fatalf("reading the reply from the real endpoint failed: %v", err)
		}
		if n < 28 {
			continue
		}
		if answer[0]>>4 != 4 || answer[9] != 17 {
			continue
		}
		headerLength := int(answer[0]&0x0f) * 4
		if n < headerLength+8 {
			continue
		}
		payload := answer[headerLength+8 : n]
		if len(payload) < 12 || payload[0] != 0x12 || payload[1] != 0x34 {
			continue
		}
		answers := int(binary.BigEndian.Uint16(payload[6:8]))
		if answers == 0 {
			t.Fatalf("the resolver answered with zero records: %x", payload)
		}
		t.Logf("real WARP round trip succeeded: %d byte DNS answer, %d records", len(payload), answers)
		return
	}
	t.Fatal("no datagram came back from the real Cloudflare endpoint within 20s")
}

func TestLiveWarpEndpointReachable(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_WARP") != "1" {
		t.Skip("set GOZAR_LIVE_WARP=1 to probe the real Cloudflare endpoint")
	}
	endpoint := os.Getenv("GOZAR_WARP_ENDPOINT")
	if endpoint == "" {
		endpoint = "162.159.198.2:443"
	}
	host, port, err := ParseEndpoint(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	conn, err := net.DialTimeout("udp", net.JoinHostPort(host, strconv.Itoa(port)), 5*time.Second)
	if err != nil {
		t.Fatalf("the endpoint is not reachable: %v", err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write([]byte{0x00}); err != nil {
		t.Fatalf("the endpoint refused a UDP write: %v", err)
	}
	t.Logf("UDP socket to %s:%d opened in %s", host, port, time.Since(start))
}
