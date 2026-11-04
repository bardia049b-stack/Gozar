package config

import "testing"

func TestAdvancedBypassDefaultOff(t *testing.T) {
	sandbox(t)
	loaded := Load()
	if len(loaded.Advanced.Enabled()) != 0 {
		t.Fatalf("advanced bypass enabled by default: %v", loaded.Advanced.Enabled())
	}
}

func TestAdvancedBypassRoundTrip(t *testing.T) {
	sandbox(t)
	value := defaults()
	value.Advanced.TLSFragment = true
	value.Advanced.UTLS = true
	if err := Save(value); err != nil {
		t.Fatal(err)
	}
	loaded := Load()
	if !loaded.Advanced.TLSFragment || !loaded.Advanced.UTLS {
		t.Fatalf("advanced bypass choice lost: %+v", loaded.Advanced)
	}
}
