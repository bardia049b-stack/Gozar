package engine

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"
)

type halfClosePipe struct{ net.Conn }

func (c halfClosePipe) CloseWrite() error { return c.Conn.Close() }

// A 10 MB application download must not be duplicated by the relay. The
// acceptance budget is 12 MB total measured application bytes.
func TestTenMegabyteDownloadStaysWithinBudget(t *testing.T) {
	e := &Engine{}
	localClient, localEnd := net.Pipe()
	remoteEnd, remoteServer := net.Pipe()
	done := make(chan struct{})
	go func() {
		e.relayDuplex(halfClosePipe{localEnd}, halfClosePipe{remoteEnd})
		close(done)
	}()
	payload := bytes.Repeat([]byte{0x5a}, 10<<20)
	go func() {
		_, _ = remoteServer.Write(payload)
		_ = remoteServer.Close()
	}()
	got := make([]byte, len(payload))
	_ = localClient.SetReadDeadline(time.Now().Add(30 * time.Second))
	if _, err := io.ReadFull(localClient, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("download payload changed")
	}
	_ = localClient.Close()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("relay did not finish")
	}
	down, up := e.Counters()
	if down != int64(len(payload)) {
		t.Fatalf("measured download = %d, want exactly %d", down, len(payload))
	}
	if up != 0 {
		t.Fatalf("unexpected upload bytes during download-only test: %d", up)
	}
	if down+up > 12<<20 {
		t.Fatalf("10 MB download exceeded 12 MB budget: %d", down+up)
	}
}
