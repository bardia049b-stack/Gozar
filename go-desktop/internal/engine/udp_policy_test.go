package engine

import (
	"testing"
	"time"
)

func TestRelayPolicyDefaultsAndOverrides(t *testing.T) {
	resetUDPDenials()
	SetRelayPorts(nil)
	for _, port := range []int{80, 443, 3478, 8080, 8443} {
		if !udpAllowed(port) {
			t.Fatalf("port %d must be allowed by default", port)
		}
	}
	for _, port := range []int{53, 88, 3074, 4500, 9295, 1, 65535} {
		if udpAllowed(port) {
			t.Fatalf("port %d must not be allowed by default", port)
		}
	}
	SetRelayPorts(NormalizePorts([]string{"3074", " 3075 ", "0", "70000", "abc", ""}))
	if !udpAllowed(3074) || !udpAllowed(3075) {
		t.Fatal("extra ports must be relayed")
	}
	if udpAllowed(3076) {
		t.Fatal("unlisted extra ports must stay blocked")
	}
	denyUDPPort(3074)
	if udpAllowed(3074) {
		t.Fatal("a denied port must be skipped until the cooldown ends")
	}
	if !udpAllowed(443) {
		t.Fatal("denying one port must not affect the others")
	}
	SetRelayPorts(nil)
	resetUDPDenials()
	if !udpAllowed(443) || udpAllowed(3074) {
		t.Fatal("resetting must restore the default policy")
	}
	_ = time.Now()
}
