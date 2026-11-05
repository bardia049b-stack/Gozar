package dnsproxy

import (
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type cacheEntry struct {
	answer  []byte
	expires time.Time
}

const (
	cacheFloorTTL = 60
	cacheMaxTTL   = 900
	negativeTTL   = 120
)

func (c *dnsCache) counters() (int64, int64, int64) {
	return c.hits.Load(), c.misses.Load(), c.coalesced.Load()
}

type dnsCache struct {
	mu      sync.Mutex
	entries map[string]cacheEntry
	flight  map[string]*dnsCall
	max     int

	hits      atomic.Int64
	misses    atomic.Int64
	coalesced atomic.Int64
	negative  atomic.Int64
}

type dnsCall struct {
	done   chan struct{}
	answer []byte
	err    error
}

func newDNSCache() *dnsCache {
	return &dnsCache{entries: make(map[string]cacheEntry), flight: make(map[string]*dnsCall), max: 4096}
}

func cacheKey(query dnsmessage.Message) string {
	question := query.Questions[0]
	return strings.ToLower(question.Name.String()) + "|" + question.Type.String() + "|" + question.Class.String()
}

func (c *dnsCache) get(key string, id uint16) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	if !ok || time.Now().After(entry.expires) {
		if ok {
			delete(c.entries, key)
		}
		c.misses.Add(1)
		return nil, false
	}
	c.hits.Add(1)
	return retag(entry.answer, id), true
}

func (c *dnsCache) put(key string, answer []byte) {
	ttl := minimumTTL(answer)
	if ttl <= 0 {
		return
	}
	if ttl < cacheFloorTTL {
		ttl = cacheFloorTTL
	}
	if ttl > cacheMaxTTL {
		ttl = cacheMaxTTL
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= c.max {
		for name, entry := range c.entries {
			if time.Now().After(entry.expires) {
				delete(c.entries, name)
			}
		}
		if len(c.entries) >= c.max {
			for name := range c.entries {
				delete(c.entries, name)
				break
			}
		}
	}
	c.entries[key] = cacheEntry{answer: append([]byte(nil), answer...), expires: time.Now().Add(time.Duration(ttl) * time.Second)}
}

func (c *dnsCache) do(key string, fn func() ([]byte, error)) ([]byte, error) {
	c.mu.Lock()
	if call, ok := c.flight[key]; ok {
		c.coalesced.Add(1)
		c.mu.Unlock()
		<-call.done
		return call.answer, call.err
	}
	call := &dnsCall{done: make(chan struct{})}
	c.flight[key] = call
	c.mu.Unlock()
	call.answer, call.err = fn()
	c.mu.Lock()
	delete(c.flight, key)
	c.mu.Unlock()
	close(call.done)
	return call.answer, call.err
}

func minimumTTL(answer []byte) uint32 {
	var message dnsmessage.Message
	if message.Unpack(answer) != nil || !message.Response {
		return 0
	}
	if message.RCode == dnsmessage.RCodeNameError {
		return negativeTTL
	}
	if len(message.Answers) == 0 && message.RCode == dnsmessage.RCodeSuccess {
		return negativeTTL
	}
	var ttl uint32
	for _, record := range message.Answers {
		if record.Header.Type == dnsmessage.TypeOPT {
			continue
		}
		if ttl == 0 || record.Header.TTL < ttl {
			ttl = record.Header.TTL
		}
	}
	if ttl == 0 {
		ttl = 30
	}
	return ttl
}

func retag(answer []byte, id uint16) []byte {
	if len(answer) < 2 {
		return nil
	}
	out := append([]byte(nil), answer...)
	out[0] = byte(id >> 8)
	out[1] = byte(id)
	return out
}
