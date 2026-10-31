package tundev

import (
	"reflect"
	"testing"
)

func TestServiceDNSParsesAddressesWithoutMessages(t *testing.T) {
	cases := []struct {
		input string
		want  []string
	}{
		{"There aren't any DNS Servers set on Wi-Fi.\n", nil},
		{"8.8.8.8\n 2001:4860:4860::8888 \nfe80::1%en0\n", []string{"8.8.8.8", "2001:4860:4860::8888", "fe80::1%en0"}},
	}
	for _, test := range cases {
		if got := serviceDNS(test.input); !reflect.DeepEqual(got, test.want) {
			t.Fatalf("service DNS %q: got %v, want %v", test.input, got, test.want)
		}
	}
}
