package killswitch

import (
	"strings"
	"testing"
)

func TestLinuxRules(t *testing.T) {
	g := New("tun0", []string{"192.0.2.10"})
	g.Cgroup = "system.slice/gozar.service"
	rules := g.LinuxRules()
	for _, want := range []string{"policy drop", "tun0", "cgroupv2", "192.0.2.10", "udp dport 53 drop", "ipv6 drop"} {
		if !strings.Contains(rules, want) {
			t.Fatalf("missing %q in rules:\n%s", want, rules)
		}
	}
}

func TestDarwinRules(t *testing.T) {
	g := New("utun4", []string{"192.0.2.10"})
	rules := g.DarwinRules()
	for _, want := range []string{"block drop out quick", "utun4", "192.0.2.10", "port 53", "inet6"} {
		if !strings.Contains(rules, want) {
			t.Fatalf("missing %q in rules:\n%s", want, rules)
		}
	}
}
