package tunnel

import (
	"context"
	"sync"
	"time"
)

func (m *Manager) Prewarm(ctx context.Context, pass string) {
	if pass == "" {
		return
	}
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			session, err := m.h2Client(8 * time.Second)
			if err != nil {
				return
			}
			m.releaseH2(session)
		}()
	}
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
}

func (m *Manager) PoolSize() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.h2)
}
