package advanced_test

import (
	"context"
	"crypto/tls"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advancedtest"
	"golang.org/x/net/dns/dnsmessage"
)

func doqQuery(t *testing.T, id uint16) []byte {
	t.Helper()
	name, err := dnsmessage.NewName("example.org.")
	if err != nil {
		t.Fatal(err)
	}
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}
	wire, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return wire
}

func TestDoQRealHandshakeAndAnswer(t *testing.T) {
	cert, roots := certificate(t, "resolver.example")
	server, err := advancedtest.StartDoQ(&tls.Config{Certificates: []tls.Certificate{cert}}, func(query []byte) ([]byte, error) {
		var message dnsmessage.Message
		if err := message.Unpack(query); err != nil {
			return nil, err
		}
		answer := dnsmessage.Message{
			Header:    dnsmessage.Header{Response: true, RecursionAvailable: true},
			Questions: message.Questions,
			Answers: []dnsmessage.Resource{{
				Header: dnsmessage.ResourceHeader{Name: message.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
				Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 53}},
			}},
		}
		return answer.Pack()
	})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client := advanced.NewDoQClient(server.Addr, &tls.Config{RootCAs: roots, ServerName: "resolver.example"})
	client.Timeout = 6 * time.Second
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	query := doqQuery(t, 0xABCD)
	answer, err := client.Resolve(ctx, query)
	if err != nil {
		t.Fatal(err)
	}
	var message dnsmessage.Message
	if err := message.Unpack(answer); err != nil {
		t.Fatal(err)
	}
	if message.ID != 0xABCD {
		t.Fatalf("the original message ID was not restored: %04x", message.ID)
	}
	if !message.Response || len(message.Answers) != 1 {
		t.Fatalf("bad DoQ answer: %+v", message.Header)
	}
	a, ok := message.Answers[0].Body.(*dnsmessage.AResource)
	if !ok || a.A != [4]byte{192, 0, 2, 53} {
		t.Fatalf("bad answer body: %+v", message.Answers[0].Body)
	}
	// A second query reuses the QUIC connection.
	if _, err := client.Resolve(ctx, doqQuery(t, 0x1111)); err != nil {
		t.Fatal(err)
	}
	if server.Queries() != 2 {
		t.Fatalf("server saw %d queries, want 2", server.Queries())
	}
}

func TestDoQFailureIsReportedSoDoHCanTakeOver(t *testing.T) {
	// Nothing listens on this port: DoQ must fail fast, not hang.
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	_, err := advanced.DoQResolve(ctx, "127.0.0.1:1", doqQuery(t, 1), nil, 1500*time.Millisecond)
	if err == nil {
		t.Fatal("DoQ reported success against a closed port")
	}
}
