package socks

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

// Dialer opens a connection to host:port for one client request.
type Dialer func(host string, port int) (io.ReadWriteCloser, error)

// Server is a minimal SOCKS5 proxy that every application on the machine can
// point at while the tunnel carries the traffic.
type Server struct {
	listener net.Listener
	dial     Dialer
	closing  sync.Once
	Bytes    func(down, up int64)
}

func Listen(address string, dial Dialer) (*Server, error) {
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, err
	}
	server := &Server{listener: listener, dial: dial}
	go server.accept()
	return server, nil
}

func (s *Server) Port() int {
	if address, ok := s.listener.Addr().(*net.TCPAddr); ok {
		return address.Port
	}
	return 0
}

func (s *Server) Close() error {
	var err error
	s.closing.Do(func() { err = s.listener.Close() })
	return err
}

func (s *Server) accept() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		go s.handle(conn)
	}
}

func (s *Server) handle(conn net.Conn) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return
	}
	if header[0] != 5 {
		return
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}
	request := make([]byte, 4)
	if _, err := io.ReadFull(conn, request); err != nil {
		return
	}
	if request[1] != 1 {
		conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	host, err := readHost(conn, request[3])
	if err != nil {
		return
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		return
	}
	port := int(binary.BigEndian.Uint16(portBytes))
	remote, err := s.dial(host, port)
	if err != nil {
		conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	conn.SetDeadline(time.Time{})
	if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	pump(conn, remote, s.Bytes)
}

func readHost(conn net.Conn, kind byte) (string, error) {
	switch kind {
	case 1:
		raw := make([]byte, 4)
		if _, err := io.ReadFull(conn, raw); err != nil {
			return "", err
		}
		return net.IP(raw).String(), nil
	case 3:
		length := make([]byte, 1)
		if _, err := io.ReadFull(conn, length); err != nil {
			return "", err
		}
		name := make([]byte, int(length[0]))
		if _, err := io.ReadFull(conn, name); err != nil {
			return "", err
		}
		return string(name), nil
	case 4:
		raw := make([]byte, 16)
		if _, err := io.ReadFull(conn, raw); err != nil {
			return "", err
		}
		return net.IP(raw).String(), nil
	}
	return "", errors.New("unknown address type")
}

func pump(local net.Conn, remote io.ReadWriteCloser, count func(down, up int64)) {
	done := make(chan struct{}, 2)
	go func() {
		buffer := make([]byte, 32*1024)
		for {
			n, err := local.Read(buffer)
			if n > 0 {
				if _, werr := remote.Write(buffer[:n]); werr != nil {
					break
				}
				if count != nil {
					count(0, int64(n))
				}
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	go func() {
		buffer := make([]byte, 32*1024)
		for {
			n, err := remote.Read(buffer)
			if n > 0 {
				if _, werr := local.Write(buffer[:n]); werr != nil {
					break
				}
				if count != nil {
					count(int64(n), 0)
				}
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	<-done
	local.Close()
	remote.Close()
}

// Address renders host:port for logging and dialing.
func Address(host string, port int) string {
	return net.JoinHostPort(host, strconv.Itoa(port))
}
