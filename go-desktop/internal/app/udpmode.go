package app

import (
	"context"
	"errors"
	"io"
	"path/filepath"
	"sync"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

type gamePath struct {
	mu       sync.Mutex
	stack    *warp.Stack
	release  func()
	failures int
	retryAt  time.Time
	closed   bool
}

func (g *gamePath) close() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.closed = true
	g.resetLocked()
}

func (g *gamePath) resetLocked() {
	if g.stack != nil {
		g.stack.Close()
		g.stack = nil
	}
	if g.release != nil {
		g.release()
		g.release = nil
	}
}

func (g *gamePath) noteFailure() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.resetLocked()
	if g.failures < 5 {
		g.failures++
	}
	delay := time.Second << (g.failures - 1)
	if delay > 30*time.Second {
		delay = 30 * time.Second
	}
	g.retryAt = time.Now().Add(delay)
}

func (g *gamePath) noteSuccess() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.failures = 0
	g.retryAt = time.Time{}
}

func (g *gamePath) datagram(a *App, host string, port int) (engine.Datagram, error) {
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return nil, engine.ErrUDPDenied
	}
	if g.stack != nil && !g.stack.Alive() {
		g.resetLocked()
		if g.failures < 5 {
			g.failures++
		}
		g.retryAt = time.Now().Add(time.Second << (g.failures - 1))
	}
	if g.stack == nil && time.Now().Before(g.retryAt) {
		g.mu.Unlock()
		return nil, engine.ErrUDPDenied
	}
	g.mu.Unlock()

	created, err := a.openGamePath()

	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		if created != nil {
			created.stack.Close()
			created.release()
		}
		return nil, engine.ErrUDPDenied
	}
	if err != nil {
		g.mu.Unlock()
		g.noteFailure()
		return nil, engine.ErrUDPDenied
	}
	if g.stack != nil {

		g.mu.Unlock()
		created.stack.Close()
		created.release()
	} else {
		g.stack, g.release = created.stack, created.release
		g.failures, g.retryAt = 0, time.Time{}
		g.mu.Unlock()
	}

	g.mu.Lock()
	current := g.stack
	g.mu.Unlock()
	if current == nil {
		return nil, engine.ErrUDPDenied
	}
	flow, err := current.Datagram(host, port)
	if err != nil {
		g.noteFailure()
		return nil, engine.ErrUDPDenied
	}
	g.noteSuccess()
	return flow, nil
}

type gameTunnel struct {
	stack   *warp.Stack
	release func()
}

func (a *App) openGamePath() (*gameTunnel, error) {
	a.mu.Lock()
	via := a.cfg.WARPVia
	chain := append([]string(nil), a.cfg.Chain...)
	mtu := a.cfg.WARPMTU
	a.mu.Unlock()
	if mtu <= 0 || mtu > warpMTU {
		mtu = warpMTU
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	device, err := warp.DeviceFor(ctx, filepath.Join(config.Dir(), "warp.json"))
	if err != nil {
		return nil, err
	}
	var carrier warp.Carrier
	var release func()
	if via == "relay" || len(chain) > 0 {
		target, err := a.relayTarget()
		if err != nil {
			return nil, err
		}
		manager := tunnel.NewManager(target)
		carrier = warp.Carrier{Stream: func(host string, port int) (io.ReadWriteCloser, error) {
			pass, err := a.currentPass()
			if err != nil {
				return nil, err
			}
			return manager.Open(host, port, pass, 20*time.Second)
		}}
		release = func() { manager.Close() }
	} else {
		socket, err := warp.Listen(device.Endpoint)
		if err != nil {
			return nil, err
		}
		carrier = warp.Carrier{Packet: socket}
		release = func() { socket.Close() }
	}
	stack, err := warp.OpenStack(ctx, device, carrier, mtu)
	if err != nil {
		release()
		return nil, err
	}
	return &gameTunnel{stack: stack, release: release}, nil
}

func (a *App) SetUDPMode(mode string) error {
	normalized := config.NormalizeUDPMode(mode)
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the UDP mode")
	}
	next := a.cfg
	next.UDPMode = normalized
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SetBatterySaver(enabled bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the battery saver")
	}
	next := a.cfg
	next.BatterySaver = enabled
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}
