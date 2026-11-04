package warp_test

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warptest"
)

func TestScannerRealWARPHandshakeAndBestEndpoint(t *testing.T) {
	server, err := warptest.Start()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	scanner := &warp.Scanner{
		Identity:  server.Identity,
		Endpoints: []string{"127.0.0.1"},
		Ports:     []int{server.Port, 1}, // port 1 is expected to fail
		Timeout:   4 * time.Second,
		Workers:   2,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	results := scanner.Scan(ctx)
	if len(results) != 2 {
		t.Fatalf("expected two probes, got %+v", results)
	}
	if !results[0].OK || results[0].Endpoint != server.Addr {
		t.Fatalf("real WARP handshake did not rank first: %+v", results)
	}
	if results[0].Protocol == "" {
		t.Fatal("successful probe did not record h3/h2 protocol")
	}
	best, ok := scanner.Best(ctx)
	if !ok || best != server.Addr {
		t.Fatalf("best endpoint = %q %v, want %q", best, ok, server.Addr)
	}
}

func TestScannerPersistsWarpEndpointsJSON(t *testing.T) {
	server, err := warptest.Start()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	scanner := &warp.Scanner{Identity: server.Identity, Endpoints: []string{"127.0.0.1"}, Ports: []int{server.Port}, Timeout: 4 * time.Second, Workers: 1}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	path := filepath.Join(t.TempDir(), "warp_endpoints.json")
	report, err := scanner.ScanAndSave(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	if report.Best != server.Addr || len(report.Results) != 1 || !report.Results[0].OK {
		t.Fatalf("bad persisted report: %+v", report)
	}
	loaded, err := warp.LoadResults(path)
	if err != nil || loaded.Best != server.Addr {
		t.Fatalf("reload failed: %+v %v", loaded, err)
	}
}

func TestBestOfPrefersLowestLatencySuccessfulProbe(t *testing.T) {
	results := []warp.EndpointResult{
		{Endpoint: "192.0.2.2:443", OK: true, Latency: 90 * time.Millisecond},
		{Endpoint: "192.0.2.1:443", OK: true, Latency: 20 * time.Millisecond},
		{Endpoint: "192.0.2.3:443", OK: false, Latency: time.Millisecond},
	}
	best, ok := warp.BestOfForTest(results)
	if !ok || best != "192.0.2.1:443" {
		t.Fatalf("best = %q %v", best, ok)
	}
}
