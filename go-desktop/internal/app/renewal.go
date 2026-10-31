package app

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func renewalDelay(token string, now time.Time) time.Duration {
	expiry := now.Add(4 * time.Minute)
	parts := strings.Split(token, ".")
	if len(parts) >= 2 {
		if data, err := base64.RawURLEncoding.DecodeString(parts[1]); err == nil {
			var claims struct {
				Expires int64 `json:"exp"`
			}
			if json.Unmarshal(data, &claims) == nil && claims.Expires > 0 && claims.Expires <= 4102444800 {
				expiry = time.Unix(claims.Expires, 0)
			}
		}
	}
	delay := expiry.Sub(now) - 2*time.Minute
	if delay < 15*time.Second {
		return 15 * time.Second
	}
	if delay > 4*time.Minute {
		return 4 * time.Minute
	}
	return delay
}

func (a *App) currentPass() (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.pass == nil || a.pass.Token == "" {
		return "", errors.New("no Firefox proxy pass")
	}
	return a.pass.Token, nil
}

func (a *App) startRenewal() {
	ctx, cancel := context.WithCancel(context.Background())
	a.mu.Lock()
	if !a.live {
		a.mu.Unlock()
		cancel()
		return
	}
	old := a.renewCancel
	a.renewCancel = cancel
	a.mu.Unlock()
	if old != nil {
		old()
	}
	go a.renewPass(ctx)
}

func (a *App) renewPass(ctx context.Context) {
	var retry time.Duration
	for {
		token, err := a.currentPass()
		if err != nil {
			return
		}
		delay := renewalDelay(token, time.Now())
		if retry > 0 {
			delay = retry
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		access, err := a.access()
		if err == nil {
			var passErr error
			pass, passErr := a.requestPass(access.token)
			err = passErr
			if err == nil {
				err = a.saveRenewedUsage(ctx, pass, access)
			}
		}
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			if retry == 0 {
				retry = 15 * time.Second
			} else {
				retry *= 2
			}
			if retry > time.Minute {
				retry = time.Minute
			}
		} else {
			retry = 0
		}
	}
}

func (a *App) saveRenewedUsage(ctx context.Context, pass *fxa.Pass, access accountAccess) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if ctx.Err() != nil || !a.live {
		return context.Canceled
	}
	return a.saveUsageLocked(pass, access)
}
