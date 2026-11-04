package rtctest

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"time"
)

const magicCookie = 0x2112a442

type Binding struct {
	Transaction [12]byte
	Mapped      netip.AddrPort
}

func NewBindingRequest() ([]byte, [12]byte, error) {
	var tx [12]byte
	if _, err := rand.Read(tx[:]); err != nil {
		return nil, tx, err
	}
	message := make([]byte, 20)
	binary.BigEndian.PutUint16(message[0:2], 0x0001)
	binary.BigEndian.PutUint16(message[2:4], 0)
	binary.BigEndian.PutUint32(message[4:8], magicCookie)
	copy(message[8:20], tx[:])
	return message, tx, nil
}

func ServeBinding(conn *net.UDPConn) error {
	buf := make([]byte, 1500)
	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return err
		}
		if n < 20 || binary.BigEndian.Uint16(buf[0:2]) != 0x0001 || binary.BigEndian.Uint32(buf[4:8]) != magicCookie {
			continue
		}
		udpAddr := addr.AddrPort()
		if !udpAddr.Addr().Is4() {
			continue
		}
		response := make([]byte, 32)
		binary.BigEndian.PutUint16(response[0:2], 0x0101)
		binary.BigEndian.PutUint16(response[2:4], 12)
		binary.BigEndian.PutUint32(response[4:8], magicCookie)
		copy(response[8:20], buf[8:20])
		binary.BigEndian.PutUint16(response[20:22], 0x0020)
		binary.BigEndian.PutUint16(response[22:24], 8)
		response[24] = 0
		response[25] = 1
		binary.BigEndian.PutUint16(response[26:28], udpAddr.Port()^0x2112)
		address := udpAddr.Addr().As4()
		cookie := []byte{0x21, 0x12, 0xa4, 0x42}
		for i := 0; i < 4; i++ {
			response[28+i] = address[i] ^ cookie[i]
		}
		if _, err := conn.WriteToUDP(response, addr); err != nil {
			return err
		}
	}
}

func ParseBindingSuccess(message []byte, tx [12]byte) (netip.AddrPort, error) {
	var zero netip.AddrPort
	if len(message) < 20 || binary.BigEndian.Uint16(message[0:2]) != 0x0101 || binary.BigEndian.Uint32(message[4:8]) != magicCookie {
		return zero, errors.New("not a STUN binding success")
	}
	if string(message[8:20]) != string(tx[:]) {
		return zero, errors.New("STUN transaction mismatch")
	}
	length := int(binary.BigEndian.Uint16(message[2:4]))
	if 20+length > len(message) {
		return zero, errors.New("short STUN attributes")
	}
	attributes := message[20 : 20+length]
	for len(attributes) >= 4 {
		kind := binary.BigEndian.Uint16(attributes[0:2])
		size := int(binary.BigEndian.Uint16(attributes[2:4]))
		if 4+size > len(attributes) {
			return zero, errors.New("short STUN attribute")
		}
		value := attributes[4 : 4+size]
		if kind == 0x0020 && size >= 8 && value[1] == 1 {
			port := binary.BigEndian.Uint16(value[2:4]) ^ 0x2112
			var address [4]byte
			cookie := []byte{0x21, 0x12, 0xa4, 0x42}
			for i := 0; i < 4; i++ {
				address[i] = value[4+i] ^ cookie[i]
			}
			return netip.AddrPortFrom(netip.AddrFrom4(address), port), nil
		}
		padded := (size + 3) & ^3
		if padded > len(attributes) {
			return zero, errors.New("invalid STUN padding")
		}
		attributes = attributes[padded:]
	}
	return zero, errors.New("STUN binding success missing XOR-MAPPED-ADDRESS")
}

func ExchangeBinding(conn *net.UDPConn, timeout time.Duration) (netip.AddrPort, error) {
	request, tx, err := NewBindingRequest()
	if err != nil {
		var zero netip.AddrPort
		return zero, err
	}
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		var zero netip.AddrPort
		return zero, err
	}
	if _, err := conn.Write(request); err != nil {
		var zero netip.AddrPort
		return zero, err
	}
	buf := make([]byte, 1500)
	n, err := conn.Read(buf)
	if err != nil {
		var zero netip.AddrPort
		return zero, err
	}
	return ParseBindingSuccess(buf[:n], tx)
}
