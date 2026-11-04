package warp

import "testing"

func TestDefaultScannerIncludesWarpPort(t *testing.T) {
	scanner := DefaultScanner()
	if len(scanner.Endpoints) == 0 || len(scanner.Ports) == 0 {
		t.Fatal("scanner has no endpoints or ports")
	}
	found := false
	for _, port := range scanner.Ports {
		if port == DefaultPort {
			found = true
		}
	}
	if !found {
		t.Fatalf("scanner does not include default WARP port: %+v", scanner.Ports)
	}
}

func TestScannerWorkersAreCappedAtTen(t *testing.T) {
	scanner := &Scanner{Workers: 100}
	if got := scanner.effectiveWorkers(); got != MaxWorkers {
		t.Fatalf("workers = %d, want cap %d", got, MaxWorkers)
	}
	scanner = DefaultScanner()
	if scanner.Workers > MaxWorkers {
		t.Fatalf("default workers exceed cap: %d", scanner.Workers)
	}
}
