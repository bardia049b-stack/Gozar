package geo

import (
	"net/netip"
	"testing"
)

func TestIranRouterDoesNotDirectForeignTraffic(t *testing.T) {
	router := DefaultIranRouter()
	for _, host := range []string{"example.com", "cloudflare.com", "ir.example.com"} {
		if router.Direct(host) {
			t.Fatalf("foreign host was direct: %s", host)
		}
	}
	for _, address := range []string{"1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"} {
		if router.DirectIP(netip.MustParseAddr(address)) {
			t.Fatalf("foreign address was direct: %s", address)
		}
	}
}

func TestIranRouterDirectSuffixAndCIDR(t *testing.T) {
	router := DefaultIranRouter()
	for _, host := range []string{"bank.ir", "sub.bank.ir", "ir"} {
		if !router.Direct(host) {
			t.Fatalf("Iranian host was not direct: %s", host)
		}
	}
	for _, address := range []string{"2.144.1.1", "5.126.0.1", "2.176.0.1"} {
		if !router.DirectIP(netip.MustParseAddr(address)) {
			t.Fatalf("Iranian address was not direct: %s", address)
		}
	}
}
