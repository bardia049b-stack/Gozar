package engine

import (
	"context"
	"encoding/binary"
	"io"
	"net/netip"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/dnsproxy"
	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

func TestTunnelDNSAddressIsInternalResolver(t *testing.T) {
	if dnsproxy.Address != "10.111.0.53" {
		t.Fatalf("tunnel DNS address = %s, want 10.111.0.53", dnsproxy.Address)
	}
}

func TestDNSQueriesToInternalResolverAreIntercepted(t *testing.T) {
	address := netip.MustParseAddr(dnsproxy.Address).As4()
	r := &mockResolver{}
	client, _, _ := virtualNetwork(t, r)

	udpRemote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(address), Port: 53}
	udp, err := gonet.DialUDP(client, nil, udpRemote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	_ = udp.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := udp.Write(packetQuery(t, 77)); err != nil {
		udp.Close()
		t.Fatal(err)
	}
	answer := make([]byte, 4096)
	n, err := udp.Read(answer)
	udp.Close()
	if err != nil {
		t.Fatal(err)
	}
	checkDNSReply(t, answer[:n], 77, dnsmessage.RCodeSuccess)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tcpRemote := tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(address), Port: 53}
	conn, err := gonet.DialContextTCP(ctx, client, tcpRemote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	query := packetQuery(t, 78)
	frame := make([]byte, len(query)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if _, err := conn.Write(frame); err != nil {
		t.Fatal(err)
	}
	size := make([]byte, 2)
	if _, err := io.ReadFull(conn, size); err != nil {
		t.Fatal(err)
	}
	tcpAnswer := make([]byte, binary.BigEndian.Uint16(size))
	if _, err := io.ReadFull(conn, tcpAnswer); err != nil {
		t.Fatal(err)
	}
	checkDNSReply(t, tcpAnswer, 78, dnsmessage.RCodeSuccess)
	if r.queries.Load() != 2 {
		t.Fatalf("expected two intercepted DNS queries, got %d", r.queries.Load())
	}
}
