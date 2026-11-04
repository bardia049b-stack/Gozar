package warp

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"
	"golang.org/x/net/http2"
)

// MaxWorkers is the hard concurrency cap for endpoint scanning.
const MaxWorkers = 10

// EndpointResult is one real WARP handshake probe.
type EndpointResult struct {
	Endpoint string        `json:"endpoint"`
	Latency  time.Duration `json:"latency"`
	OK       bool          `json:"ok"`
	Protocol string        `json:"protocol,omitempty"`
	Error    string        `json:"error,omitempty"`
	Checked  time.Time     `json:"checked"`
}

// Scanner probes WARP endpoints using the registered P-256 client certificate.
// A plain TLS connect is not considered a successful WARP probe.
type Scanner struct {
	Identity  *Identity
	Endpoints []string
	Ports     []int
	Timeout   time.Duration
	Workers   int
}

func DefaultScanner() *Scanner {
	return &Scanner{
		Endpoints: []string{"162.159.198.1", "162.159.198.2", "162.159.199.1", "162.159.199.2", "162.159.200.1", "162.159.200.2"},
		Ports:     []int{443, 500, 1701, 4500, 4443, 8443, 8095},
		Timeout:   2 * time.Second,
		Workers:   MaxWorkers,
	}
}

func (s *Scanner) effectiveWorkers() int {
	workers := s.Workers
	if workers <= 0 {
		workers = MaxWorkers
	}
	if workers > MaxWorkers {
		workers = MaxWorkers
	}
	return workers
}

func (s *Scanner) Scan(ctx context.Context) []EndpointResult {
	timeout := s.Timeout
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	ports := s.Ports
	if len(ports) == 0 {
		ports = []int{DefaultPort}
	}
	type job struct {
		host string
		port int
	}
	jobs := make(chan job)
	results := make(chan EndpointResult)
	var wg sync.WaitGroup
	for i := 0; i < s.effectiveWorkers(); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for item := range jobs {
				endpoint := net.JoinHostPort(item.host, strconv.Itoa(item.port))
				result := EndpointResult{Endpoint: endpoint, Checked: time.Now()}
				if s.Identity == nil {
					result.Error = "WARP identity is required for a real handshake"
				} else {
					probeCtx, cancel := context.WithTimeout(ctx, timeout)
					started := time.Now()
					protocol, err := probeWARP(probeCtx, s.Identity, endpoint)
					cancel()
					result.Latency = time.Since(started)
					if err == nil {
						result.OK = true
						result.Protocol = protocol
					} else {
						result.Error = err.Error()
					}
				}
				select {
				case results <- result:
				case <-ctx.Done():
					return
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, host := range s.Endpoints {
			for _, port := range ports {
				select {
				case jobs <- job{host: host, port: port}:
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	go func() {
		wg.Wait()
		close(results)
	}()
	var out []EndpointResult
	for result := range results {
		out = append(out, result)
	}
	sortResults(out)
	return out
}

func sortResults(results []EndpointResult) {
	sort.Slice(results, func(i, j int) bool {
		if results[i].OK != results[j].OK {
			return results[i].OK
		}
		if results[i].OK {
			return results[i].Latency < results[j].Latency
		}
		return results[i].Endpoint < results[j].Endpoint
	})
}

// probeWARP performs a real MASQUE/CONNECT-IP handshake. It first tries HTTP/3
// datagrams and then HTTP/2 capsules, both authenticated with the P-256 client
// certificate and pinned peer key from the WARP identity.
func probeWARP(ctx context.Context, identity *Identity, endpoint string) (string, error) {
	if identity == nil {
		return "", errors.New("missing WARP identity")
	}
	if protocol, err := probeWARPH3(ctx, identity, endpoint); err == nil {
		return protocol, nil
	}
	protocol, err := probeWARPH2(ctx, identity, endpoint)
	if err != nil {
		return "", err
	}
	return protocol, nil
}

func probeWARPH3(ctx context.Context, identity *Identity, endpoint string) (string, error) {
	config, err := identity.tlsConfig([]string{http3.NextProtoH3})
	if err != nil {
		return "", err
	}
	conn, err := quic.DialAddr(ctx, endpoint, config, &quic.Config{
		EnableDatagrams:   true,
		KeepAlivePeriod:   0,
		MaxIdleTimeout:    10 * time.Second,
		InitialPacketSize: 1242,
	})
	if err != nil {
		return "", err
	}
	defer conn.CloseWithError(0, "")
	client := (&http3.Transport{DisableCompression: true, EnableDatagrams: true}).NewClientConn(conn)
	headers := http.Header{"User-Agent": []string{""}}
	stream, _, err := connectip.Dial(ctx, client, uritemplate.MustNew(ConnectURI), Protocol, headers, true)
	if err != nil {
		return "", err
	}
	stream.Close()
	return "h3", nil
}

func probeWARPH2(ctx context.Context, identity *Identity, endpoint string) (string, error) {
	config, err := identity.tlsConfig([]string{"h2"})
	if err != nil {
		return "", err
	}
	transport := &http2.Transport{
		TLSClientConfig: config,
		DialTLSContext: func(ctx context.Context, network, addr string, _ *tls.Config) (net.Conn, error) {
			dialer := &tls.Dialer{Config: config}
			return dialer.DialContext(ctx, network, endpoint)
		},
	}
	client := &http.Client{Transport: transport}
	defer client.CloseIdleConnections()
	headers := http.Header{"Capsule-Protocol": []string{"?1"}, "User-Agent": []string{""}}
	stream, _, err := connectip.DialH2(ctx, client, uritemplate.MustNew(ConnectURI), headers)
	if err != nil {
		return "", err
	}
	stream.Close()
	return "h2", nil
}

func (s *Scanner) Best(ctx context.Context) (string, bool) {
	return bestOf(s.Scan(ctx))
}

func bestOf(results []EndpointResult) (string, bool) {
	best := ""
	var bestLatency time.Duration
	for _, result := range results {
		if !result.OK {
			continue
		}
		if best == "" || result.Latency < bestLatency {
			best, bestLatency = result.Endpoint, result.Latency
		}
	}
	return best, best != ""
}

// Report is the persisted warp_endpoints.json document.
type Report struct {
	Version     int              `json:"version"`
	GeneratedAt time.Time        `json:"generated_at"`
	Best        string           `json:"best,omitempty"`
	Results     []EndpointResult `json:"results"`
}

func DefaultResultsPath() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "Gozar", "warp_endpoints.json"), nil
}

func WriteResults(path string, results []EndpointResult) error {
	sortResults(results)
	best, _ := bestOf(results)
	report := Report{Version: 1, GeneratedAt: time.Now().UTC(), Best: best, Results: results}
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

func LoadResults(path string) (*Report, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var report Report
	if err := json.Unmarshal(data, &report); err != nil {
		return nil, err
	}
	if report.Version != 1 {
		return nil, fmt.Errorf("unsupported warp endpoint report version %d", report.Version)
	}
	sortResults(report.Results)
	return &report, nil
}

func (s *Scanner) ScanAndSave(ctx context.Context, path string) (*Report, error) {
	results := s.Scan(ctx)
	if err := WriteResults(path, results); err != nil {
		return nil, err
	}
	return LoadResults(path)
}

// Cache keeps the previous best-endpoint cache API for callers that only need
// one endpoint. New code should prefer Report/WriteResults.
type Cache struct {
	Path     string
	TTL      time.Duration
	mu       sync.Mutex
	endpoint string
	storedAt time.Time
}

func NewCache(path string) *Cache {
	return &Cache{Path: path, TTL: 6 * time.Hour}
}

func (c *Cache) Load() (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.endpoint != "" {
		if time.Since(c.storedAt) < c.TTL {
			return c.endpoint, true
		}
		return "", false
	}
	data, err := os.ReadFile(c.Path)
	if err != nil {
		return "", false
	}
	var saved struct {
		Endpoint string    `json:"endpoint"`
		StoredAt time.Time `json:"stored_at"`
	}
	if json.Unmarshal(data, &saved) != nil || !ValidEndpoint(saved.Endpoint) || time.Since(saved.StoredAt) >= c.TTL {
		return "", false
	}
	c.endpoint, c.storedAt = saved.Endpoint, saved.StoredAt
	return c.endpoint, true
}

func (c *Cache) Save(endpoint string) error {
	if !ValidEndpoint(endpoint) {
		return os.ErrInvalid
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.endpoint, c.storedAt = endpoint, time.Now()
	if err := os.MkdirAll(filepath.Dir(c.Path), 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(struct {
		Endpoint string    `json:"endpoint"`
		StoredAt time.Time `json:"stored_at"`
	}{Endpoint: endpoint, StoredAt: c.storedAt})
	if err != nil {
		return err
	}
	return os.WriteFile(c.Path, data, 0o600)
}
