package warp

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"
)

type EndpointResult struct {
	Endpoint string        `json:"endpoint"`
	Latency  time.Duration `json:"latency"`
	OK       bool          `json:"ok"`
	Checked  time.Time     `json:"checked"`
}

type Scanner struct {
	Endpoints []string
	Ports     []int
	Timeout   time.Duration
	Workers   int
}

func DefaultScanner() *Scanner {
	return &Scanner{
		Endpoints: []string{"162.159.198.1", "162.159.198.2", "162.159.199.1", "162.159.199.2", "162.159.200.1", "162.159.200.2"},
		Ports:     []int{443, 500, 1701, 4500, 4443, 8443, 8095},
		Timeout:   1500 * time.Millisecond,
		Workers:   16,
	}
}

func (s *Scanner) Scan(ctx context.Context) []EndpointResult {
	timeout := s.Timeout
	if timeout <= 0 {
		timeout = 1500 * time.Millisecond
	}
	workers := s.Workers
	if workers <= 0 {
		workers = 16
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
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for item := range jobs {
				endpoint := net.JoinHostPort(item.host, strconv.Itoa(item.port))
				started := time.Now()
				dialer := &tls.Dialer{Config: &tls.Config{ServerName: ConnectSNI, InsecureSkipVerify: true, NextProtos: []string{"h2"}}}
				dialCtx, cancel := context.WithTimeout(ctx, timeout)
				conn, err := dialer.DialContext(dialCtx, "tcp", endpoint)
				cancel()
				result := EndpointResult{Endpoint: endpoint, Checked: time.Now(), Latency: time.Since(started)}
				if err == nil {
					conn.Close()
					result.OK = true
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
	sort.Slice(out, func(i, j int) bool {
		if out[i].OK != out[j].OK {
			return out[i].OK
		}
		return out[i].Latency < out[j].Latency
	})
	return out
}

func (s *Scanner) Best(ctx context.Context) (string, bool) {
	for _, result := range s.Scan(ctx) {
		if result.OK {
			return result.Endpoint, true
		}
	}
	return "", false
}

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
