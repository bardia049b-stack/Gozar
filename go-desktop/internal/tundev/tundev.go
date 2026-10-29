package tundev

import (
	"context"
	"errors"
	"io"
	"runtime"
	"sync"

	wtun "golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

const Name = "Gozar"

type Device struct {
	link wtun.Device
	end  *channel.Endpoint
	name string
	mtu  int
	ctx  context.Context
	stop context.CancelFunc
	pump sync.WaitGroup
}

func Open(mtu int) (*Device, error) {
	name := Name
	if runtime.GOOS == "darwin" {
		name = "utun"
	}
	created, err := wtun.CreateTUN(name, mtu)
	if err != nil {
		return nil, err
	}
	actual, err := created.Name()
	if err != nil {
		created.Close()
		return nil, err
	}
	end := channel.New(512, uint32(mtu), "")
	ctx, stop := context.WithCancel(context.Background())
	device := &Device{link: created, end: end, name: actual, mtu: mtu, ctx: ctx, stop: stop}
	device.pump.Add(2)
	go device.readLoop()
	go device.writeLoop()
	return device, nil
}

func (d *Device) Endpoint() stack.LinkEndpoint { return d.end }

func (d *Device) Name() string { return d.name }

func (d *Device) readLoop() {
	defer d.pump.Done()
	batch := d.link.BatchSize()
	bufs := make([][]byte, batch)
	sizes := make([]int, batch)
	for index := range bufs {
		bufs[index] = make([]byte, d.mtu)
	}
	for {
		count, err := d.link.Read(bufs, sizes, 0)
		if err != nil {
			if errors.Is(err, io.EOF) || d.ctx.Err() != nil {
				return
			}
			continue
		}
		for index := 0; index < count; index++ {
			packet := bufs[index][:sizes[index]]
			if len(packet) == 0 {
				continue
			}
			proto := header.IPv4ProtocolNumber
			if packet[0]>>4 == 6 {
				proto = header.IPv6ProtocolNumber
			}
			payload := buffer.MakeWithData(append([]byte{}, packet...))
			d.end.InjectInbound(proto, stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: payload}))
		}
	}
}

func (d *Device) writeLoop() {
	defer d.pump.Done()
	for {
		packet := d.end.ReadContext(d.ctx)
		if packet == nil {
			return
		}
		view := packet.ToView()
		packet.DecRef()
		data := view.AsSlice()
		if len(data) > 0 {
			d.link.Write([][]byte{append([]byte{}, data...)}, 0)
		}
		view.Release()
	}
}

func (d *Device) Close() error {
	d.stop()
	d.end.Close()
	err := d.link.Close()
	d.pump.Wait()
	return err
}

var Address = tcpip.AddrFrom4([4]byte{10, 111, 0, 2})
