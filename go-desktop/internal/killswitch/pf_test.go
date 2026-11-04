package killswitch

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestPFRulesContent(t *testing.T) {
	g := New("utun4", []string{"192.0.2.10"})
	rules := g.DarwinRules()
	for _, want := range []string{"pass out quick on utun4", "192.0.2.10", "10.111.0.53", "port 53", "inet6", "block drop out quick"} {
		if !strings.Contains(rules, want) {
			t.Fatalf("missing %q in rules:\n%s", want, rules)
		}
	}
	if strings.Contains(rules, "set persist") {
		t.Fatal("invalid pf persist syntax")
	}
}

func TestPFRulesSyntax(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("pfctl is only available on macOS")
	}
	g := New("utun4", []string{"192.0.2.10"})
	path := filepath.Join(t.TempDir(), "gozar.pf")
	if err := os.WriteFile(path, []byte(g.DarwinRules()), 0o600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("pfctl", "-n", "-f", path)
	if os.Geteuid() != 0 {
		cmd = exec.Command("sudo", "-n", "pfctl", "-n", "-f", path)
	}
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("pfctl syntax check failed: %s: %v", out, err)
	}
}
