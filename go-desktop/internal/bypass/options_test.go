package bypass

import (
	"encoding/json"
	"testing"
)

func TestAdvancedOptionsDefaultOff(t *testing.T) {
	options := Default()
	if len(options.Enabled()) != 0 {
		t.Fatalf("advanced bypass enabled by default: %v", options.Enabled())
	}
	data, err := json.Marshal(options)
	if err != nil {
		t.Fatal(err)
	}
	var loaded Options
	if err := json.Unmarshal(data, &loaded); err != nil {
		t.Fatal(err)
	}
	if len(loaded.Enabled()) != 0 {
		t.Fatalf("round trip enabled options: %v", loaded.Enabled())
	}
}

func TestAdvancedOptionsExplicitEnable(t *testing.T) {
	options := Default()
	options.TLSFragment = true
	options.UTLS = true
	options.Padding = true
	if err := options.Validate(); err != nil {
		t.Fatal(err)
	}
	enabled := options.Enabled()
	if len(enabled) != 3 {
		t.Fatalf("unexpected enabled options: %v", enabled)
	}
	order := FallbackOrder()
	if order[0] != "tls_fragment" || order[1] != "utls" || order[2] != "standard" {
		t.Fatalf("unexpected fallback order: %v", order)
	}
}
