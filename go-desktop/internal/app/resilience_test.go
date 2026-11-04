package app

import (
	"context"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
)

// Three consecutive idle failures rebuild the session; the watcher then exits
// so the new session can start its own watcher.
func TestIdleWatchdogRebuildsAfterRepeatedFailures(t *testing.T) {
	var probes, rebuilds atomic.Int64
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	watcher := &pathWatcher{
		interval:  2 * time.Millisecond,
		limit:     3,
		active:    func() bool { return true },
		counters:  func() (int64, int64) { return 10, 20 },
		probe:     func() error { probes.Add(1); return io.ErrUnexpectedEOF },
		reconnect: func(context.Context) { rebuilds.Add(1) },
	}
	watcher.run(ctx)
	if probes.Load() != 3 {
		t.Fatalf("expected exactly three probes, got %d", probes.Load())
	}
	if rebuilds.Load() != 1 {
		t.Fatalf("expected one rebuild, got %d", rebuilds.Load())
	}
}

// While the user is actually transferring data the watchdog must stay silent:
// no extra requests, no extra bytes.
func TestMovingTrafficIsNeverProbed(t *testing.T) {
	var probes atomic.Int64
	var moved atomic.Int64
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancel()
	watcher := &pathWatcher{
		interval: 2 * time.Millisecond,
		limit:    3,
		active:   func() bool { return true },
		counters: func() (int64, int64) { return moved.Add(1024), 0 },
		probe:    func() error { probes.Add(1); return io.ErrUnexpectedEOF },
		reconnect: func(context.Context) {
			t.Error("the watchdog rebuilt a healthy, busy session")
		},
	}
	watcher.run(ctx)
	if probes.Load() != 0 {
		t.Fatalf("the watchdog probed a busy tunnel %d times", probes.Load())
	}
}

// A single dropped probe (a Wi-Fi blip) must not tear the tunnel down.
func TestTransientProbeFailureDoesNotReconnect(t *testing.T) {
	var calls atomic.Int64
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancel()
	watcher := &pathWatcher{
		interval: 2 * time.Millisecond,
		limit:    3,
		active:   func() bool { return true },
		counters: func() (int64, int64) { return 1, 1 },
		probe: func() error {
			if calls.Add(1)%3 == 0 {
				return nil
			}
			return io.ErrUnexpectedEOF
		},
		reconnect: func(context.Context) {
			t.Error("two failures in a row were treated as a dead path")
		},
	}
	watcher.run(ctx)
	if calls.Load() < 6 {
		t.Fatalf("the watcher stopped probing after %d calls", calls.Load())
	}
}

// Once the user disconnects, the watcher must stop immediately.
func TestWatchdogStopsWhenTheSessionEnds(t *testing.T) {
	var probes atomic.Int64
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	watcher := &pathWatcher{
		interval:  2 * time.Millisecond,
		active:    func() bool { return false },
		probe:     func() error { probes.Add(1); return nil },
		reconnect: func(context.Context) { t.Error("rebuilt a session the user closed") },
	}
	watcher.run(ctx)
	if probes.Load() != 0 {
		t.Fatal("the watcher probed after the session ended")
	}
}

// connectProxy is a real HTTP/2 TLS CONNECT proxy for the probe test.
func connectProxy(t *testing.T) (*httptest.Server, *atomic.Int64) {
	t.Helper()
	var hits atomic.Int64
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		hits.Add(1)
		if r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(http.StatusProxyAuthRequired)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		_, _ = io.CopyN(w, r.Body, 4)
		w.(http.Flusher).Flush()
	})
	server := httptest.NewUnstartedServer(handler)
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(server.Close)
	return server, &hits
}

// The idle probe must be a real CONNECT through the live proxy, and it must
// fail when that proxy dies — that failure is what triggers the rebuild.
func TestProbePathChecksTheLiveProxy(t *testing.T) {
	server, hits := connectProxy(t)
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	host, portText, err := net.SplitHostPort(server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	manager := tunnel.NewManager(tunnel.Target{Host: host, DialHost: host, Port: port})
	defer manager.Close()
	manager.SetProxyRoots(roots)
	app := &App{pass: &fxa.Pass{Token: "fake-pass"}, transport: manager}
	if err := app.probePath(); err != nil {
		t.Fatalf("the probe failed against a healthy proxy: %v", err)
	}
	if hits.Load() == 0 {
		t.Fatal("the probe never reached the proxy")
	}
	server.Close()
	manager.Close()
	if err := app.probePath(); err == nil {
		t.Fatal("the probe reported success after the proxy died")
	}
}

// The reconnect loop must respect an explicit user disconnect.
func TestRebuildStopsWhenTheUserDisconnected(t *testing.T) {
	app := &App{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		app.rebuildPath(ctx, nil)
	}()
	app.mu.Lock()
	app.reconnectWanted = false
	app.mu.Unlock()
	cancel()
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("the rebuild loop ignored cancellation")
	}
}
