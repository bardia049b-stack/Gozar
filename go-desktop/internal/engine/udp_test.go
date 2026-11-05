package engine

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warptest"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// The gaming/WebRTC acceptance test: UDP for the classic game and STUN ports
// must cross the tunnel and come back unchanged, through a real QUIC + MASQUE
// handshake against the mock WARP responder.
func TestGameAndWebRTCUdpPortsTraverseTheTunnel(t *testing.T) {
	server, err := warptest.StartUDPEcho()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	defer client.Close()
	dial := func(ctx context.Context) (PacketConn, error) { return client.DialH3(ctx) }
	clientStack, engine, _ := virtualNetworkWithPackets(t, &mockResolver{}, dial)
	destination := [4]byte{10, 111, 0, 3}
	for _, port := range []uint16{3074, 3478, 27015, 4500} {
		remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(destination), Port: port}
		conn, err := gonet.DialUDP(clientStack, nil, remote, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatalf("port %d dial: %v", port, err)
		}
		_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
		payload := make([]byte, 1200)
		if _, err := rand.Read(payload); err != nil {
			t.Fatal(err)
		}
		started := time.Now()
		if _, err := conn.Write(payload); err != nil {
			conn.Close()
			t.Fatalf("port %d write: %v", port, err)
		}
		answer := make([]byte, 65535)
		count, err := conn.Read(answer)
		conn.Close()
		if err != nil {
			t.Fatalf("port %d lost its answer: %v", port, err)
		}
		if !bytes.Equal(answer[:count], payload) {
			t.Fatalf("port %d payload changed", port)
		}
		if elapsed := time.Since(started); elapsed > 2*time.Second {
			t.Fatalf("port %d round trip too slow: %s", port, elapsed)
		}
		t.Logf("udp/%d round trip %s", port, time.Since(started))
	}
	down, up := engine.Counters()
	if down == 0 || up == 0 {
		t.Fatalf("UDP traffic was not metered: down=%d up=%d", down, up)
	}
}

// A dead datagram tunnel must recover by itself: the relay notices the broken
// connection and dials again on the next packet. This is the Wi-Fi drop /
// endpoint-down case for UDP.
func TestUDPRelayRedialsAfterTheEndpointDies(t *testing.T) {
	server, err := warptest.StartUDPEcho()
	if err != nil {
		t.Fatal(err)
	}
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	defer client.Close()

	var mu sync.Mutex
	current := client
	var dials int
	dial := func(ctx context.Context) (PacketConn, error) {
		mu.Lock()
		dials++
		target := current
		mu.Unlock()
		return target.DialH3(ctx)
	}
	clientStack, engine, _ := virtualNetworkWithPackets(t, &mockResolver{}, dial)
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 111, 0, 3}), Port: 3074}
	send := func() error {
		conn, err := gonet.DialUDP(clientStack, nil, remote, ipv4.ProtocolNumber)
		if err != nil {
			return err
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
		payload := []byte("resilience")
		if _, err := conn.Write(payload); err != nil {
			return err
		}
		answer := make([]byte, 2048)
		count, err := conn.Read(answer)
		if err != nil {
			return err
		}
		if !bytes.Equal(answer[:count], payload) {
			return errors.New("echo mismatch")
		}
		return nil
	}
	if err := send(); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	first := dials
	mu.Unlock()
	if first != 1 {
		t.Fatalf("expected one dial, got %d", first)
	}

	// The endpoint dies.
	server.Close()
	client.Close()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		engine.packets.mu.Lock()
		dead := engine.packets.conn == nil
		engine.packets.mu.Unlock()
		if dead {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	// A replacement endpoint comes up; the next packet must dial it.
	restarted, err := warptest.StartUDPEcho()
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	restartedClient := warp.NewClient(restarted.Identity)
	restartedClient.Port = restarted.Port
	defer restartedClient.Close()
	mu.Lock()
	current = restartedClient
	mu.Unlock()
	if err := send(); err != nil {
		t.Fatalf("the relay did not recover: %v", err)
	}
	mu.Lock()
	second := dials
	mu.Unlock()
	if second < 2 {
		t.Fatalf("the relay never re-dialled: %d dials", second)
	}
}

// UDP has no close signal, so an idle mapping must expire like a NAT entry.
// Without this a client that opens many short-lived sockets would keep every
// endpoint (and its goroutine) alive forever.
func TestIdleUDPFlowsExpire(t *testing.T) {
	previous := udpFlowIdle
	udpFlowIdle = 300 * time.Millisecond
	defer func() { udpFlowIdle = previous }()
	server, err := warptest.StartUDPEcho()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	defer client.Close()
	clientStack, engine, _ := virtualNetworkWithPackets(t, &mockResolver{}, func(ctx context.Context) (PacketConn, error) {
		return client.DialH3(ctx)
	})
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 111, 0, 3}), Port: 3478}
	for i := 0; i < 12; i++ {
		conn, err := gonet.DialUDP(clientStack, nil, remote, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := conn.Write([]byte("stun")); err != nil {
			conn.Close()
			t.Fatal(err)
		}
		answer := make([]byte, 64)
		if _, err := conn.Read(answer); err != nil {
			conn.Close()
			t.Fatal(err)
		}
		conn.Close()
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		engine.packets.mu.Lock()
		remaining := len(engine.packets.flows)
		engine.packets.mu.Unlock()
		if remaining == 0 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	engine.packets.mu.Lock()
	remaining := len(engine.packets.flows)
	engine.packets.mu.Unlock()
	t.Fatalf("%d idle UDP mappings never expired", remaining)
}
