package killswitch

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

type Guard struct {
	Interface string
	Cgroup    string
	Keep      []string
	table     string
	loaded    bool
}

func New(iface string, keep []string) *Guard {
	return &Guard{Interface: iface, Keep: keep, table: "gozar_kill"}
}

func (g *Guard) Apply() error {
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
	switch runtime.GOOS {
	case "linux":
		return g.removeLinux()
	case "darwin":
		return g.removeDarwin()
	default:
		return nil
	}
}

func (g *Guard) LinuxRules() string {
	var b strings.Builder
	fmt.Fprintf(&b, "table inet %s {\n", g.table)
	b.WriteString("  chain output {\n")
	b.WriteString("    type filter hook output priority 0; policy drop;\n")
	b.WriteString("    oifname \"lo\" accept\n")
	fmt.Fprintf(&b, "    oifname \"%s\" accept\n", g.Interface)
	if g.Cgroup != "" {
		fmt.Fprintf(&b, "    socket cgroupv2 level 2 \"%s\" accept\n", g.Cgroup)
	}
	for _, address := range g.Keep {
		fmt.Fprintf(&b, "    ip daddr %s accept\n", address)
	}
	b.WriteString("    ip daddr 10.111.0.53 accept\n")
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
	if path, err := cgroupPath(); err == nil {
		g.Cgroup = path
	}
	rules := g.LinuxRules()
	cmd := exec.Command("nft", "-f", "-")
	cmd.Stdin = strings.NewReader(rules)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("nft apply failed: %s: %w", strings.TrimSpace(string(out)), err)
	}
	g.loaded = true
	return nil
}

func (g *Guard) removeLinux() error {
	if !g.loaded {
		return nil
	}
	g.loaded = false
	cmd := exec.Command("nft", "delete", "table", "inet", g.table)
	_ = cmd.Run()
	return nil
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
	b.WriteString("set persist {\n")
	fmt.Fprintf(&b, "  %s\n", g.Interface)
	b.WriteString("}\n")
	b.WriteString("block drop out quick\n")
	b.WriteString("pass out quick on lo0\n")
	fmt.Fprintf(&b, "pass out quick on $%s\n", g.Interface)
	for _, address := range g.Keep {
		fmt.Fprintf(&b, "pass out quick to %s\n", address)
	}
	b.WriteString("pass out quick to 10.111.0.53\n")
	b.WriteString("block drop out quick proto udp to any port 53\n")
	b.WriteString("block drop out quick proto tcp to any port 53\n")
	b.WriteString("block drop out quick inet6\n")
	return b.String()
}

func (g *Guard) applyDarwin() error {
	if os.Geteuid() != 0 {
		return errors.New("kill switch needs root")
	}
	path := filepath.Join(os.TempDir(), "gozar-killswitch.pf")
	if err := os.WriteFile(path, []byte(g.DarwinRules()), 0o600); err != nil {
		return err
	}
	if out, err := exec.Command("pfctl", "-f", path).CombinedOutput(); err != nil {
		return fmt.Errorf("pfctl apply failed: %s: %w", strings.TrimSpace(string(out)), err)
	}
	g.loaded = true
	return nil
}

func (g *Guard) removeDarwin() error {
	if !g.loaded {
		return nil
	}
	g.loaded = false
	_ = exec.Command("pfctl", "-F", "rules").Run()
	return nil
}
