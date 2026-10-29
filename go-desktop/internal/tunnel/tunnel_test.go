package tunnel

import (
	"io"
	"net"
	"net/http"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

func TestHTTP2WithInMemoryPeer(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	result := make(chan error, 1)
	go func() {
		handler := http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
			if request.Method != http.MethodConnect || request.Host != "example.org:443" ||
				request.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
				response.WriteHeader(http.StatusBadRequest)
				return
			}
			response.WriteHeader(http.StatusOK)
			response.(http.Flusher).Flush()
			buffer := make([]byte, 16)
			n, err := request.Body.Read(buffer)
			if err != nil {
				result <- err
				return
			}
			_, err = response.Write(buffer[:n])
			response.(http.Flusher).Flush()
			result <- err
		})
		(&http2.Server{}).ServeConn(server, &http2.ServeConnOpts{Handler: handler})
	}()
	stream, err := connectH2(client, "example.org", 443, "fake-pass", 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	if _, err := stream.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 5)
	if _, err := io.ReadFull(stream, data); err != nil {
		t.Fatal(err)
	}
	if string(data) != "hello" {
		t.Fatalf("unexpected response %q", data)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}
