package warp_test

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/packet"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warptest"
)

func TestMockWARPEchoH3(t *testing.T) {
	server, err := warptest.Start()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, err := client.DialH3(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	assertEcho(t, conn, []byte("Hello WARP"))
}

func TestMockWARPEchoH2(t *testing.T) {
	server, err := warptest.Start()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, err := client.DialH2(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	assertEcho(t, conn, []byte("Hello WARP H2"))
}

func TestMockWARPFragmentation(t *testing.T) {
	server, err := warptest.Start()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, err := client.Dial(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	src := netip.MustParseAddr("10.111.0.2")
	dst := netip.MustParseAddr("10.111.0.3")
	for _, size := range []int{100, 500, 1180, 1400, 1500} {
		payload := bytes.Repeat([]byte{byte(size % 251)}, size)
		original := packet.BuildUDP(src, dst, 40000, 3074, payload)
		for _, fragment := range packet.Fragment(original, warp.MTU) {
			if _, err := conn.WritePacket(fragment); err != nil {
				t.Fatal(err)
			}
		}
		reassembler := packet.NewReassembler()
		buf := make([]byte, 65535)
		deadline := time.Now().Add(5 * time.Second)
		var rebuilt []byte
		for time.Now().Before(deadline) {
			n, err := conn.ReadPacket(buf, true)
			if err != nil {
				t.Fatal(err)
			}
			if whole, ok := reassembler.Add(buf[:n]); ok {
				rebuilt = whole
				break
			}
		}
		if rebuilt == nil {
			t.Fatalf("size %d echo lost", size)
		}
		_, _, _, _, got, err := packet.ParseUDP(rebuilt)
		if err != nil || !bytes.Equal(got, payload) {
			t.Fatalf("size %d echo mismatch: %v", size, err)
		}
	}
}

func TestMockWARPFallbackToTCP(t *testing.T) {
	server, err := warptest.Start()
	if err != nil {
		t.Fatal(err)
	}
	client := warp.NewClient(server.Identity)
	client.Port = server.Port
	server.Close()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 1024)
		n, err := conn.Read(buf)
		if err == nil {
			conn.Write(buf[:n])
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	result, err := client.DialWithFallback(ctx, 1500*time.Millisecond, func(ctx context.Context) (net.Conn, error) {
		var dialer net.Dialer
		return dialer.DialContext(ctx, "tcp", listener.Addr().String())
	})
	if err != nil {
		t.Fatal(err)
	}
	defer result.Close()
	if result.TCP == nil {
		t.Fatal("did not fall back to TCP")
	}
	if _, err := result.TCP.Write([]byte("tcp fallback")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 64)
	result.TCP.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err := result.TCP.Read(buf)
	if err != nil || string(buf[:n]) != "tcp fallback" {
		t.Fatalf("bad tcp fallback echo: %q %v", buf[:n], err)
	}
}

func assertEcho(t *testing.T, conn *connectip.Conn, payload []byte) {
	t.Helper()
	src := netip.MustParseAddr("10.111.0.2")
	dst := netip.MustParseAddr("10.111.0.3")
	original := packet.BuildUDP(src, dst, 40000, 3074, payload)
	if _, err := conn.WritePacket(original); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 65535)
	done := make(chan error, 1)
	go func() {
		n, err := conn.ReadPacket(buf, true)
		if err != nil {
			done <- err
			return
		}
		_, _, _, _, got, err := packet.ParseUDP(buf[:n])
		if err != nil {
			done <- err
			return
		}
		if !bytes.Equal(got, payload) {
			done <- errEcho
			return
		}
		done <- nil
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("echo timeout")
	}
}

var errEcho = errors.New("echo mismatch")
