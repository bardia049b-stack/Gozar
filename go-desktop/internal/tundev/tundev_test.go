package tundev

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"sync"
	"testing"
	"time"

	wtun "golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type fakeDevice struct {
	input       chan []byte
	output      chan []byte
	readOffset  chan int
	writeOffset chan int
	done        chan struct{}
	once        sync.Once
	writeErr    error
}

func (f *fakeDevice) File() *os.File            { return nil }
func (f *fakeDevice) MTU() (int, error)         { return 1500, nil }
func (f *fakeDevice) Name() (string, error)     { return "fake", nil }
func (f *fakeDevice) Events() <-chan wtun.Event { return nil }
func (f *fakeDevice) BatchSize() int            { return 1 }
func (f *fakeDevice) Close() error              { f.once.Do(func() { close(f.done) }); return nil }
func (f *fakeDevice) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case data := <-f.input:
		f.readOffset <- offset
		sizes[0] = copy(bufs[0][offset:], data)
		return 1, nil
	case <-f.done:
		return 0, io.EOF
	}
}
func (f *fakeDevice) Write(bufs [][]byte, offset int) (int, error) {
	f.writeOffset <- offset
	if f.writeErr != nil {
		return 0, f.writeErr
	}
	f.output <- append([]byte(nil), bufs[0][offset:]...)
	return 1, nil
}

type receivedPacket struct {
	protocol tcpip.NetworkProtocolNumber
	data     []byte
}
type fakeDispatcher struct{ received chan receivedPacket }

func (d *fakeDispatcher) DeliverNetworkPacket(protocol tcpip.NetworkProtocolNumber, packet *stack.PacketBuffer) {
	view := packet.ToView()
	d.received <- receivedPacket{protocol, append([]byte(nil), view.AsSlice()...)}
	view.Release()
}
func (*fakeDispatcher) DeliverLinkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer) {}

func startFakeDevice(writeErr error) (*Device, *fakeDevice, *fakeDispatcher) {
	link := &fakeDevice{input: make(chan []byte, 1), output: make(chan []byte, 1), readOffset: make(chan int, 1), writeOffset: make(chan int, 1), done: make(chan struct{}), writeErr: writeErr}
	endpoint := channel.New(4, 1500, "")
	dispatcher := &fakeDispatcher{received: make(chan receivedPacket, 1)}
	endpoint.Attach(dispatcher)
	ctx, cancel := context.WithCancel(context.Background())
	d := &Device{link: link, end: endpoint, name: "fake", mtu: 1500, ctx: ctx, stop: cancel, failures: make(chan error, 1)}
	d.pump.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d, link, dispatcher
}

func sendPacket(t *testing.T, d *Device, data []byte) {
	t.Helper()
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
	var list stack.PacketBufferList
	list.PushBack(packet)
	count, err := d.end.WritePackets(list)
	packet.DecRef()
	if err != nil || count != 1 {
		t.Fatalf("queued %d packets: %v", count, err)
	}
}

func TestPacketHeadroomAndBidirectionalDelivery(t *testing.T) {
	d, link, dispatcher := startFakeDevice(nil)
	defer d.Close()
	incoming := []byte{0x45, 0, 1, 2, 3}
	link.input <- incoming
	select {
	case got := <-dispatcher.received:
		if got.protocol != header.IPv4ProtocolNumber || !bytes.Equal(got.data, incoming) {
			t.Fatalf("bad inbound packet %+v", got)
		}
	case <-time.After(time.Second):
		t.Fatal("inbound packet was not delivered")
	}
	if offset := <-link.readOffset; offset < 16 {
		t.Fatalf("unsafe read offset %d", offset)
	}
	outbound := []byte{0x45, 9, 8, 7, 6}
	sendPacket(t, d, outbound)
	select {
	case got := <-link.output:
		if !bytes.Equal(got, outbound) {
			t.Fatalf("bad outbound packet %x", got)
		}
	case <-time.After(time.Second):
		t.Fatal("outbound packet was not delivered")
	}
	if offset := <-link.writeOffset; offset < 16 {
		t.Fatalf("unsafe write offset %d", offset)
	}
}

func TestDeviceWriteErrorIsReported(t *testing.T) {
	expected := errors.New("test device failure")
	d, _, _ := startFakeDevice(expected)
	defer d.Close()
	sendPacket(t, d, []byte{0x45, 1, 2, 3})
	select {
	case err := <-d.Failures():
		if !errors.Is(err, expected) {
			t.Fatalf("unexpected failure %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("write error was hidden")
	}
}
