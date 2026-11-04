package rotation

import (
	"context"
	"errors"
	"sync"
	"time"
)

type Account struct {
	ID        string
	Remaining int64
	Limit     int64
}

type Switcher interface {
	Accounts() []Account
	Active() string
	Switch(id string) error
}

type Worker struct {
	Switcher Switcher
	Interval time.Duration
	mu       sync.Mutex
	last     time.Time
}

func NewWorker(s Switcher) *Worker {
	return &Worker{Switcher: s, Interval: 30 * time.Second}
}

func (w *Worker) Run(ctx context.Context) {
	interval := w.Interval
	if interval <= 0 {
		interval = 30 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.Check()
		}
	}
}

func (w *Worker) Check() {
	w.mu.Lock()
	if time.Since(w.last) < 5*time.Second {
		w.mu.Unlock()
		return
	}
	w.last = time.Now()
	w.mu.Unlock()
	accounts := w.Switcher.Accounts()
	active := w.Switcher.Active()
	for _, account := range accounts {
		if account.ID == active && needsRotation(account) {
			w.rotate(accounts, active)
			return
		}
	}
}

func (w *Worker) OnError(status int) {
	if status == 429 || status == 402 {
		w.rotate(w.Switcher.Accounts(), w.Switcher.Active())
	}
}

func needsRotation(account Account) bool {
	if account.Limit <= 0 {
		return false
	}
	return float64(account.Limit-account.Remaining)/float64(account.Limit) >= 0.90
}

func (w *Worker) rotate(accounts []Account, active string) {
	for _, account := range accounts {
		if account.ID == active || account.Remaining <= 0 {
			continue
		}
		if err := w.Switcher.Switch(account.ID); err == nil {
			return
		}
	}
}

var ErrNoAccount = errors.New("no alternate account available")
