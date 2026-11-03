package warp

import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"sync"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/quic-go/quic-go/quicvarint"
)

const (
	capsuleAddressAssign      = http3.CapsuleType(0x01)
	capsuleAddressRequest     = http3.CapsuleType(0x02)
	capsuleRouteAdvertisement = http3.CapsuleType(0x03)
	legacyDatagramSetting     = 0x276
	requestProtocol           = "cf-connect-ip"
	requestAuthority          = "cloudflareaccess.com"
)

type Options struct {
	MTU      int
	Deadline time.Duration
}

type Tunnel struct {
	stream  http3.RequestStream
	session quic.Connection
	tr      *http3.Transport

	mu     sync.Mutex
	local  []netip.Prefix
	routes []netip.Prefix
	closed bool
	once   sync.Once
	done   chan struct{}
}

func Dial(ctx context.Context, device *Device, carrier net.PacketConn, options Options) (*Tunnel, error) {
	if device == nil {
		return nil, errors.New("the warp device is missing")
	}
	if carrier == nil {
		return nil, errors.New("the warp tunnel needs a datagram carrier")
	}
	if options.MTU <= 0 {
		options.MTU = 1200
	}
	if options.Deadline <= 0 {
		options.Deadline = 20 * time.Second
	}
	key, err := device.Private()
	if err != nil {
		return nil, err
	}
	peer, err := device.Peer()
	if err != nil {
		return nil, err
	}
	endpoint, err := device.EndpointAddress()
	if err != nil {
		return nil, err
	}
	tlsConfig, err := tlsFor(key, peer, device.ServerName())
	if err != nil {
		return nil, err
	}
	remote, err := net.ResolveUDPAddr("udp", endpoint)
	if err != nil {
		return nil, fmt.Errorf("the warp endpoint is unusable: %w", err)
	}
	transport := newQUICTransport(carrier)
	session, err := transport.Dial(ctx, remote, tlsConfig, warpQUICConfig(options.MTU))
	if err != nil {
		return nil, fmt.Errorf("the warp handshake failed: %w", err)
	}
	if !session.ConnectionState().SupportsDatagrams {
		_ = session.CloseWithError(0, "")
		return nil, errors.New("the warp endpoint refused http datagrams")
	}
	h3 := newH3Transport()
	client := h3.NewClientConn(session)
	stream, err := client.OpenRequestStream(ctx)
	if err != nil {
		h3.Close()
		_ = session.CloseWithError(0, "")
		return nil, err
	}
	if err := sendConnectIP(stream); err != nil {
		h3.Close()
		_ = session.CloseWithError(0, "")
		return nil, err
	}
	response, err := stream.ReadResponse()
	if err != nil {
		h3.Close()
		_ = session.CloseWithError(0, "")
		return nil, fmt.Errorf("the warp proxy rejected the request: %w", err)
	}
	if debugEnabled() {
		println("warp: connect-ip status", response.StatusCode, "proto", response.Proto)
	}
	if response.StatusCode < 200 || response.StatusCode > 299 {
		h3.Close()
		_ = session.CloseWithError(0, "")
		return nil, fmt.Errorf("the warp proxy answered %d", response.StatusCode)
	}
	prefixes, err := device.Prefixes()
	if err != nil {
		h3.Close()
		_ = session.CloseWithError(0, "")
		return nil, err
	}
	tunnel := &Tunnel{stream: stream, session: session, tr: h3, local: prefixes, done: make(chan struct{})}
	go tunnel.pump()
	if err := tunnel.requestAddresses(device); err != nil && debugEnabled() {
		println("warp: address request failed:", err.Error())
	}
	deadline := time.Now().Add(options.Deadline)
	for time.Now().Before(deadline) {
		tunnel.mu.Lock()
		ready := len(tunnel.local) > 0
		tunnel.mu.Unlock()
		if ready {
			return tunnel, nil
		}
		select {
		case <-tunnel.done:
			tunnel.Close()
			return nil, errors.New("the warp proxy closed the tunnel before assigning an address")
		case <-ctx.Done():
			tunnel.Close()
			return nil, ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
	tunnel.Close()
	return nil, errors.New("the warp proxy did not assign an address in time")
}

func newQUICTransport(carrier net.PacketConn) *quic.Transport {
	return &quic.Transport{Conn: carrier, ConnectionIDLength: 20}
}

func warpQUICConfig(mtu int) *quic.Config {
	return &quic.Config{
		EnableDatagrams:         true,
		InitialPacketSize:       uint16(mtu),
		DisablePathMTUDiscovery: true,
		MaxIdleTimeout:          5 * time.Minute,
		KeepAlivePeriod:         20 * time.Second,
	}
}

func newH3Transport() *http3.Transport {
	return &http3.Transport{
		EnableDatagrams:    true,
		DisableCompression: true,
		AdditionalSettings: map[uint64]uint64{legacyDatagramSetting: 1},
	}
}

func sendConnectIP(stream http3.RequestStream) error {
	requestURL, _ := url.Parse("https://" + requestAuthority)
	request := &http.Request{
		Method: http.MethodConnect,
		Proto:  requestProtocol,
		Host:   requestURL.Host,
		URL:    requestURL,
		Header: http.Header{http3.CapsuleProtocolHeader: []string{"?1"}},
	}
	return stream.SendRequestHeader(request)
}

func tlsFor(key *ecdsa.PrivateKey, pinned []byte, name string) (*tls.Config, error) {
	der, err := x509.CreateCertificate(rand.Reader, &x509.Certificate{
		SerialNumber: big.NewInt(0),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(30 * 24 * time.Hour),
	}, &x509.Certificate{}, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	block := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	pair, err := tls.X509KeyPair(block, pem.EncodeToMemory(&pem.Block{
		Type:  "EC PRIVATE KEY",
		Bytes: mustECKey(key),
	}))
	if err != nil {
		return nil, err
	}
	return &tls.Config{
		Certificates:       []tls.Certificate{pair},
		ServerName:         name,
		NextProtos:         []string{http3.NextProtoH3},
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return errors.New("the warp endpoint sent no certificate")
			}
			certificate, err := x509.ParseCertificate(raw[0])
			if err != nil {
				return err
			}
			encoded, err := x509.MarshalPKIXPublicKey(certificate.PublicKey)
			if err != nil {
				return err
			}
			if !equalBytes(encoded, pinned) {
				return errors.New("the warp endpoint presented an unexpected key")
			}
			return nil
		},
	}, nil
}

func mustECKey(key *ecdsa.PrivateKey) []byte {
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil
	}
	return der
}

func equalBytes(left, right []byte) bool {
	if len(left) != len(right) {
		return false
	}
	var difference byte
	for index := range left {
		difference |= left[index] ^ right[index]
	}
	return difference == 0
}

func debugEnabled() bool { return os.Getenv("WARP_DEBUG") == "1" }

func (t *Tunnel) requestAddresses(device *Device) error {
	prefixes, err := device.Prefixes()
	if err != nil {
		return err
	}
	body := make([]byte, 0, 48)
	request := uint64(1)
	for _, prefix := range prefixes {
		body = quicvarint.Append(body, request)
		request++
		if prefix.Addr().Is4() {
			body = append(body, 4)
			raw := prefix.Addr().As4()
			body = append(body, raw[:]...)
		} else {
			body = append(body, 6)
			raw := prefix.Addr().As16()
			body = append(body, raw[:]...)
		}
		body = append(body, byte(prefix.Bits()))
	}
	return http3.WriteCapsule(quicvarint.NewWriter(mustWriter(t.stream)), capsuleAddressRequest, body)
}

type streamWriter struct{ stream http3.RequestStream }

func (s streamWriter) Write(p []byte) (int, error) { return s.stream.Write(p) }

func mustWriter(stream http3.RequestStream) *streamWriter { return &streamWriter{stream: stream} }

func (t *Tunnel) pump() {
	for {
		kind, body, err := http3.ParseCapsule(quicvarint.NewReader(t.stream))
		if err != nil {
			t.fail()
			return
		}
		if debugEnabled() {
			println("warp: capsule type", uint64(kind))
		}
		switch kind {
		case capsuleAddressAssign:
			if prefixes, err := parseAssigned(body); err == nil && len(prefixes) > 0 {
				t.mu.Lock()
				t.local = prefixes
				t.mu.Unlock()
			}
		case capsuleRouteAdvertisement:
			if ranges, err := parseRoutes(body); err == nil {
				t.mu.Lock()
				t.routes = ranges
				t.mu.Unlock()
			}
		case capsuleAddressRequest:
			_, _ = io.Copy(io.Discard, body)
		}
	}
}

func (t *Tunnel) fail() {
	t.once.Do(func() {
		t.mu.Lock()
		t.closed = true
		t.mu.Unlock()
		close(t.done)
	})
}

func (t *Tunnel) Done() <-chan struct{} { return t.done }

func (t *Tunnel) Addresses() []netip.Prefix {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]netip.Prefix(nil), t.local...)
}

func (t *Tunnel) Routes() []netip.Prefix {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]netip.Prefix(nil), t.routes...)
}

func (t *Tunnel) WritePacket(packet []byte) error {
	if len(packet) == 0 {
		return io.ErrUnexpectedEOF
	}
	t.mu.Lock()
	closed := t.closed
	t.mu.Unlock()
	if closed {
		return net.ErrClosed
	}
	frame := quicvarint.Append(nil, 0)
	frame = append(frame, packet...)
	return t.stream.SendDatagram(frame)
}

func (t *Tunnel) ReadPacket(ctx context.Context) ([]byte, error) {
	for {
		payload, err := t.stream.ReceiveDatagram(ctx)
		if err != nil {
			return nil, err
		}
		contextID, used, err := quicvarint.Parse(payload)
		if err != nil {
			continue
		}
		if contextID != 0 {
			continue
		}
		if used >= len(payload) {
			continue
		}
		return payload[used:], nil
	}
}

func (t *Tunnel) Close() {
	t.fail()
	if t.stream != nil {
		_ = t.stream.Close()
	}
	if t.session != nil {
		_ = t.session.CloseWithError(0, "")
	}
	if t.tr != nil {
		_ = t.tr.Close()
	}
}

func parseAssigned(body io.Reader) ([]netip.Prefix, error) {
	var out []netip.Prefix
	reader := quicvarint.NewReader(body)
	for {
		_, err := quicvarint.Read(reader)
		if err != nil {
			if err == io.EOF {
				return out, nil
			}
			return out, err
		}
		version, err := readByte(body)
		if err != nil {
			return out, err
		}
		width := 4
		if version == 6 {
			width = 16
		} else if version != 4 {
			return out, errors.New("the warp proxy assigned an unknown address family")
		}
		raw := make([]byte, width)
		if _, err := io.ReadFull(body, raw); err != nil {
			return out, err
		}
		length, err := readByte(body)
		if err != nil {
			return out, err
		}
		address, ok := netip.AddrFromSlice(raw)
		if !ok {
			return out, errors.New("the warp proxy assigned a malformed address")
		}
		if int(length) > address.BitLen() {
			return out, errors.New("the warp proxy assigned an invalid prefix")
		}
		out = append(out, netip.PrefixFrom(address.Unmap(), int(length)))
	}
}

func parseRoutes(body io.Reader) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for {
		version, err := readByte(body)
		if err != nil {
			if err == io.EOF {
				return out, nil
			}
			return out, err
		}
		width := 4
		if version == 6 {
			width = 16
		} else if version != 4 {
			return out, errors.New("the warp proxy advertised an unknown address family")
		}
		start := make([]byte, width)
		if _, err := io.ReadFull(body, start); err != nil {
			return out, err
		}
		end := make([]byte, width)
		if _, err := io.ReadFull(body, end); err != nil {
			return out, err
		}
		if _, err := readByte(body); err != nil {
			return out, err
		}
		from, ok := netip.AddrFromSlice(start)
		if !ok {
			return out, errors.New("the warp proxy advertised a malformed range")
		}
		to, ok := netip.AddrFromSlice(end)
		if !ok {
			return out, errors.New("the warp proxy advertised a malformed range")
		}
		if prefix, err := netip.ParsePrefix(from.String() + "/" + itoa(bits(from, to))); err == nil {
			out = append(out, prefix)
		}
	}
}

func bits(from, to netip.Addr) int {
	left := from.AsSlice()
	right := to.AsSlice()
	prefix := 0
	for index := range left {
		xor := left[index] ^ right[index]
		if xor == 0 {
			prefix += 8
			continue
		}
		for bit := 7; bit >= 0; bit-- {
			if xor&(1<<bit) != 0 {
				return prefix
			}
			prefix++
		}
	}
	return prefix
}

func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	var digits [4]byte
	position := len(digits)
	for value > 0 && position > 0 {
		position--
		digits[position] = byte('0' + value%10)
		value /= 10
	}
	return string(digits[position:])
}

func readByte(reader io.Reader) (byte, error) {
	var single [1]byte
	if _, err := io.ReadFull(reader, single[:]); err != nil {
		if err == io.ErrUnexpectedEOF {
			return 0, io.EOF
		}
		return 0, err
	}
	return single[0], nil
}
