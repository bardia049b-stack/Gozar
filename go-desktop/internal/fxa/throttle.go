package fxa

import (
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type throttle struct {
	mu       sync.Mutex
	gap      time.Duration
	cap      time.Duration
	attempts int
	sleep    func(time.Duration)
	now      func() time.Time
	jitter   func(time.Duration) time.Duration

	lastStart    time.Time
	blockedUntil time.Time
}

func newThrottle() *throttle {
	return &throttle{
		gap:      750 * time.Millisecond,
		cap:      time.Minute,
		attempts: 5,
		sleep:    time.Sleep,
		now:      time.Now,
		jitter: func(bound time.Duration) time.Duration {
			if bound <= 0 {
				return 0
			}
			return time.Duration(rand.Int63n(int64(bound)))
		},
	}
}

func (t *throttle) run(task func() (*Reply, error)) (*Reply, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	for round := 0; ; {
		if wait := t.startsAt().Sub(t.now()); wait > 0 {
			t.sleep(wait)
		}
		t.lastStart = t.now()
		reply, err := task()
		if err != nil || reply == nil || !retryableStatus(reply.Code) {
			t.blockedUntil = time.Time{}
			return reply, err
		}
		round++
		if round >= t.attempts {
			return reply, err
		}
		delay := t.backoff(round, reply)
		t.blockedUntil = t.now().Add(delay)
		t.sleep(delay)
	}
}

func (t *throttle) startsAt() time.Time {
	gap := t.lastStart.Add(t.gap)
	if t.blockedUntil.After(gap) {
		return t.blockedUntil
	}
	return gap
}

func (t *throttle) backoff(round int, reply *Reply) time.Duration {
	exponent := round - 1
	if exponent > 16 {
		exponent = 16
	}
	exponential := time.Second << exponent
	if exponential > t.cap || exponential <= 0 {
		exponential = t.cap
	}
	delay := retryAfter(reply, t.now())
	if exponential > delay {
		delay = exponential
	}
	if delay > t.cap {
		delay = t.cap
	}
	return delay + t.jitter(400*time.Millisecond)
}

func (t *throttle) cooldown(d time.Duration) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if until := t.now().Add(d); until.After(t.blockedUntil) {
		t.blockedUntil = until
	}
}

func retryableStatus(code int) bool {
	return code == http.StatusTooManyRequests || code == http.StatusServiceUnavailable
}

func retryAfter(reply *Reply, now time.Time) time.Duration {
	if reply == nil || reply.Headers == nil {
		return 0
	}
	text := strings.TrimSpace(reply.Headers.Get("Retry-After"))
	if text == "" {
		return 0
	}
	if seconds, err := strconv.ParseInt(text, 10, 64); err == nil {
		if seconds < 0 {
			return 0
		}
		if seconds > 3600 {
			seconds = 3600
		}
		return time.Duration(seconds) * time.Second
	}
	if date, err := http.ParseTime(text); err == nil {
		if delay := date.Sub(now); delay > 0 {
			return delay
		}
	}
	return 0
}
