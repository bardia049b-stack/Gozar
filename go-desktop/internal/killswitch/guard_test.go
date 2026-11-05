package killswitch

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestDarwinMainRulesKeepTheSystemRuleset(t *testing.T) {
	guard := New("utun4", []string{"192.0.2.10"})
	current := "block drop out quick proto tcp from any to any port 25\npass out quick proto udp to 192.0.2.53 port 53\n"
	main := guard.DarwinMainRules(current)
	if !strings.Contains(main, "port 25") || !strings.Contains(main, "192.0.2.53") {
		t.Fatalf("the existing rules were dropped:\n%s", main)
	}
	if !strings.Contains(main, `anchor "gozar"`) || !strings.Contains(main, `load anchor "gozar" from`) {
		t.Fatalf("the gozar anchor is missing:\n%s", main)
	}
	if strings.Contains(main, "set persist") {
		t.Fatal("invalid pf syntax")
	}
}

func TestDarwinMainRulesDoNotDuplicateTheAnchor(t *testing.T) {
	guard := New("utun4", nil)
	once := guard.DarwinMainRules("pass out quick\n")
	twice := guard.DarwinMainRules(once)
	if strings.Count(twice, `anchor "gozar"`) != 2 {
		t.Fatalf("applying twice duplicated the anchor:\n%s", twice)
	}
}

func TestDarwinRulesNeverSkipTheLoopbackInsideTheAnchor(t *testing.T) {
	guard := New("utun4", nil)
	if strings.Contains(guard.DarwinRules(), "set skip") {
		t.Fatal("set statements are not allowed inside an anchor")
	}
}

func TestRulesAllowDHCPSoTheLeaseSurvives(t *testing.T) {
	guard := New("tun0", []string{"192.0.2.10"})
	if !strings.Contains(guard.LinuxRules(), "udp sport 68 udp dport 67 accept") {
		t.Fatal("the linux rules would kill the DHCP lease")
	}
	if !strings.Contains(guard.DarwinRules(), "port 68 to any port 67") {
		t.Fatal("the pf rules would kill the DHCP lease")
	}
}

func TestApplyNeedsAnInterface(t *testing.T) {
	guard := New("", nil)
	if err := guard.Apply(); err == nil {
		t.Fatal("a guard without an interface was applied")
	}
	if guard.Active() {
		t.Fatal("a failed apply reported an active guard")
	}
}

func TestRemoveIsSafeWhenNeverApplied(t *testing.T) {
	guard := New("tun0", nil)
	if err := guard.Remove(); err != nil {
		t.Fatalf("removing an idle guard failed: %v", err)
	}
}

func TestReapClearsStaleState(t *testing.T) {
	dir := stateDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"linux.state", "darwin.state", "pf.previous"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("stale"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	Reap()
	for _, name := range []string{"linux.state", "darwin.state", "pf.previous"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Fatalf("%s survived the reap", name)
		}
	}
}

func TestLinuxRulesAreAcceptedByNft(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("nftables is only available on linux")
	}
	if _, err := exec.LookPath("nft"); err != nil {
		t.Skip("nft is not installed")
	}
	if os.Geteuid() != 0 {
		t.Skip("checking nftables rules needs root")
	}
	guard := New("lo", []string{"192.0.2.10"})
	guard.table = "gozar_syntax_check"
	cmd := exec.Command("nft", "-c", "-f", "-")
	cmd.Stdin = strings.NewReader(guard.LinuxRules())
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("nft rejected the rules: %s: %v", out, err)
	}
}
