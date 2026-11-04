package warp

import (
	"context"
	"net"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func TestDefaultScannerIncludesWarpPort(t *testing.T) {
	scanner := DefaultScanner()
	if len(scanner.Endpoints) == 0 || len(scanner.Ports) == 0 {
		t.Fatal("scanner has no endpoints or ports")
	}
	found := false
	for _, port := range scanner.Ports {
		if port == DefaultPort {
			found = true
		}
	}
	if !found {
		t.Fatalf("scanner does not include default WARP port: %+v", scanner.Ports)
	}
}

func TestScannerRanksReachableEndpoint(t *testing.T) {
	server := httptest.NewTLSServer(nil)
	defer server.Close()
	host, portText, err := net.SplitHostPort(server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	scanner := &Scanner{Endpoints: []string{host}, Ports: []int{port}, Timeout: 2 * time.Second, Workers: 1}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	results := scanner.Scan(ctx)
	if len(results) != 1 || !results[0].OK {
		t.Fatalf("reachable endpoint was not ranked OK: %+v", results)
	}
	best, ok := scanner.Best(ctx)
	if !ok || best != server.Listener.Addr().String() {
		t.Fatalf("bad best endpoint: %s %v", best, ok)
	}
}
