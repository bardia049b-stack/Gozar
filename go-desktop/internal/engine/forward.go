package engine

import (
	"net"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

type forwardAdapter struct {
	request *udp.ForwarderRequest
	id      stack.TransportEndpointID
}

func (f forwardAdapter) host() string { return f.id.LocalAddress.String() }

func (f forwardAdapter) port() int { return int(f.id.LocalPort) }

func (f forwardAdapter) accept() (net.PacketConn, error) {
	queue := &waiter.Queue{}
	endpoint, failure := f.request.CreateEndpoint(queue)
	if failure != nil {
		return nil, &forwardError{failure.String()}
	}
	return gonet.NewUDPConn(queue, endpoint), nil
}

func (f forwardAdapter) reject() {}

type forwardError struct{ text string }

func (e *forwardError) Error() string { return e.text }
