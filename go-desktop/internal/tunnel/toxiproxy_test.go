package tunnel

import (
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/bypass"
)

func toxiproxyAPI(t *testing.T) string {
	t.Helper()
	api := os.Getenv("GOZAR_TOXIPROXY_API")
	if api == "" {
		t.Skip("GOZAR_TOXIPROXY_API not set")
	}
	return api
}

func toxicProxy(t *testing.T, api, upstream string, toxics []map[string]any) string {
	t.Helper()
	holder, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := holder.Addr().(*net.TCPAddr).Port
	holder.Close()
	name := fmt.Sprintf("gozar-tunnel-%d", time.Now().UnixNano())
	create, _ := json.Marshal(map[string]any{
		"name":     name,
		"listen":   fmt.Sprintf("127.0.0.1:%d", port),
		"upstream": upstream,
	})
	response, err := http.Post(api+"/proxies", "application/json", bytes.NewReader(create))
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusCreated && response.StatusCode != http.StatusOK {
		t.Fatalf("toxiproxy create status %d", response.StatusCode)
	}
	t.Cleanup(func() {
		request, _ := http.NewRequest(http.MethodDelete, api+"/proxies/"+name, nil)
		if reply, err := http.DefaultClient.Do(request); err == nil {
			io.Copy(io.Discard, reply.Body)
			reply.Body.Close()
		}
	})
	for _, toxic := range toxics {
		body, _ := json.Marshal(toxic)
		reply, err := http.Post(api+"/proxies/"+name+"/toxics", "application/json", bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, reply.Body)
		reply.Body.Close()
		if reply.StatusCode != http.StatusOK && reply.StatusCode != http.StatusCreated {
			t.Fatalf("toxic %v rejected with %d", toxic["type"], reply.StatusCode)
		}
	}
	return fmt.Sprintf("127.0.0.1:%d", port)
}

func TestToxiproxySlicedPathStillCarriesTheObfuscatedTunnel(t *testing.T) {
	api := toxiproxyAPI(t)
	record := &paddingRecord{}
	upstream, roots, stop := recordingProxy(t, record)
	defer stop()
	front := toxicProxy(t, api, upstream, []map[string]any{
		{"name": "slice", "type": "slicer", "stream": "upstream", "toxicity": 1.0,
			"attributes": map[string]any{"average_size": 24, "size_variation": 12, "delay": 200}},
		{"name": "delay", "type": "latency", "stream": "downstream", "toxicity": 1.0,
			"attributes": map[string]any{"latency": 30, "jitter": 10}},
		{"name": "throttle", "type": "bandwidth", "stream": "downstream", "toxicity": 1.0,
			"attributes": map[string]any{"rate": 512}},
	})
	host, portText, err := net.SplitHostPort(front)
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	style := bypass.Options{TLSFragment: true, Padding: true, Jitter: true}
	manager := NewManager(Target{Host: "localhost", DialHost: host, Port: port, Advanced: style})
	defer manager.Close()
	manager.testTLS = &tls.Config{RootCAs: roots}

	payload := bytes.Repeat([]byte("gozar-fragment-padding-jitter."), 4096)
	stream, err := manager.Open("example.com", 443, "toxic-pass", 40*time.Second)
	if err != nil {
		t.Fatalf("the obfuscated tunnel did not survive the sliced path: %v", err)
	}
	defer stream.Close()
	go func() {
		_, _ = stream.Write(payload)
		if closer, ok := stream.(interface{ CloseWrite() error }); ok {
			_ = closer.CloseWrite()
		}
	}()
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(stream, got); err != nil {
		t.Fatalf("payload lost on the hostile path: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("the hostile path corrupted the payload")
	}
	values, _ := record.snapshot()
	if len(values) == 0 || values[0] == "" {
		t.Fatal("the padding header never reached the proxy through toxiproxy")
	}
	raw, err := base64.StdEncoding.DecodeString(values[0])
	if err != nil || len(raw) == 0 || len(raw)%128 != 0 {
		t.Fatalf("padding arrived malformed: %d bytes, %v", len(raw), err)
	}
	t.Logf("%d bytes round-tripped through slicer+latency+bandwidth toxics with fragment/pad/jitter on", len(payload))
}
