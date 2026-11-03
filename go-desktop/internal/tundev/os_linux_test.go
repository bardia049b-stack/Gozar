package tundev

import (
	"net/netip"
	"reflect"
	"testing"
)

func TestKeepRouteUsesTheActualSelectedInterface(t *testing.T) {
	address := netip.MustParseAddr("203.0.113.42")
	cases := []struct {
		input    string
		expected []string
		needed   bool
	}{
		{"203.0.113.42 via 192.0.2.1 dev eth1 src 192.0.2.10", []string{"-4", "route", "add", "203.0.113.42/32", "via", "192.0.2.1", "dev", "eth1"}, true},
		{"203.0.113.42 dev ppp0 src 192.0.2.10", []string{"-4", "route", "add", "203.0.113.42/32", "dev", "ppp0"}, true},
		{"203.0.113.42 via inet6 fe80::1 dev eth0", []string{"-4", "route", "add", "203.0.113.42/32", "via", "inet6", "fe80::1", "dev", "eth0"}, true},
		{"local 203.0.113.42 dev lo src 203.0.113.42", nil, false},
	}
	for _, test := range cases {
		got, needed, err := routeForKeep(address, test.input)
		if err != nil || needed != test.needed || !reflect.DeepEqual(got, test.expected) {
			t.Fatalf("%q => %v, %v, %v", test.input, got, needed, err)
		}
	}
	if _, _, err := routeForKeep(address, "unreachable 203.0.113.42"); err == nil {
		t.Fatal("route without interface was accepted")
	}
}
