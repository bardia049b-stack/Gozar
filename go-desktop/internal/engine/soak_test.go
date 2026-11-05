package engine

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/x509"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"runtime"
	"runtime/pprof"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warptest"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// soakProxy is a real HTTP/2 CONNECT proxy speaking a framed echo protocol, so
// one connection can carry many round trips the way keep-alive HTTP does.
func soakProxy(t *testing.T) (*httptest.Server, *atomic.Int64) {
	t.Helper()
	var served atomic.Int64
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		var header [4]byte
		for {
			if _, err := io.ReadFull(r.Body, header[:]); err != nil {
				return
			}
			size := int64(binary.BigEndian.Uint32(header[:]))
			if size <= 0 || size > 1<<20 {
				return
			}
			if _, err := io.CopyN(w, r.Body, size); err != nil {
				return
			}
			w.(http.Flusher).Flush()
			served.Add(1)
		}
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(server.Close)
	return server, &served
}

func soakTarget(t *testing.T, server *httptest.Server) tunnel.Target {
	t.Helper()
	host, portText, err := net.SplitHostPort(server.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	return tunnel.Target{Host: host, DialHost: host, Port: port}
}

// TestSoak is the long-running stability gate. It is skipped unless
// GOZAR_SOAK_MINUTES is set, so a normal test run stays fast.
//
//	GOZAR_SOAK_MINUTES=15 go test ./internal/engine -run TestSoak -timeout 30m
//
// It keeps a real TCP download/upload workload and a UDP game flow alive
// through the userspace stack, kills the primary proxy every minute to exercise
// failover, and watches goroutines, heap and CPU. It fails on a leak, on a
// growing heap, or on a success rate below 99%.
func TestSoak(t *testing.T) {
	minutesText := os.Getenv("GOZAR_SOAK_MINUTES")
	if minutesText == "" {
		t.Skip("set GOZAR_SOAK_MINUTES to run the stability soak")
	}
	minutes, err := strconv.Atoi(minutesText)
	if err != nil || minutes <= 0 {
		t.Fatalf("GOZAR_SOAK_MINUTES=%q is not a positive number", minutesText)
	}
	duration := time.Duration(minutes) * time.Minute

	primary, served := soakProxy(t)
	backup, _ := soakProxy(t)
	roots := x509.NewCertPool()
	roots.AddCert(primary.Certificate())
	roots.AddCert(backup.Certificate())
	manager := tunnel.NewManager(soakTarget(t, primary))
	defer manager.Close()
	manager.SetProxyRoots(roots)
	manager.SetBackups([]tunnel.Target{soakTarget(t, backup)})

	warpServer, err := warptest.StartUDPEcho()
	if err != nil {
		t.Fatal(err)
	}
	defer warpServer.Close()
	warpClient := warp.NewClient(warpServer.Identity)
	warpClient.Port = warpServer.Port
	defer warpClient.Close()

	clientStack, engine, _ := virtualNetworkWithPackets(t, &mockResolver{}, func(ctx context.Context) (PacketConn, error) {
		return warpClient.DialH3(ctx)
	})
	// The shared helper installs a stub TCP dial; the soak needs the real one.
	engine.dial = func(host string, port int) (io.ReadWriteCloser, error) {
		return manager.Open(host, port, "soak-pass", 15*time.Second)
	}

	deadline := time.Now().Add(duration)
	var attempts, failures, udpOK, udpBad atomic.Int64
	var stats runtime.MemStats
	started := time.Now()
	baseGoroutines := runtime.NumGoroutine()
	runtime.ReadMemStats(&stats)
	baseHeap := stats.HeapAlloc
	startCPU := soakCPU(t)
	var floors []uint64

	// The UDP flow runs beside the TCP workload for the whole soak, on one
	// socket, exactly like a game client.
	udpDone := make(chan struct{})
	go func() {
		defer close(udpDone)
		remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 111, 0, 3}), Port: 3074}
		payload := make([]byte, 1100)
		_, _ = rand.Read(payload)
		var conn *gonet.UDPConn
		defer func() {
			if conn != nil {
				conn.Close()
			}
		}()
		for time.Now().Before(deadline) {
			if conn == nil {
				fresh, dialErr := gonet.DialUDP(clientStack, nil, remote, ipv4.ProtocolNumber)
				if dialErr != nil {
					udpBad.Add(1)
					time.Sleep(200 * time.Millisecond)
					continue
				}
				conn = fresh
			}
			_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
			_, writeErr := conn.Write(payload)
			answer := make([]byte, 2048)
			count, readErr := conn.Read(answer)
			if writeErr != nil || readErr != nil || !bytes.Equal(answer[:count], payload) {
				udpBad.Add(1)
				conn.Close()
				conn = nil
			} else {
				udpOK.Add(1)
			}
			time.Sleep(250 * time.Millisecond)
		}
	}()

	restarts := 0
	nextRestart := time.Now().Add(time.Minute)
	nextSample := time.Now().Add(20 * time.Second)
	iteration := 0
	var stream *gonet.TCPConn
	defer func() {
		if stream != nil {
			stream.Close()
		}
	}()
	payload := make([]byte, 16*1024)
	if _, err := rand.Read(payload); err != nil {
		t.Fatal(err)
	}
	answer := make([]byte, len(payload))
	var header [4]byte
	binary.BigEndian.PutUint32(header[:], uint32(len(payload)))

	for time.Now().Before(deadline) {
		iteration++
		attempts.Add(1)
		// Keep-alive: one connection carries many round trips, and every 200
		// iterations a fresh connection is opened, so both paths are exercised.
		if stream == nil || iteration%200 == 0 {
			if stream != nil {
				stream.Close()
				stream = nil
			}
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			remote := tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 111, 0, 3}), Port: 80}
			fresh, dialErr := gonet.DialContextTCP(ctx, clientStack, remote, ipv4.ProtocolNumber)
			cancel()
			if dialErr != nil {
				failures.Add(1)
				time.Sleep(100 * time.Millisecond)
				continue
			}
			stream = fresh
		}
		_ = stream.SetDeadline(time.Now().Add(20 * time.Second))
		if _, err := stream.Write(header[:]); err != nil {
			failures.Add(1)
			stream.Close()
			stream = nil
			continue
		}
		if _, err := stream.Write(payload); err != nil {
			failures.Add(1)
			stream.Close()
			stream = nil
			continue
		}
		if _, err := io.ReadFull(stream, answer); err != nil {
			failures.Add(1)
			if failures.Load() < 5 {
				t.Logf("iteration %d failed: %v", iteration, err)
			}
			stream.Close()
			stream = nil
			continue
		}
		if !bytes.Equal(answer, payload) {
			failures.Add(1)
			t.Log("a payload changed in transit")
			stream.Close()
			stream = nil
			continue
		}
		if time.Now().After(nextRestart) && time.Until(deadline) > 45*time.Second {
			// A real outage kills live connections and stops accepting.
			// httptest.Server.Close would instead wait for the keep-alive
			// CONNECT handler to finish, which deadlocks the soak.
			primary.CloseClientConnections()
			primary.Listener.Close()
			restarts++
			nextRestart = time.Now().Add(time.Minute)
			t.Logf("proxy outage #%d injected after %s", restarts, time.Since(started).Round(time.Second))
		}
		if time.Now().After(nextSample) {
			// A forced GC gives the live-heap floor, which is what a leak
			// shows up in; the raw HeapAlloc sawtooth does not.
			runtime.GC()
			runtime.ReadMemStats(&stats)
			floors = append(floors, stats.HeapAlloc)
			t.Logf("%s floor=%.1fMiB goroutines=%d ok=%d fail=%d udp=%d/%d served=%d",
				time.Since(started).Round(time.Second), float64(stats.HeapAlloc)/(1<<20), runtime.NumGoroutine(),
				attempts.Load()-failures.Load(), failures.Load(), udpOK.Load(), udpBad.Load(), served.Load())
			nextSample = time.Now().Add(20 * time.Second)
		}
		time.Sleep(20 * time.Millisecond)
	}
	<-udpDone
	if stream != nil {
		stream.Close()
		stream = nil
	}

	// Let in-flight work settle before measuring leaks.
	time.Sleep(3 * time.Second)
	runtime.GC()
	runtime.ReadMemStats(&stats)
	if path := os.Getenv("GOZAR_SOAK_PROFILE"); path != "" {
		if file, createErr := os.Create(path); createErr == nil {
			_ = pprof.WriteHeapProfile(file)
			file.Close()
			t.Logf("heap profile written to %s", path)
		}
	}
	goroutines := runtime.NumGoroutine()
	heap := stats.HeapAlloc
	cpu := soakCPU(t) - startCPU
	elapsed := time.Since(started)
	down, up := engine.Counters()

	t.Logf("soak finished: duration=%s tcp=%d/%d udp=%d/%d restarts=%d down=%d up=%d heap %.1f→%.1fMiB goroutines %d→%d cpu=%.1fs (%.1f%% of one core)",
		elapsed.Round(time.Second), attempts.Load()-failures.Load(), attempts.Load(), udpOK.Load(), udpBad.Load(),
		restarts, down, up, float64(baseHeap)/(1<<20), float64(heap)/(1<<20), baseGoroutines, goroutines,
		cpu, 100*cpu/elapsed.Seconds())

	if attempts.Load() == 0 {
		t.Fatal("the soak never ran a round trip")
	}
	success := float64(attempts.Load()-failures.Load()) / float64(attempts.Load())
	if success < 0.99 {
		t.Fatalf("TCP success rate %.3f is below 0.99 (%d failures)", success, failures.Load())
	}
	if udpOK.Load() == 0 {
		t.Fatal("no UDP packet survived the soak")
	}
	udpSuccess := float64(udpOK.Load()) / float64(udpOK.Load()+udpBad.Load())
	if udpSuccess < 0.98 {
		t.Fatalf("UDP success rate %.3f is below 0.98", udpSuccess)
	}
	if goroutines > baseGoroutines+60 {
		buffer := make([]byte, 1<<20)
		size := runtime.Stack(buffer, true)
		t.Logf("goroutine dump:\n%s", buffer[:size])
		t.Fatalf("goroutines leaked: %d → %d", baseGoroutines, goroutines)
	}
	// A leak shows up as a floor that keeps climbing. Compare the last floor
	// with the first one taken after warm-up; a plateau is healthy.
	if len(floors) >= 3 {
		first := floors[1]
		last := floors[len(floors)-1]
		if last > first*2+64<<20 {
			t.Fatalf("live heap kept growing: %.1fMiB → %.1fMiB", float64(first)/(1<<20), float64(last)/(1<<20))
		}
	} else if heap > baseHeap*4+128<<20 {
		t.Fatalf("live heap grew from %d to %d bytes", baseHeap, heap)
	}
	if down == 0 || up == 0 {
		t.Fatal("the tunnel moved no bytes")
	}
	if restarts == 0 && minutes > 4 {
		t.Log("note: no proxy outage was injected because the soak was short")
	}
}

// soakCPU reports the process CPU seconds. It reads /proc where available and
// returns 0 elsewhere, so the test still compiles on Windows and macOS.
func soakCPU(t *testing.T) float64 {
	t.Helper()
	raw, err := os.ReadFile("/proc/self/stat")
	if err != nil {
		return 0
	}
	text := string(raw)
	closing := strings.LastIndex(text, ")")
	if closing < 0 || closing+2 >= len(text) {
		return 0
	}
	fields := strings.Fields(text[closing+2:])
	if len(fields) < 13 {
		return 0
	}
	utime, err1 := strconv.ParseFloat(fields[11], 64)
	stime, err2 := strconv.ParseFloat(fields[12], 64)
	if err1 != nil || err2 != nil {
		return 0
	}
	return (utime + stime) / 100
}
