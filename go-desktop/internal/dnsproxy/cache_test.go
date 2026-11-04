package dnsproxy

import (
	"io"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func TestCacheAndSingleFlight(t *testing.T) {
	var requests atomic.Int32
	r, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		requests.Add(1)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answerFor(t, body))
	}))
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(id uint16) {
			defer wg.Done()
			wire := query(t, id, "cached.example.", dnsmessage.TypeA)
			answer, err := r.Resolve(wire)
			if err != nil {
				t.Error(err)
				return
			}
			q, _ := readQuery(wire)
			if err := checkAnswer(q, answer); err != nil {
				t.Error(err)
			}
		}(uint16(i + 1))
	}
	wg.Wait()
	if requests.Load() != 1 {
		t.Fatalf("duplicate upstream queries: %d", requests.Load())
	}
}
