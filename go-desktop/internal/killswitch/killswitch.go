package killswitch

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

const anchor = "gozar"

type Guard struct {
	Interface string
	Cgroup    string
	Keep      []string
	table     string
	loaded    bool
	mu        sync.Mutex
	previous  string
}

func New(iface string, keep []string) *Guard {
	return &Guard{Interface: iface, Keep: keep, table: "gozar_kill"}
}

func stateDir() string {
	return filepath.Join(os.TempDir(), "gozar-killswitch")
}

func (g *Guard) Apply() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.loaded {
		return nil
	}
	if g.Interface == "" {
		return errors.New("the kill switch needs the tunnel interface name")
	}
	switch runtime.GOOS {
	case "linux":
		return g.applyLinux()
	case "darwin":
		return g.applyDarwin()
	default:
		return nil
	}
}

func (g *Guard) Remove() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.loaded {
		return nil
	}
	switch runtime.GOOS {
	case "linux":
		return g.removeLinux()
	case "darwin":
		return g.removeDarwin()
	default:
		g.loaded = false
		return nil
	}
}

func (g *Guard) Active() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.loaded
}

func (g *Guard) LinuxRules() string {
	var b strings.Builder
	fmt.Fprintf(&b, "table inet %s {\n", g.table)
	b.WriteString("  chain output {\n")
	b.WriteString("    type filter hook output priority 0; policy drop;\n")
	b.WriteString("    oifname \"lo\" accept\n")
	fmt.Fprintf(&b, "    oifname \"%s\" accept\n", g.Interface)
	if g.Cgroup != "" {
		fmt.Fprintf(&b, "    socket cgroupv2 level %d \"%s\" accept\n", cgroupLevel(g.Cgroup), g.Cgroup)
	}
	for _, address := range g.Keep {
		fmt.Fprintf(&b, "    ip daddr %s accept\n", address)
	}
	b.WriteString("    ip daddr 10.111.0.53 accept\n")
	b.WriteString("    udp sport 68 udp dport 67 accept\n")
	b.WriteString("    udp dport 53 drop\n")
	b.WriteString("    tcp dport 53 drop\n")
	b.WriteString("    meta nfproto ipv6 drop\n")
	b.WriteString("  }\n}\n")
	return b.String()
}

func (g *Guard) applyLinux() error {
	if os.Geteuid() != 0 {
		return errors.New("kill switch needs root")
	}
	if _, err := exec.LookPath("nft"); err != nil {
		return errors.New("nftables is not installed, the kill switch cannot be enforced")
	}
	if g.Cgroup == "" {
		if path, err := cgroupPath(); err == nil {
			g.Cgroup = path
		}
	}
	if g.Cgroup != "" && !cgroupExists(g.Cgroup) {
		g.Cgroup = ""
	}
	_ = exec.Command("nft", "delete", "table", "inet", g.table).Run()
	cmd := exec.Command("nft", "-f", "-")
	cmd.Stdin = strings.NewReader(g.LinuxRules())
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("nft apply failed: %s: %w", strings.TrimSpace(string(out)), err)
	}
	g.loaded = true
	g.mark()
	return nil
}

func (g *Guard) removeLinux() error {
	g.loaded = false
	g.unmark()
	if out, err := exec.Command("nft", "delete", "table", "inet", g.table).CombinedOutput(); err != nil {
		return fmt.Errorf("nft remove failed: %s: %w", strings.TrimSpace(string(out)), err)
	}
	return nil
}

func (g *Guard) mark() {
	dir := stateDir()
	if os.MkdirAll(dir, 0o700) != nil {
		return
	}
	_ = os.WriteFile(filepath.Join(dir, runtime.GOOS+".state"), []byte(g.Interface+"\n"+g.table+"\n"), 0o600)
	if g.previous != "" {
		_ = os.WriteFile(filepath.Join(dir, "pf.previous"), []byte(g.previous), 0o600)
	}
}

func (g *Guard) unmark() {
	_ = os.Remove(filepath.Join(stateDir(), runtime.GOOS+".state"))
	_ = os.Remove(filepath.Join(stateDir(), "pf.previous"))
}

func Reap() {
	dir := stateDir()
	if _, err := os.Stat(filepath.Join(dir, "linux.state")); err == nil {
		_ = exec.Command("nft", "delete", "table", "inet", "gozar_kill").Run()
		_ = os.Remove(filepath.Join(dir, "linux.state"))
	}
	if _, err := os.Stat(filepath.Join(dir, "darwin.state")); err == nil {
		if raw, err := os.ReadFile(filepath.Join(dir, "pf.previous")); err == nil && len(raw) > 0 {
			path := filepath.Join(dir, "pf.restore")
			if os.WriteFile(path, raw, 0o600) == nil {
				_ = exec.Command("pfctl", "-f", path).Run()
			}
		}
		_ = exec.Command("pfctl", "-a", anchor, "-F", "all").Run()
		_ = os.Remove(filepath.Join(dir, "darwin.state"))
		_ = os.Remove(filepath.Join(dir, "pf.previous"))
	}
}

func cgroupLevel(path string) int {
	level := len(strings.Split(strings.Trim(path, "/"), "/"))
	if level < 1 {
		return 1
	}
	return level
}

func cgroupExists(path string) bool {
	trimmed := strings.Trim(path, "/")
	if trimmed == "" || strings.Contains(trimmed, "..") {
		return false
	}
	info, err := os.Stat(filepath.Join("/sys/fs/cgroup", trimmed))
	return err == nil && info.IsDir()
}

func cgroupPath() (string, error) {
	data, err := os.ReadFile("/proc/self/cgroup")
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(data), "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) == 3 && parts[1] == "" {
			return strings.TrimPrefix(parts[2], "/"), nil
		}
	}
	return "", errors.New("cgroup v2 path unavailable")
}

func (g *Guard) DarwinRules() string {
	var b strings.Builder
	fmt.Fprintf(&b, "pass out quick on %s\n", g.Interface)
	for _, address := range g.Keep {
		fmt.Fprintf(&b, "pass out quick to %s\n", address)
	}
	b.WriteString("pass out quick to 10.111.0.53\n")
	b.WriteString("pass out quick proto udp from any port 68 to any port 67\n")
	b.WriteString("block drop out quick proto udp to any port 53\n")
	b.WriteString("block drop out quick proto tcp to any port 53\n")
	b.WriteString("block drop out quick inet6\n")
	b.WriteString("block drop out quick\n")
	return b.String()
}

func (g *Guard) DarwinMainRules(current string) string {
	var b strings.Builder
	b.WriteString("set skip on lo0\n")
	trimmed := strings.TrimSpace(current)
	if trimmed != "" {
		for _, line := range strings.Split(trimmed, "\n") {
			if strings.Contains(line, "anchor \""+anchor+"\"") || strings.Contains(line, "load anchor \""+anchor+"\"") {
				continue
			}
			b.WriteString(line + "\n")
		}
	}
	fmt.Fprintf(&b, "anchor \"%s\"\n", anchor)
	fmt.Fprintf(&b, "load anchor \"%s\" from \"%s\"\n", anchor, filepath.Join(stateDir(), "gozar.pf"))
	return b.String()
}

func (g *Guard) applyDarwin() error {
	if os.Geteuid() != 0 {
		return errors.New("kill switch needs root")
	}
	dir := stateDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	current, err := exec.Command("pfctl", "-sr").Output()
	if err != nil {
		return fmt.Errorf("the current packet filter rules could not be read: %w", err)
	}
	g.previous = string(current)
	rules := filepath.Join(dir, "gozar.pf")
	if err := os.WriteFile(rules, []byte(g.DarwinRules()), 0o600); err != nil {
		return err
	}
	main := filepath.Join(dir, "pf.main")
	if err := os.WriteFile(main, []byte(g.DarwinMainRules(g.previous)), 0o600); err != nil {
		return err
	}
	if out, err := exec.Command("pfctl", "-n", "-f", main).CombinedOutput(); err != nil {
		return fmt.Errorf("pf syntax check failed: %s: %w", strings.TrimSpace(string(out)), err)
	}
	if out, err := exec.Command("pfctl", "-f", main).CombinedOutput(); err != nil {
		return fmt.Errorf("pfctl apply failed: %s: %w", strings.TrimSpace(string(out)), err)
	}
	_ = exec.Command("pfctl", "-e").Run()
	g.loaded = true
	g.mark()
	return nil
}

func (g *Guard) removeDarwin() error {
	g.loaded = false
	dir := stateDir()
	var restore error
	if g.previous != "" {
		path := filepath.Join(dir, "pf.restore")
		if err := os.WriteFile(path, []byte(g.previous), 0o600); err == nil {
			if out, err := exec.Command("pfctl", "-f", path).CombinedOutput(); err != nil {
				restore = fmt.Errorf("pfctl restore failed: %s: %w", strings.TrimSpace(string(out)), err)
			}
		}
	}
	_ = exec.Command("pfctl", "-a", anchor, "-F", "all").Run()
	g.unmark()
	return restore
}
