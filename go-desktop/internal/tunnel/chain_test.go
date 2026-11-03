package tunnel

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestParseChainValidatesEveryHop(t *testing.T) {
	hops, err := ParseChain([]string{"socks5://proxy.example:1080", "http://127.0.0.1:8080", "[::1]:9999"})
	if err != nil || len(hops) != 3 || hops[0].Scheme != "socks5" || hops[1].Scheme != "http" || hops[2].Host != "::1" {
		t.Fatalf("unexpected chain %#v: %v", hops, err)
	}
	for _, value := range []string{
		"socks5://example", "https://example:443", "http://user:pass@example:80",
		"http://example:80/path", "socks5://example:0", "socks5://example:65536",
		"http://example:80?x=1", "ftp://example:21", "socks5://exa mple:80",
	} {
		if _, err := ParseChain([]string{value}); err == nil {
			t.Errorf("accepted invalid proxy %q", value)
		}
	}
	if _, err := ParseChain(make([]string, 9)); err == nil {
		t.Fatal("accepted more than eight proxy hops")
	}
}

func TestSocksConnectHandlesFragmentedReplyAndRemoteHostname(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	result := make(chan error, 1)
	go func() {
		var greeting [3]byte
		if _, err := io.ReadFull(server, greeting[:]); err != nil || greeting != [3]byte{5, 1, 0} {
			result <- fmt.Errorf("bad greeting: %v", err)
			return
		}
		server.Write([]byte{5})
		server.Write([]byte{0})
		var prefix [5]byte
		if _, err := io.ReadFull(server, prefix[:]); err != nil || prefix[3] != 3 {
			result <- fmt.Errorf("missing domain: %v", err)
			return
		}
		address := make([]byte, int(prefix[4])+2)
		if _, err := io.ReadFull(server, address); err != nil {
			result <- err
			return
		}
		if string(address[:len(address)-2]) != "second.example" || binary.BigEndian.Uint16(address[len(address)-2:]) != 8080 {
			result <- fmt.Errorf("unexpected SOCKS destination")
			return
		}
		for _, b := range []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 80} {
			if _, err := server.Write([]byte{b}); err != nil {
				result <- err
				return
			}
		}
		result <- nil
	}()
	if err := socksConnect(client, "second.example", 8080); err != nil {
		t.Fatal(err)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}

func TestHttpConnectPreservesFollowingBytes(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	result := make(chan error, 1)
	go func() {
		reader := bufio.NewReader(server)
		line, err := reader.ReadString('\n')
		if err != nil || line != "CONNECT fastly.example:2499 HTTP/1.1\r\n" {
			result <- fmt.Errorf("bad request %q: %v", line, err)
			return
		}
		for {
			line, err = reader.ReadString('\n')
			if err != nil {
				result <- err
				return
			}
			if line == "\r\n" {
				break
			}
		}
		_, err = server.Write([]byte("HTTP/1.1 200 Connection Established\r\nX-Test: yes\r\n\r\nNEXT"))
		result <- err
	}()
	if err := httpConnect(client, "fastly.example", 2499); err != nil {
		t.Fatal(err)
	}
	var after [4]byte
	if _, err := io.ReadFull(client, after[:]); err != nil || string(after[:]) != "NEXT" {
		t.Fatalf("proxy consumed bytes after the headers: %q %v", after, err)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
	if err := httpConnect(client, "unsafe\r\nHost: x", 443); err == nil {
		t.Fatal("accepted a header injection")
	}
}

func TestDialChainRunsTwoHopsWithoutAnyTunDevice(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	result := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			result <- err
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		var greeting [3]byte
		if _, err := io.ReadFull(conn, greeting[:]); err != nil {
			result <- err
			return
		}
		conn.Write([]byte{5, 0})
		var prefix [5]byte
		if _, err := io.ReadFull(conn, prefix[:]); err != nil || prefix[3] != 3 {
			result <- fmt.Errorf("bad SOCKS request: %v", err)
			return
		}
		address := make([]byte, int(prefix[4])+2)
		if _, err := io.ReadFull(conn, address); err != nil || string(address[:len(address)-2]) != "second.example" {
			result <- fmt.Errorf("wrong next hop: %v", err)
			return
		}
		conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
		reader := bufio.NewReader(conn)
		line, err := reader.ReadString('\n')
		if err != nil || !strings.Contains(line, "fastly.example:2499") {
			result <- fmt.Errorf("wrong final hop %q: %v", line, err)
			return
		}
		for {
			line, err = reader.ReadString('\n')
			if err != nil {
				result <- err
				return
			}
			if line == "\r\n" {
				break
			}
		}
		_, err = conn.Write([]byte("HTTP/1.1 200 OK\r\n\r\n"))
		result <- err
	}()
	target := Target{Host: "fastly.example", Port: 2499, DialHost: "127.0.0.1", Chain: []Hop{
		{Scheme: "socks5", Host: "first.example", Port: port},
		{Scheme: "http", Host: "second.example", Port: 8080},
	}}
	conn, err := dialChain(target, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}

func TestSocksProxyAuthenticationAndHttpFailureAreRejected(t *testing.T) {
	client, server := net.Pipe()
	go func() {
		defer server.Close()
		var greeting [3]byte
		io.ReadFull(server, greeting[:])
		server.Write([]byte{5, 2})
	}()
	if err := socksConnect(client, "server.example", 443); err == nil {
		t.Fatal("accepted unsupported SOCKS authentication")
	}
	client.Close()
	client, server = net.Pipe()
	go func() {
		defer server.Close()
		reader := bufio.NewReader(server)
		for {
			line, err := reader.ReadString('\n')
			if err != nil || line == "\r\n" {
				break
			}
		}
		server.Write([]byte("HTTP/1.1 407 Authentication Required\r\n\r\n"))
	}()
	if err := httpConnect(client, "server.example", 443); err == nil {
		t.Fatal("accepted an HTTP authentication challenge")
	}
	client.Close()
}
