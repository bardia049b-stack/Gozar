package geo

import (
	"net/netip"
	"testing"
)

func TestIranDirect(t *testing.T) {
	r := DefaultIranRouter()
	if !r.Direct("bank.ir") || !r.Direct("sub.bank.ir") {
		t.Fatal(".ir domain was not direct")
	}
	if r.Direct("example.com") {
		t.Fatal("foreign domain was direct")
	}
	if !r.DirectIP(netip.MustParseAddr("2.144.1.1")) {
		t.Fatal("Iranian IP was not direct")
	}
	if r.DirectIP(netip.MustParseAddr("8.8.8.8")) {
		t.Fatal("foreign IP was direct")
	}
}
