package fxa

import (
	"errors"
	"net/http"
	"testing"
	"time"
)

// fakeClock keeps the backoff tests instant while still recording every wait.
type fakeClock struct {
	current time.Time
	waits   []time.Duration
}

func newFakeClock() *fakeClock {
	return &fakeClock{current: time.Unix(1700000000, 0)}
}

func (c *fakeClock) now() time.Time { return c.current }

func (c *fakeClock) sleep(d time.Duration) {
	c.waits = append(c.waits, d)
	c.current = c.current.Add(d)
}

func (c *fakeClock) total() time.Duration {
	var sum time.Duration
	for _, d := range c.waits {
		sum += d
	}
	return sum
}

func testThrottle(clock *fakeClock) *throttle {
	t := newThrottle()
	t.now = clock.now
	t.sleep = clock.sleep
	t.jitter = func(time.Duration) time.Duration { return 0 }
	return t
}

func statusReply(code int, header string) *Reply {
	r := &Reply{Code: code, Headers: http.Header{}}
	if header != "" {
		r.Headers.Set("Retry-After", header)
	}
	return r
}

func TestThrottleRetriesRateLimitThenSucceeds(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	got, err := limiter.run(func() (*Reply, error) {
		calls++
		if calls < 3 {
			return statusReply(http.StatusTooManyRequests, ""), nil
		}
		return statusReply(http.StatusOK, ""), nil
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Code != http.StatusOK {
		t.Fatalf("want 200 after retries, got %d", got.Code)
	}
	if calls != 3 {
		t.Fatalf("want 3 attempts, got %d", calls)
	}
	if clock.total() < 3*time.Second {
		t.Fatalf("backoff too short: %v (waits %v)", clock.total(), clock.waits)
	}
}

func TestThrottleHonoursRetryAfter(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	got, _ := limiter.run(func() (*Reply, error) {
		calls++
		if calls == 1 {
			return statusReply(http.StatusTooManyRequests, "7"), nil
		}
		return statusReply(http.StatusOK, ""), nil
	})
	if got.Code != http.StatusOK || calls != 2 {
		t.Fatalf("want one retry then 200, got code=%d calls=%d", got.Code, calls)
	}
	if clock.total() < 7*time.Second {
		t.Fatalf("Retry-After was ignored: %v", clock.total())
	}
}

func TestThrottleHonoursHTTPDateRetryAfter(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	limiter.run(func() (*Reply, error) {
		calls++
		if calls == 1 {
			return statusReply(http.StatusTooManyRequests, clock.now().Add(5*time.Second).UTC().Format(http.TimeFormat)), nil
		}
		return statusReply(http.StatusOK, ""), nil
	})
	if clock.total() < 4*time.Second {
		t.Fatalf("HTTP-date Retry-After was ignored: %v", clock.total())
	}
}

func TestThrottleCapsBackoffAndGivesUp(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	got, err := limiter.run(func() (*Reply, error) {
		calls++
		return statusReply(http.StatusTooManyRequests, "99999"), nil
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if calls != limiter.attempts {
		t.Fatalf("want %d attempts, got %d", limiter.attempts, calls)
	}
	if got.Code != http.StatusTooManyRequests {
		t.Fatalf("the real 429 must reach the caller, got %d", got.Code)
	}
	for _, wait := range clock.waits {
		if wait > limiter.cap+time.Second {
			t.Fatalf("a single wait exceeded the cap: %v", wait)
		}
	}
}

func TestThrottleSpacesOutSuccessiveLogins(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	for i := 0; i < 3; i++ {
		if _, err := limiter.run(func() (*Reply, error) { return statusReply(http.StatusOK, ""), nil }); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if clock.total() < 2*limiter.gap {
		t.Fatalf("logins were not spaced out: %v", clock.total())
	}
}

func TestThrottleDoesNotRetryOrdinaryFailures(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	got, err := limiter.run(func() (*Reply, error) {
		calls++
		return statusReply(http.StatusUnauthorized, ""), nil
	})
	if err != nil || calls != 1 || got.Code != http.StatusUnauthorized {
		t.Fatalf("401 must not be retried: code=%d calls=%d err=%v", got.Code, calls, err)
	}
}

func TestThrottleReturnsTransportErrorsImmediately(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	sentinel := errors.New("dial failed")
	_, err := limiter.run(func() (*Reply, error) {
		calls++
		return nil, sentinel
	})
	if !errors.Is(err, sentinel) || calls != 1 {
		t.Fatalf("transport error must surface once: calls=%d err=%v", calls, err)
	}
}

func TestThrottleCooldownDelaysTheNextCall(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	limiter.cooldown(10 * time.Second)
	if _, err := limiter.run(func() (*Reply, error) { return statusReply(http.StatusOK, ""), nil }); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if clock.total() < 10*time.Second {
		t.Fatalf("cooldown was ignored: %v", clock.total())
	}
}

func TestThrottleRetriesServiceUnavailable(t *testing.T) {
	clock := newFakeClock()
	limiter := testThrottle(clock)
	calls := 0
	got, _ := limiter.run(func() (*Reply, error) {
		calls++
		if calls == 1 {
			return statusReply(http.StatusServiceUnavailable, ""), nil
		}
		return statusReply(http.StatusOK, ""), nil
	})
	if calls != 2 || got.Code != http.StatusOK {
		t.Fatalf("503 should be retried once: calls=%d code=%d", calls, got.Code)
	}
}

func TestRetryAfterRejectsGarbage(t *testing.T) {
	clock := newFakeClock()
	for _, header := range []string{"", "soon", "-5"} {
		if got := retryAfter(statusReply(http.StatusTooManyRequests, header), clock.now()); got != 0 {
			t.Fatalf("header %q should yield 0, got %v", header, got)
		}
	}
}

func TestRateLimitGetsAnActionableMessage(t *testing.T) {
	err := failure(statusReply(http.StatusTooManyRequests, "30"))
	var typed *Error
	if !errors.As(err, &typed) {
		t.Fatalf("want a typed error, got %T", err)
	}
	if typed.Status != http.StatusTooManyRequests {
		t.Fatalf("the status must be preserved, got %d", typed.Status)
	}
	if !stringsContains(typed.Message, "30 seconds") {
		t.Fatalf("the message should tell the user how long to wait: %q", typed.Message)
	}
}

func stringsContains(haystack, needle string) bool {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}
