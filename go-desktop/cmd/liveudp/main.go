package main

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

func dnsQuery(name string) []byte {
	q := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	for _, label := range strings.Split(name, ".") {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	return append(q, 0x00, 0x00, 0x01, 0x00, 0x01)
}

func fail(stage string, err error) {
	fmt.Println(stage+":", err)
	os.Exit(1)
}

func main() {
	dir, _ := os.MkdirTemp("", "warp-live-")
	defer os.RemoveAll(dir)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	fmt.Println("[1] registering a WARP device against the production API")
	device, err := warp.DeviceFor(ctx, filepath.Join(dir, "warp.json"))
	if err != nil {
		fail("REGISTRATION_FAILED", err)
	}
	fmt.Println("    device:", device.ID, "endpoint:", device.Endpoint, "ipv4:", device.IPv4)

	fmt.Println("[2] opening the connect-ip tunnel over direct QUIC")
	carrier, err := warp.Listen(device.Endpoint)
	if err != nil {
		fail("CARRIER_FAILED", err)
	}
	defer carrier.Close()
	stack, err := warp.OpenStack(ctx, device, warp.Carrier{Packet: carrier}, warp.DefaultMTU)
	if err != nil {
		fail("TUNNEL_FAILED", err)
	}
	defer stack.Close()
	fmt.Println("    tunnel up, prefixes:", stack.Prefixes())
	if !stack.Alive() {
		fail("TUNNEL_FAILED", fmt.Errorf("the stack reports itself dead"))
	}

	fmt.Println("[3] UDP 53 through WARP")
	flow, err := stack.Datagram("1.1.1.1", 53)
	if err != nil {
		fail("DNS_DIAL_FAILED", err)
	}
	start := time.Now()
	if err := flow.Send(dnsQuery("example.com")); err != nil {
		fail("DNS_SEND_FAILED", err)
	}
	rctx, rcancel := context.WithTimeout(context.Background(), 10*time.Second)
	answer, err := flow.Receive(rctx)
	rcancel()
	flow.Close()
	if err != nil {
		fail("DNS_RECEIVE_FAILED", err)
	}
	if len(answer) < 12 || binary.BigEndian.Uint16(answer[6:8]) == 0 {
		fail("DNS_EMPTY_ANSWER", fmt.Errorf("%d bytes", len(answer)))
	}
	fmt.Printf("    answered %d bytes, %d answers, rtt %s\n",
		len(answer), binary.BigEndian.Uint16(answer[6:8]), time.Since(start).Round(time.Millisecond))

	fmt.Println("[4] UDP 3074, a game port the Mozilla relay refuses")
	game, err := stack.Datagram("1.1.1.1", 3074)
	if err != nil {
		fail("GAME_DIAL_FAILED", err)
	}
	err = game.Send([]byte("gozar-game-probe"))
	game.Close()
	if err != nil {
		fail("GAME_SEND_FAILED", err)
	}
	fmt.Println("    datagram sent on UDP 3074")

	fmt.Println("[5] UDP 443 through WARP, as Game Mode routes it")
	web, err := stack.Datagram("1.1.1.1", 443)
	if err != nil {
		fail("WEB_DIAL_FAILED", err)
	}
	err = web.Send([]byte{0x00, 0x01, 0x02})
	web.Close()
	if err != nil {
		fail("WEB_SEND_FAILED", err)
	}
	fmt.Println("    datagram sent on UDP 443")

	fmt.Println("[6] TCP over the same stack, reading the real egress address")
	conn, err := stack.DialConn("1.1.1.1", 443)
	if err != nil {
		fail("TCP_DIAL_FAILED", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	secure := tls.Client(conn, &tls.Config{ServerName: "one.one.one.one", MinVersion: tls.VersionTLS12})
	if err := secure.Handshake(); err != nil {
		fail("TLS_FAILED", err)
	}
	fmt.Fprint(secure, "GET /cdn-cgi/trace HTTP/1.1\r\nHost: one.one.one.one\r\nConnection: close\r\n\r\n")
	buffer := make([]byte, 4096)
	count, _ := secure.Read(buffer)
	sawWarp := false
	for _, line := range strings.Split(string(buffer[:count]), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "ip=") || strings.HasPrefix(line, "warp=") || strings.HasPrefix(line, "colo=") {
			fmt.Println("   ", line)
		}
		if line == "warp=on" || line == "warp=plus" {
			sawWarp = true
		}
	}
	if !sawWarp {
		fail("EGRESS_NOT_WARP", fmt.Errorf("Cloudflare did not report warp=on"))
	}
	fmt.Println("LIVE_UDP_OK")
}
