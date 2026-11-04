package geo

import (
	"net"
	"testing"
)

func TestLoadTestGeoRouter(t *testing.T) {
	router, err := LoadRouter("test_geo.txt")
	if err != nil {
		t.Fatal(err)
	}
	router.LookupIP = func(host string) ([]net.IP, error) {
		switch host {
		case "aparat.com":
			return []net.IP{net.ParseIP("185.55.226.10")}, nil
		case "google.com":
			return []net.IP{net.ParseIP("142.250.185.14")}, nil
		}
		return nil, &net.DNSError{Err: "no such host", Name: host, IsNotFound: true}
	}
	for _, host := range []string{"aparat.com", "www.aparat.com", "bank.ir", "185.55.226.26"} {
		if !router.Direct(host) {
			t.Fatalf("%s should be direct", host)
		}
		if router.DNS(host) != "10.111.0.53" {
			t.Fatalf("%s should use internal Iranian DNS", host)
		}
	}
	for _, host := range []string{"google.com", "www.google.com", "8.8.8.8"} {
		if router.Direct(host) {
			t.Fatalf("%s should be tunneled", host)
		}
		if router.DNS(host) != "" {
			t.Fatalf("%s should not use internal Iranian DNS", host)
		}
	}
}
