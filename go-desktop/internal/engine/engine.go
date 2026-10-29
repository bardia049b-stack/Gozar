package engine

import (
	"errors"
	"io"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/bardia049b-stack/gozar/go-desktop/internal/dnsproxy"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/tundev"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const mtu = 1500

type Dialer func(host string, port int) (io.ReadWriteCloser, error)

type Engine struct {
	stack  *stack.Stack
	device *tundev.Device
	config tundev.Config
	dial   Dialer
	dns    *dnsproxy.Resolver
	down   atomic.Int64
	up     atomic.Int64
	once   sync.Once
}

func Start(proxyHost string, dial Dialer, protect []netip.Addr) (*Engine, error) {
	if !tundev.Privileged() {
		return nil, errors.New("the tunnel needs administrator rights")
	}
	device, err := tundev.Open(mtu)
	if err != nil {
		return nil, err
	}
	created := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := created.CreateNIC(1, device.Endpoint()); err != nil {
		device.Close()
		return nil, errors.New(err.String())
	}
	if err := created.SetPromiscuousMode(1, true); err != nil {
		device.Close()
		created.Close()
		return nil, errors.New(err.String())
	}
	if err := created.SetSpoofing(1, true); err != nil {
		device.Close()
		created.Close()
		return nil, errors.New(err.String())
	}
	address := tcpip.AddrFrom4([4]byte{10, 111, 0, 2})
	if err := created.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: address.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		device.Close()
		return nil, errors.New(err.String())
	}
	created.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
	})

	engine := &Engine{stack: created, device: device, dial: dial}
	engine.dns = &dnsproxy.Resolver{Dialer: dial, Server: dnsproxy.Address}

	tcpForwarder := tcp.NewForwarder(created, 0, 4096, engine.handleTCP)
	created.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(created, func(request *udp.ForwarderRequest) {
		engine.handleUDP(request)
	})
	created.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)

	keep := append([]netip.Addr{}, protect...)
	engine.config = tundev.Config{Interface: device.Name(), DNS: dnsproxy.Address, Keep: keep}
	if err := tundev.Configure(&engine.config); err != nil {
		device.Close()
		created.Close()
		return nil, err
	}
	_ = proxyHost
	return engine, nil
}

func (e *Engine) handleTCP(request *tcp.ForwarderRequest) {
	go func() {
		id := request.ID()
		host := id.LocalAddress.String()
		port := int(id.LocalPort)
		if host == dnsproxy.Address && port == 53 {
			queue := &waiter.Queue{}
			endpoint, terr := request.CreateEndpoint(queue)
			if terr != nil {
				request.Complete(true)
				return
			}
			request.Complete(false)
			e.serveDNS(gonet.NewTCPConn(queue, endpoint))
			return
		}
		remote, err := e.dial(host, port)
		if err != nil {
			request.Complete(true)
			return
		}
		queue := &waiter.Queue{}
		endpoint, terr := request.CreateEndpoint(queue)
		if terr != nil {
			remote.Close()
			request.Complete(true)
			return
		}
		request.Complete(false)
		e.relay(gonet.NewTCPConn(queue, endpoint), remote)
	}()
}

func (e *Engine) handleUDP(request *udp.ForwarderRequest) bool {
	id := request.ID()
	if id.LocalAddress.String() != dnsproxy.Address || id.LocalPort != 53 {
		return false
	}
	go func() {
		queue := &waiter.Queue{}
		endpoint, terr := request.CreateEndpoint(queue)
		if terr != nil {
			return
		}
		conn := gonet.NewUDPConn(queue, endpoint)
		defer conn.Close()
		buffer := make([]byte, 4096)
		for {
			count, err := conn.Read(buffer)
			if err != nil || count == 0 {
				return
			}
			answer, err := e.dns.Resolve(append([]byte{}, buffer[:count]...))
			if err != nil {
				continue
			}
			e.down.Add(int64(len(answer)))
			conn.Write(answer)
		}
	}()
	return true
}

func (e *Engine) relay(local *gonet.TCPConn, remote io.ReadWriteCloser) {
	done := make(chan struct{}, 2)
	go func() {
		buffer := make([]byte, 32*1024)
		for {
			count, err := local.Read(buffer)
			if count > 0 {
				if _, werr := remote.Write(buffer[:count]); werr != nil {
					break
				}
				e.up.Add(int64(count))
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
			count, err := remote.Read(buffer)
			if count > 0 {
				if _, werr := local.Write(buffer[:count]); werr != nil {
					break
				}
				e.down.Add(int64(count))
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

func (e *Engine) Counters() (int64, int64) {
	return e.down.Load(), e.up.Load()
}

func (e *Engine) Device() string {
	return e.config.Interface
}

func (e *Engine) Stop() {
	e.once.Do(func() {
		tundev.Restore(&e.config)
		if e.device != nil {
			e.device.Close()
		}
		if e.stack != nil {
			e.stack.Close()
			e.stack.Wait()
		}
		time.Sleep(200 * time.Millisecond)
	})
}

func (e *Engine) serveDNS(local *gonet.TCPConn) {
	defer local.Close()
	for {
		size := make([]byte, 2)
		if _, err := io.ReadFull(local, size); err != nil {
			return
		}
		length := int(size[0])<<8 | int(size[1])
		if length == 0 || length > 4096 {
			return
		}
		query := make([]byte, length)
		if _, err := io.ReadFull(local, query); err != nil {
			return
		}
		answer, err := e.dns.Resolve(query)
		if err != nil {
			return
		}
		framed := make([]byte, 2+len(answer))
		framed[0] = byte(len(answer) >> 8)
		framed[1] = byte(len(answer))
		copy(framed[2:], answer)
		if _, err := local.Write(framed); err != nil {
			return
		}
		e.down.Add(int64(len(answer)))
	}
}
