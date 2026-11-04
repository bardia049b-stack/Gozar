package warptest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/packet"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/quic-go/quic-go/http3"
	"github.com/quic-go/quic-go/quicvarint"
	"github.com/yosida95/uritemplate/v3"
	"golang.org/x/net/http2"
)

type Server struct {
	Identity *warp.Identity
	Addr     string
	Port     int
	h3       *http3.Server
	h2       *http.Server
	udp      net.PacketConn
	tcp      net.Listener
	once     sync.Once
}

func Start() (*Server, error) {
	serverKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	clientKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "mock-warp"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"localhost", "consumer-masque.cloudflareclient.com"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &serverKey.PublicKey, serverKey)
	if err != nil {
		return nil, err
	}
	serverSPKI, err := x509.MarshalPKIXPublicKey(&serverKey.PublicKey)
	if err != nil {
		return nil, err
	}
	clientDER, err := x509.MarshalECPrivateKey(clientKey)
	if err != nil {
		return nil, err
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: serverKey}
	var udp net.PacketConn
	var tcp net.Listener
	for attempt := 0; attempt < 20; attempt++ {
		udp, err = net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
		port := udp.LocalAddr().(*net.UDPAddr).Port
		tcp, err = net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
		if err == nil {
			break
		}
		udp.Close()
	}
	if tcp == nil {
		udp.Close()
		return nil, errors.New("could not reserve matching tcp/udp port")
	}
	port := udp.LocalAddr().(*net.UDPAddr).Port
	mock := &Server{
		Addr: udp.LocalAddr().String(),
		Port: port,
		udp:  udp,
		tcp:  tcp,
		Identity: &warp.Identity{
			ID:         "mock-device",
			Token:      "mock-token",
			PrivateKey: base64.StdEncoding.EncodeToString(clientDER),
			IPv4:       "10.111.0.2",
			IPv6:       "fd00::2",
			PeerKey:    base64.StdEncoding.EncodeToString(serverSPKI),
			Endpoint:   "127.0.0.1",
		},
	}
	mock.Identity.Endpoint = "127.0.0.1"
	h3 := &http3.Server{
		TLSConfig:       &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{http3.NextProtoH3}},
		EnableDatagrams: true,
		Handler:         http.HandlerFunc(mock.handleH3),
	}
	mock.h3 = h3
	h2 := &http.Server{
		Handler:   http.HandlerFunc(mock.handleH2),
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}},
	}
	if err := http2.ConfigureServer(h2, &http2.Server{}); err != nil {
		mock.Close()
		return nil, err
	}
	mock.h2 = h2
	go h3.Serve(udp)
	go h2.ServeTLS(tcp, "", "")
	return mock, nil
}

func (s *Server) Close() error {
	var err error
	s.once.Do(func() {
		if s.h3 != nil {
			err = s.h3.Close()
		}
		if s.h2 != nil {
			s.h2.Close()
		}
		if s.udp != nil {
			s.udp.Close()
		}
		if s.tcp != nil {
			s.tcp.Close()
		}
	})
	return err
}

func (s *Server) handleH3(w http.ResponseWriter, r *http.Request) {
	template := uritemplate.MustNew(warp.ConnectURI)
	request, err := connectip.ParseRequest(r, template, warp.Protocol)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	proxy := &connectip.Proxy{}
	conn, err := proxy.Proxy(w, request)
	if err != nil {
		return
	}
	defer conn.Close()
	reassembler := packet.NewReassembler()
	buf := make([]byte, 65535)
	for {
		n, err := conn.ReadPacket(buf, true)
		if err != nil {
			return
		}
		whole, ok := reassembler.Add(buf[:n])
		if !ok {
			continue
		}
		for _, fragment := range packet.Fragment(whole, warp.MTU) {
			if _, err := conn.WritePacket(fragment); err != nil {
				return
			}
		}
	}
}

func (s *Server) handleH2(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodConnect {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Capsule-Protocol", "?1")
	w.WriteHeader(http.StatusOK)
	w.(http.Flusher).Flush()
	reassembler := packet.NewReassembler()
	for {
		capsuleType, payload, err := readCapsule(r.Body)
		if err != nil {
			return
		}
		if capsuleType != 0 {
			continue
		}
		whole, ok := reassembler.Add(payload)
		if !ok {
			continue
		}
		for _, fragment := range packet.Fragment(whole, warp.MTU) {
			if err := writeCapsule(w, fragment); err != nil {
				return
			}
		}
	}
}

func readCapsule(r io.Reader) (uint64, []byte, error) {
	reader := quicvarint.NewReader(r)
	capsuleType, err := quicvarint.Read(reader)
	if err != nil {
		return 0, nil, err
	}
	length, err := quicvarint.Read(reader)
	if err != nil {
		return 0, nil, err
	}
	if length > 65535 {
		return 0, nil, errors.New("capsule too large")
	}
	payload := make([]byte, int(length))
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	return capsuleType, payload, nil
}

func writeCapsule(w io.Writer, payload []byte) error {
	frame := quicvarint.Append(nil, 0)
	frame = quicvarint.Append(frame, uint64(len(payload)))
	frame = append(frame, payload...)
	if _, err := w.Write(frame); err != nil {
		return err
	}
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
	return nil
}
