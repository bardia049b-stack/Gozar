package dnsproxy

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func answer(t *testing.T, ttl uint32, code dnsmessage.RCode, records int) []byte {
	t.Helper()
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{Response: true, RCode: code},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("example.com."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}
	for index := 0; index < records; index++ {
		message.Answers = append(message.Answers, dnsmessage.Resource{
			Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("example.com."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: ttl},
			Body:   &dnsmessage.AResource{A: [4]byte{93, 184, 216, 34}},
		})
	}
	raw, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func TestCacheFloorKeepsShortTTLAnswers(t *testing.T) {
	cache := newDNSCache()
	raw := answer(t, 5, dnsmessage.RCodeSuccess, 1)
	cache.put("short", raw)
	entry, ok := cache.entries["short"]
	if !ok {
		t.Fatal("a five second answer was dropped")
	}
	remaining := time.Until(entry.expires)
	if remaining < time.Duration(cacheFloorTTL-2)*time.Second {
		t.Fatalf("the floor was not applied, only %s left", remaining)
	}
}

func TestCacheCapLimitsLongTTLAnswers(t *testing.T) {
	cache := newDNSCache()
	cache.put("long", answer(t, 86400, dnsmessage.RCodeSuccess, 1))
	entry := cache.entries["long"]
	if time.Until(entry.expires) > time.Duration(cacheMaxTTL)*time.Second {
		t.Fatal("the cap was not applied")
	}
}

func TestNegativeAnswersAreCached(t *testing.T) {
	cache := newDNSCache()
	cache.put("nx", answer(t, 0, dnsmessage.RCodeNameError, 0))
	if _, ok := cache.entries["nx"]; !ok {
		t.Fatal("NXDOMAIN was not cached, every retry would hit the network")
	}
	cache.put("nodata", answer(t, 0, dnsmessage.RCodeSuccess, 0))
	if _, ok := cache.entries["nodata"]; !ok {
		t.Fatal("an empty NOERROR answer was not cached")
	}
}

func TestSingleFlightCollapsesParallelQueries(t *testing.T) {
	cache := newDNSCache()
	var calls atomic.Int64
	raw := answer(t, 60, dnsmessage.RCodeSuccess, 1)
	allJoined := make(chan struct{})
	go func() {
		deadline := time.Now().Add(10 * time.Second)
		for cache.coalesced.Load() < 31 && time.Now().Before(deadline) {
			time.Sleep(time.Millisecond)
		}
		close(allJoined)
	}()
	var group sync.WaitGroup
	for index := 0; index < 32; index++ {
		group.Add(1)
		go func() {
			defer group.Done()
			if _, err := cache.do("same", func() ([]byte, error) {
				calls.Add(1)
				<-allJoined
				return raw, nil
			}); err != nil {
				t.Error(err)
			}
		}()
	}
	group.Wait()
	if calls.Load() != 1 {
		t.Fatalf("32 identical queries produced %d upstream lookups", calls.Load())
	}
	if cache.coalesced.Load() != 31 {
		t.Fatalf("expected 31 coalesced callers, got %d", cache.coalesced.Load())
	}
}

func TestCacheCountersTrackHitsAndMisses(t *testing.T) {
	cache := newDNSCache()
	if _, ok := cache.get("absent", 7); ok {
		t.Fatal("an empty cache returned a hit")
	}
	cache.put("present", answer(t, 120, dnsmessage.RCodeSuccess, 1))
	for index := 0; index < 5; index++ {
		if _, ok := cache.get("present", uint16(index)); !ok {
			t.Fatal("a stored answer was not returned")
		}
	}
	hits, misses, _ := cache.counters()
	if hits != 5 || misses != 1 {
		t.Fatalf("counters are wrong: hits=%d misses=%d", hits, misses)
	}
}

func TestCachedAnswerKeepsTheQueryID(t *testing.T) {
	cache := newDNSCache()
	cache.put("id", answer(t, 120, dnsmessage.RCodeSuccess, 1))
	raw, ok := cache.get("id", 0xBEEF)
	if !ok {
		t.Fatal("the answer was not cached")
	}
	if raw[0] != 0xBE || raw[1] != 0xEF {
		t.Fatalf("the response id was not rewritten: %x", raw[:2])
	}
}
