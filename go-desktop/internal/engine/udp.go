package engine

import (
	"context"
	"errors"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

type Datagram interface {
	Send([]byte) error
	Receive(context.Context) ([]byte, error)
	Close()
}

type DatagramDialer func(host string, port int) (Datagram, error)

var relayPorts = map[int]bool{80: true, 443: true, 3478: true, 8080: true, 8443: true}

var relayPortsMu sync.RWMutex
var relayExtra = map[int]bool{}

func SetRelayPorts(extra []int) {
	relayPortsMu.Lock()
	defer relayPortsMu.Unlock()
	relayExtra = map[int]bool{}
	for _, port := range extra {
		if port > 0 && port < 65536 {
			relayExtra[port] = true
		}
	}
}

func RelayPorts() []int {
	relayPortsMu.RLock()
	defer relayPortsMu.RUnlock()
	seen := map[int]bool{}
	var out []int
	for port := range relayPorts {
		seen[port] = true
		out = append(out, port)
	}
	for port := range relayExtra {
		if !seen[port] {
			out = append(out, port)
		}
	}
	sort.Ints(out)
	return out
}

var deniedMu sync.RWMutex
var deniedPorts = map[int]time.Time{}

func udpAllowed(port int) bool {
	relayPortsMu.RLock()
	ok := relayPorts[port] || relayExtra[port]
	relayPortsMu.RUnlock()
	if !ok {
		return false
	}
	deniedMu.RLock()
	defer deniedMu.RUnlock()
	until, blocked := deniedPorts[port]
	return !blocked || time.Now().After(until)
}

func denyUDPPort(port int) {
	deniedMu.Lock()
	deniedPorts[port] = time.Now().Add(10 * time.Minute)
	deniedMu.Unlock()
}

func resetUDPDenials() {
	deniedMu.Lock()
	deniedPorts = map[int]time.Time{}
	deniedMu.Unlock()
}

func (e *Engine) relayDatagrams(local net.PacketConn, remote Datagram) {
	done := make(chan struct{}, 2)
	go func() {
		buffer := make([]byte, 65535)
		for {
			_ = local.SetReadDeadline(time.Now().Add(60 * time.Second))
			count, _, err := local.ReadFrom(buffer)
			if err != nil || count == 0 {
				done <- struct{}{}
				return
			}
			if err := remote.Send(buffer[:count]); err != nil {
				done <- struct{}{}
				return
			}
			e.up.Add(int64(count))
		}
	}()
	go func() {
		for {
			ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
			payload, err := remote.Receive(ctx)
			cancel()
			if err != nil {
				done <- struct{}{}
				return
			}
			if _, err := local.WriteTo(payload, nil); err != nil {
				done <- struct{}{}
				return
			}
			e.down.Add(int64(len(payload)))
		}
	}()
	<-done
	local.Close()
	remote.Close()
}

func (e *Engine) dialDatagram(host string, port int) (Datagram, error) {
	switch e.routeFor(port, port == 53) {
	case RouteWarp:
		if e.warpDial != nil {
			remote, err := e.warpDial(host, port)
			if err == nil {
				return remote, nil
			}
		}
		if e.udpDial != nil && udpAllowed(port) {
			return e.udpDial(host, port)
		}
		return nil, ErrUDPDenied
	case RouteRelay:
		if e.udpDial == nil {
			return nil, ErrUDPDenied
		}
		return e.udpDial(host, port)
	default:
		return nil, ErrUDPDenied
	}
}

func (e *Engine) handleDataUDP(request udpForwardRequest) {
	remote, err := e.dialDatagram(request.host(), request.port())
	if err != nil {
		if errors.Is(err, ErrUDPDenied) {
			denyUDPPort(request.port())
		}
		request.reject()
		return
	}
	local, err := request.accept()
	if err != nil {
		remote.Close()
		return
	}
	e.relayDatagrams(local, remote)
}

var ErrUDPDenied = errors.New("the relay does not carry datagrams to this port")

type udpForwardRequest interface {
	host() string
	port() int
	accept() (net.PacketConn, error)
	reject()
}

func NormalizePorts(values []string) []int {
	var out []int
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		var port int
		ok := true
		for _, r := range value {
			if r < '0' || r > '9' {
				ok = false
				break
			}
			port = port*10 + int(r-'0')
		}
		if ok && port > 0 && port < 65536 {
			out = append(out, port)
		}
	}
	return out
}
