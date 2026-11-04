package app

import (
	"context"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
)

// pathProbeInterval is how often an *idle* tunnel is checked. While traffic is
// flowing the counters move and no probe is sent at all, so browsing never pays
// for the watchdog.
const pathProbeInterval = 30 * time.Second

// pathFailureLimit is how many consecutive idle probes must fail before the
// session is rebuilt. One failure is usually a transient Wi-Fi blip.
const pathFailureLimit = 3

// pathWatcher is the reconnect state machine. Every side effect is injected so
// the policy can be tested without a network, an account or a TUN device.
type pathWatcher struct {
	interval  time.Duration
	limit     int
	active    func() bool
	counters  func() (int64, int64)
	probe     func() error
	reconnect func(context.Context)
	// wait is the backoff before the first rebuild; it doubles afterwards.
	wait time.Duration
}

func (w *pathWatcher) run(ctx context.Context) {
	if w.interval <= 0 {
		w.interval = pathProbeInterval
	}
	if w.limit <= 0 {
		w.limit = pathFailureLimit
	}
	if w.wait <= 0 {
		w.wait = 15 * time.Second
	}
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()
	var lastDown, lastUp int64
	failures := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		if w.active != nil && !w.active() {
			return
		}
		if w.counters != nil {
			down, up := w.counters()
			if down != lastDown || up != lastUp {
				// Real traffic moved: the path is alive, no probe needed.
				lastDown, lastUp = down, up
				failures = 0
				continue
			}
		}
		if w.probe == nil {
			continue
		}
		if err := w.probe(); err == nil {
			failures = 0
			continue
		}
		failures++
		if failures < w.limit {
			continue
		}
		if w.reconnect != nil {
			w.reconnect(ctx)
		}
		return
	}
}

// probePath opens a real CONNECT through the live proxy and closes it at once.
// It is the cheapest honest end-to-end check: it exercises the same credentials
// and the same transport the user's traffic uses.
func (a *App) probePath() error {
	a.mu.Lock()
	transport := a.transport
	a.mu.Unlock()
	if transport == nil {
		return context.Canceled
	}
	pass, err := a.currentPass()
	if err != nil {
		return err
	}
	stream, err := transport.Open("www.gstatic.com", 80, pass, 8*time.Second)
	if err != nil {
		return err
	}
	return stream.Close()
}

// watchPath starts the idle watchdog for one tunnel session.
func (a *App) watchPath(ctx context.Context, current *engine.Engine) {
	watcher := &pathWatcher{
		active: func() bool {
			a.mu.Lock()
			defer a.mu.Unlock()
			return a.live && a.engine == current
		},
		counters: current.Counters,
		probe:    a.probePath,
		reconnect: func(ctx context.Context) {
			a.rebuildPath(ctx, current)
		},
	}
	go watcher.run(ctx)
}

// rebuildPath tears the dead session down and reconnects with a growing delay.
// It gives up after eight attempts (about half an hour) and leaves the reason
// in the app state instead of retrying forever.
func (a *App) rebuildPath(ctx context.Context, current *engine.Engine) {
	delay := 15 * time.Second
	for attempt := 0; attempt < 8; attempt++ {
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		a.connectionMu.Lock()
		a.mu.Lock()
		still := a.engine == current && a.reconnectWanted
		a.mu.Unlock()
		if !still {
			a.connectionMu.Unlock()
			return
		}
		a.teardown()
		a.stage = "اتصال مجدد…"
		a.connectionMu.Unlock()
		if err := a.Connect(); err == nil {
			return
		}
		delay *= 2
		if delay > 5*time.Minute {
			delay = 5 * time.Minute
		}
	}
	a.mu.Lock()
	a.fault = "The connection kept dropping; automatic reconnect gave up after eight attempts."
	a.stage = ""
	a.mu.Unlock()
}
