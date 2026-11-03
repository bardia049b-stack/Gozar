package tundev

import "testing"

func TestWindowsDefaultRouteUsesTheLowestEffectiveMetric(t *testing.T) {
	output := `Active Routes:
Network Destination        Netmask          Gateway       Interface  Metric
          0.0.0.0          0.0.0.0      192.0.2.1       192.0.2.2     60
          0.0.0.0          0.0.0.0    198.51.100.1   198.51.100.2     15
          0.0.0.0          0.0.0.0      203.0.113.1    203.0.113.2      5
          0.0.0.0          0.0.0.0         On-link       192.0.2.3      8
`
	indexes := map[string]int{"192.0.2.2": 2, "198.51.100.2": 4, "192.0.2.3": 6}
	gateway, index := windowsDefaultRoute(output, indexes)
	if gateway != "0.0.0.0" || index != "6" {
		t.Fatalf("gateway=%s index=%s", gateway, index)
	}
	delete(indexes, "192.0.2.3")
	gateway, index = windowsDefaultRoute(output, indexes)
	if gateway != "198.51.100.1" || index != "4" {
		t.Fatalf("gateway=%s index=%s", gateway, index)
	}
}

func TestWindowsDefaultRouteRejectsMalformedRows(t *testing.T) {
	gateway, index := windowsDefaultRoute("0.0.0.0 0.0.0.0 1.2.3.4 5.6.7.8 invalid", map[string]int{"5.6.7.8": 1})
	if gateway != "" || index != "" {
		t.Fatal(gateway, index)
	}
}
