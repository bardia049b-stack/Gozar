package bridge

import (
	"bufio"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Dialer func(host string, port int) (io.ReadWriteCloser, error)

type Config struct {
	Address    string
	Port       int
	Allow      []netip.Prefix
	User       string
	Pass       string
	MaxClients int
}

type Stats struct {
	Running  bool     `json:"running"`
	Address  string   `json:"address"`
	Port     int      `json:"port"`
	Clients  int      `json:"clients"`
	Total    int64    `json:"total"`
	Up       int64    `json:"up"`
	Down     int64    `json:"down"`
	Last     string   `json:"last"`
	Allow    []string `json:"allow"`
	DNS      string   `json:"dns"`
	Requires bool     `json:"requiresAuth"`
}

type Server struct {
	config Config
	dial   Dialer
	dns    Resolver

	mu      sync.Mutex
	listen  net.Listener
	dnsConn *net.UDPConn
	dnsTCP  net.Listener
	closed  bool
	active  int
	total   int64
	up      atomic.Int64
	down    atomic.Int64
	last    string
}

type Resolver interface {
	Resolve([]byte) ([]byte, error)
}

func New(config Config, dial Dialer, dns Resolver) (*Server, error) {
	if config.Port <= 0 || config.Port > 65535 {
		return nil, errors.New("the console bridge needs a port between 1 and 65535")
	}
	if config.MaxClients <= 0 {
		config.MaxClients = 32
	}
	if config.Address == "" {
		return nil, errors.New("the console bridge needs a local address")
	}
	return &Server{config: config, dial: dial, dns: dns}, nil
}

func (s *Server) Start() error {
	s.mu.Lock()
	if s.listen != nil {
		s.mu.Unlock()
		return nil
	}
	listen, err := net.Listen("tcp", net.JoinHostPort(s.config.Address, strconv.Itoa(s.config.Port)))
	if err != nil {
		s.mu.Unlock()
		return err
	}
	s.listen = listen
	s.closed = false
	s.mu.Unlock()
	go s.acceptLoop(listen)
	if s.dns != nil {
		go s.startDNS()
	}
	return nil
}

func (s *Server) acceptLoop(listen net.Listener) {
	for {
		client, err := listen.Accept()
		if err != nil {
			return
		}
		if !s.permitted(client.RemoteAddr()) {
			client.Close()
			continue
		}
		s.mu.Lock()
		if s.closed || s.active >= s.config.MaxClients {
			s.mu.Unlock()
			client.Close()
			continue
		}
		s.active++
		s.total++
		s.last = client.RemoteAddr().String()
		s.mu.Unlock()
		go s.serve(client)
	}
}

func (s *Server) permitted(remote net.Addr) bool {
	host, _, err := net.SplitHostPort(remote.String())
	if err != nil {
		return false
	}
	address, err := netip.ParseAddr(host)
	if err != nil {
		return false
	}
	address = address.Unmap()
	if address.IsLoopback() {
		return true
	}
	for _, prefix := range s.config.Allow {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func (s *Server) serve(client net.Conn) {
	defer func() {
		client.Close()
		s.mu.Lock()
		s.active--
		s.mu.Unlock()
	}()
	_ = client.SetDeadline(time.Now().Add(30 * time.Second))
	reader := bufio.NewReader(client)
	head, err := readHead(reader)
	if err != nil {
		return
	}
	lines := strings.Split(head, "\r\n")
	parts := strings.Split(lines[0], " ")
	if len(parts) < 3 {
		return
	}
	method, target, version := parts[0], parts[1], parts[2]
	if s.config.User != "" && !s.authorized(lines) {
		_, _ = io.WriteString(client, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Gozar\"\r\nConnection: close\r\n\r\n")
		return
	}
	if strings.EqualFold(method, "CONNECT") {
		s.tunnel(client, target, version)
		return
	}
	s.forward(client, reader, method, target, version, lines)
}

func (s *Server) authorized(lines []string) bool {
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte(s.config.User+":"+s.config.Pass))
	for _, line := range lines[1:] {
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		if strings.EqualFold(strings.TrimSpace(name), "Proxy-Authorization") &&
			strings.EqualFold(strings.TrimSpace(value), want) {
			return true
		}
	}
	return false
}

func (s *Server) tunnel(client net.Conn, target, version string) {
	host, port, err := splitTarget(target, 443)
	if err != nil {
		_, _ = io.WriteString(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
		return
	}
	remote, err := s.dial(host, port)
	if err != nil {
		_, _ = io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	defer remote.Close()
	_, _ = io.WriteString(client, "HTTP/1.1 200 Connection established\r\n\r\n")
	_ = client.SetDeadline(time.Time{})
	s.relay(client, remote)
}

func (s *Server) forward(client net.Conn, reader *bufio.Reader, method, target, version string, lines []string) {
	if !strings.HasPrefix(target, "http://") {
		_, _ = io.WriteString(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
		return
	}
	rest := strings.TrimPrefix(target, "http://")
	slash := strings.Index(rest, "/")
	authority := rest
	path := "/"
	if slash >= 0 {
		authority = rest[:slash]
		path = rest[slash:]
	}
	host, port, err := splitTarget(authority, 80)
	if err != nil {
		_, _ = io.WriteString(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
		return
	}
	remote, err := s.dial(host, port)
	if err != nil {
		_, _ = io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	defer remote.Close()
	var rebuilt strings.Builder
	rebuilt.WriteString(method + " " + path + " " + version + "\r\n")
	for _, line := range lines[1:] {
		if line == "" {
			continue
		}
		name, _, _ := strings.Cut(line, ":")
		switch strings.ToLower(strings.TrimSpace(name)) {
		case "proxy-connection", "proxy-authorization", "connection":
			continue
		}
		rebuilt.WriteString(line + "\r\n")
	}
	rebuilt.WriteString("Connection: close\r\n\r\n")
	if _, err := io.WriteString(remote, rebuilt.String()); err != nil {
		return
	}
	_ = client.SetDeadline(time.Time{})
	s.relay(client, remote)
}

func (s *Server) relay(client net.Conn, remote io.ReadWriteCloser) {
	done := make(chan struct{}, 2)
	go func() {
		copyCount(remote, client, &s.down)
		done <- struct{}{}
	}()
	go func() {
		copyCount(client, remote, &s.up)
		done <- struct{}{}
	}()
	<-done
	client.Close()
	remote.Close()
	<-done
}

func copyCount(dst io.Writer, src io.Reader, counter *atomic.Int64) {
	buffer := make([]byte, 32*1024)
	for {
		count, err := src.Read(buffer)
		if count > 0 {
			if _, werr := dst.Write(buffer[:count]); werr != nil {
				return
			}
			counter.Add(int64(count))
		}
		if err != nil {
			return
		}
	}
}

func readHead(reader *bufio.Reader) (string, error) {
	var builder strings.Builder
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return "", err
		}
		if len(builder.String())+len(line) > 16*1024 {
			return "", errors.New("the proxy request head is too large")
		}
		builder.WriteString(line)
		if line == "\r\n" || line == "\n" {
			return builder.String(), nil
		}
	}
}

func splitTarget(value string, fallback int) (string, int, error) {
	if value == "" || len(value) > 255 {
		return "", 0, errors.New("bad target")
	}
	host := value
	port := fallback
	if strings.HasPrefix(value, "[") {
		end := strings.Index(value, "]")
		if end < 0 {
			return "", 0, errors.New("bad ipv6 target")
		}
		host = value[1:end]
		if rest := value[end+1:]; strings.HasPrefix(rest, ":") {
			parsed, err := strconv.Atoi(rest[1:])
			if err != nil || parsed < 1 || parsed > 65535 {
				return "", 0, errors.New("bad port")
			}
			port = parsed
		}
	} else if index := strings.LastIndex(value, ":"); index > 0 {
		parsed, err := strconv.Atoi(value[index+1:])
		if err != nil || parsed < 1 || parsed > 65535 {
			return "", 0, errors.New("bad port")
		}
		host = value[:index]
		port = parsed
	}
	if host == "" || strings.ContainsAny(host, " \t\r\n/") {
		return "", 0, errors.New("bad host")
	}
	return host, port, nil
}

func (s *Server) startDNS() {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(s.config.Address), Port: 53})
	if err != nil {
		return
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		conn.Close()
		return
	}
	s.dnsConn = conn
	s.mu.Unlock()
	buffer := make([]byte, 4096)
	for {
		count, from, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		query := append([]byte{}, buffer[:count]...)
		go s.answerDNS(conn, from, query)
	}
}

func (s *Server) answerDNS(conn *net.UDPConn, from *net.UDPAddr, query []byte) {
	answer, err := s.dns.Resolve(query)
	if err != nil || len(answer) < 12 {
		return
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.WriteToUDP(answer, from); err != nil {
		return
	}
	s.down.Add(int64(len(answer)))
	s.up.Add(int64(len(query)))
}

func (s *Server) Stats() Stats {
	s.mu.Lock()
	defer s.mu.Unlock()
	allow := make([]string, 0, len(s.config.Allow))
	for _, prefix := range s.config.Allow {
		allow = append(allow, prefix.String())
	}
	sort.Strings(allow)
	dnsAddress := ""
	if s.dnsConn != nil {
		dnsAddress = s.config.Address + ":53"
	}
	return Stats{
		Running:  s.listen != nil && !s.closed,
		Address:  s.config.Address,
		Port:     s.config.Port,
		Clients:  s.active,
		Total:    s.total,
		Up:       s.up.Load(),
		Down:     s.down.Load(),
		Last:     s.last,
		Allow:    allow,
		DNS:      dnsAddress,
		Requires: s.config.User != "",
	}
}

func (s *Server) Stop() {
	s.mu.Lock()
	s.closed = true
	listen := s.listen
	dns := s.dnsConn
	tcpDNS := s.dnsTCP
	s.listen = nil
	s.dnsConn = nil
	s.dnsTCP = nil
	s.mu.Unlock()
	if listen != nil {
		listen.Close()
	}
	if dns != nil {
		dns.Close()
	}
	if tcpDNS != nil {
		tcpDNS.Close()
	}
}
