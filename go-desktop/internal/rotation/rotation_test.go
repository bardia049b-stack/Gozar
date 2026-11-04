package rotation

import "testing"

type fakeSwitcher struct {
	accounts []Account
	active   string
	switched string
}

func (f *fakeSwitcher) Accounts() []Account { return f.accounts }
func (f *fakeSwitcher) Active() string      { return f.active }
func (f *fakeSwitcher) Switch(id string) error {
	f.switched = id
	f.active = id
	return nil
}

func TestRotatesAtNinetyPercent(t *testing.T) {
	s := &fakeSwitcher{accounts: []Account{{ID: "a", Limit: 100, Remaining: 10}, {ID: "b", Limit: 100, Remaining: 100}}, active: "a"}
	w := NewWorker(s)
	w.Check()
	if s.switched != "b" {
		t.Fatalf("did not rotate: %s", s.switched)
	}
}

func TestRotatesOn429(t *testing.T) {
	s := &fakeSwitcher{accounts: []Account{{ID: "a", Limit: 100, Remaining: 80}, {ID: "b", Limit: 100, Remaining: 100}}, active: "a"}
	w := NewWorker(s)
	w.OnError(429)
	if s.switched != "b" {
		t.Fatalf("did not rotate on 429: %s", s.switched)
	}
}

func TestDoesNotRotateHealthyAccount(t *testing.T) {
	s := &fakeSwitcher{accounts: []Account{{ID: "a", Limit: 100, Remaining: 50}, {ID: "b", Limit: 100, Remaining: 100}}, active: "a"}
	w := NewWorker(s)
	w.Check()
	if s.switched != "" {
		t.Fatalf("rotated healthy account: %s", s.switched)
	}
}
