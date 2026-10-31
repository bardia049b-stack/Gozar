package engine

import (
	"io"
	"net"
	"testing"
	"time"
)

func tcpPair(t *testing.T) (*net.TCPConn, *net.TCPConn) {
	t.Helper()
	listener, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	incoming := make(chan *net.TCPConn, 1)
	go func() { conn, _ := listener.AcceptTCP(); incoming <- conn }()
	first, err := net.DialTCP("tcp", nil, listener.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	second := <-incoming
	if second == nil {
		t.Fatal("accept failed")
	}
	_ = first.SetDeadline(time.Now().Add(4 * time.Second))
	_ = second.SetDeadline(time.Now().Add(4 * time.Second))
	return first, second
}

func TestRelayHalfClosePreservesResponse(t *testing.T) {
	client, local := tcpPair(t)
	remote, server := tcpPair(t)
	defer client.Close()
	defer server.Close()
	engine := &Engine{}
	done := make(chan struct{})
	go func() { engine.relayDuplex(local, remote); close(done) }()
	if _, err := client.Write([]byte("request")); err != nil {
		t.Fatal(err)
	}
	if err := client.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	request, err := io.ReadAll(server)
	if err != nil || string(request) != "request" {
		t.Fatalf("request=%q error=%v", request, err)
	}
	if _, err := server.Write([]byte("response")); err != nil {
		t.Fatal(err)
	}
	if err := server.CloseWrite(); err != nil {
		t.Fatal(err)
	}
	response, err := io.ReadAll(client)
	if err != nil || string(response) != "response" {
		t.Fatalf("response=%q error=%v", response, err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("relay did not finish")
	}
	down, up := engine.Counters()
	if down != 8 || up != 7 {
		t.Fatalf("down=%d up=%d", down, up)
	}
}

type partialWriter struct{ data []byte }

func (w *partialWriter) Write(p []byte) (int, error) {
	if len(p) > 2 {
		p = p[:2]
	}
	w.data = append(w.data, p...)
	return len(p), nil
}

func TestCopyMeteredHandlesShortWrites(t *testing.T) {
	src, dst := net.Pipe()
	defer src.Close()
	defer dst.Close()
	writer := &partialWriter{}
	done := make(chan error, 1)
	engine := &Engine{}
	go func() { done <- copyMetered(writer, dst, &engine.up) }()
	if _, err := src.Write([]byte("123456")); err != nil {
		t.Fatal(err)
	}
	src.Close()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if string(writer.data) != "123456" {
		t.Fatalf("lost data %q", writer.data)
	}
	_, up := engine.Counters()
	if up != 6 {
		t.Fatal(up)
	}
}
