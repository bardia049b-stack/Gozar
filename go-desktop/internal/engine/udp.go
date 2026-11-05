package engine

import (
	"context"
	"errors"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/packet"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// PacketConn is a datagram tunnel. *connectip.Conn (WARP MASQUE) implements it.
// WritePacket returns the ICMP error the proxy generated, if any.
type PacketConn interface {
	WritePacket([]byte) ([]byte, error)
	ReadPacket([]byte, bool) (int, error)
	Close() error
}

// PacketDialer opens the datagram tunnel. It is called again after a failure so
// a dropped Wi-Fi link or a dead WARP endpoint recovers on the next packet.
type PacketDialer func(context.Context) (PacketConn, error)

// udpMTU is the WARP inner MTU; larger packets are fragmented before sending.
const udpMTU = 1180

// udpFlowIdle is how long an idle UDP mapping lives. UDP has no close signal,
// so the relay expires mappings like a NAT does; without this a client that
// opens many short-lived sockets (STUN, DNS, game servers) would keep every
// endpoint alive forever.
var udpFlowIdle = 30 * time.Second

type udpFlow struct {
	conn       *gonet.UDPConn
	server     netip.Addr
	serverPort uint16
	clientPort uint16
}

func flowKey(server netip.Addr, serverPort, clientPort uint16) string {
	return server.String() + "|" + strconv.Itoa(int(serverPort)) + "|" + strconv.Itoa(int(clientPort))
}

type udpRelay struct {
	dial   PacketDialer
	engine *Engine

	mu         sync.Mutex
	conn       PacketConn
	flows      map[string]*udpFlow
	reassembly *packet.Reassembler
	closed     bool
}

func newUDPRelay(engine *Engine, dial PacketDialer) *udpRelay {
	return &udpRelay{dial: dial, engine: engine, flows: make(map[string]*udpFlow), reassembly: packet.NewReassembler()}
}

// session returns the live datagram tunnel, dialling it on first use and after
// any failure. The reader goroutine is started once per connection.
func (r *udpRelay) session() (PacketConn, error) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil, errors.New("the UDP relay is closed")
	}
	if r.conn != nil {
		conn := r.conn
		r.mu.Unlock()
		return conn, nil
	}
	r.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	conn, err := r.dial(ctx)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		conn.Close()
		return nil, errors.New("the UDP relay is closed")
	}
	if r.conn != nil {
		existing := r.conn
		r.mu.Unlock()
		conn.Close()
		return existing, nil
	}
	r.conn = conn
	r.reassembly = packet.NewReassembler()
	r.mu.Unlock()
	go r.readLoop(conn)
	return conn, nil
}

func (r *udpRelay) drop(conn PacketConn) {
	r.mu.Lock()
	if r.conn == conn {
		r.conn = nil
	}
	r.mu.Unlock()
	conn.Close()
}

func (r *udpRelay) readLoop(conn PacketConn) {
	buffer := make([]byte, 65535)
	for {
		count, err := conn.ReadPacket(buffer, true)
		if err != nil {
			r.drop(conn)
			return
		}
		whole, ok := r.reassembly.Add(buffer[:count])
		if !ok {
			continue
		}
		src, _, srcPort, dstPort, payload, err := packet.ParseUDP(whole)
		if err != nil {
			continue
		}
		key := flowKey(src, srcPort, dstPort)
		r.mu.Lock()
		flow := r.flows[key]
		r.mu.Unlock()
		if flow == nil {
			continue
		}
		if _, err := flow.conn.Write(payload); err != nil {
			r.remove(key)
			flow.conn.Close()
			continue
		}
		r.engine.down.Add(int64(len(payload)))
	}
}

func (r *udpRelay) add(key string, flow *udpFlow) {
	r.mu.Lock()
	r.flows[key] = flow
	r.mu.Unlock()
}

func (r *udpRelay) remove(key string) {
	r.mu.Lock()
	delete(r.flows, key)
	r.mu.Unlock()
}

func (r *udpRelay) send(flow *udpFlow, payload []byte) error {
	conn, err := r.session()
	if err != nil {
		return err
	}
	whole := packet.BuildUDP(tunnelAddress, flow.server, flow.clientPort, flow.serverPort, payload)
	if whole == nil {
		return errors.New("the UDP payload is too large")
	}
	for _, fragment := range packet.Fragment(whole, udpMTU) {
		if _, err := conn.WritePacket(fragment); err != nil {
			r.drop(conn)
			return err
		}
	}
	r.engine.up.Add(int64(len(payload)))
	return nil
}

func (r *udpRelay) close() {
	r.mu.Lock()
	r.closed = true
	conn := r.conn
	r.conn = nil
	flows := make([]*udpFlow, 0, len(r.flows))
	for _, flow := range r.flows {
		flows = append(flows, flow)
	}
	r.flows = make(map[string]*udpFlow)
	r.mu.Unlock()
	for _, flow := range flows {
		flow.conn.Close()
	}
	if conn != nil {
		conn.Close()
	}
}

// serveUDP relays one inside-tunnel UDP flow over the datagram tunnel. This is
// what makes WebRTC/STUN and game ports (3074, 3478, 27015, …) work.
func (e *Engine) serveUDP(request *udp.ForwarderRequest) {
	id := request.ID()
	queue := &waiter.Queue{}
	endpoint, terr := request.CreateEndpoint(queue)
	if terr != nil {
		return
	}
	conn := gonet.NewUDPConn(queue, endpoint)
	server, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		conn.Close()
		return
	}
	flow := &udpFlow{conn: conn, server: server.Unmap(), serverPort: id.LocalPort, clientPort: id.RemotePort}
	key := flowKey(flow.server, flow.serverPort, flow.clientPort)
	e.packets.add(key, flow)
	defer func() {
		e.packets.remove(key)
		conn.Close()
	}()
	buffer := make([]byte, 65535)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(udpFlowIdle))
		count, err := conn.Read(buffer)
		if err != nil || count == 0 {
			return
		}
		if err := e.packets.send(flow, buffer[:count]); err != nil {
			return
		}
	}
}
