//go:build linux

package tundev

import (
	"errors"
	"os"
	"strings"
)

const resolvConf = "/etc/resolv.conf"
const resolvBackup = "/etc/gozar-resolv.conf"

func configure(cfg Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs root")
	}
	if err := mustRun("ip", "link", "set", "dev", cfg.Interface, "up"); err != nil {
		return err
	}
	if err := mustRun("ip", "addr", "add", Address.String()+"/32", "dev", cfg.Interface); err != nil {
		return err
	}
	out, _ := command("sh", "-c", "ip route show default | head -n1")
	fields := strings.Fields(strings.TrimSpace(out))
	var gate, device string
	for index, field := range fields {
		if field == "via" && index+1 < len(fields) {
			gate = fields[index+1]
		}
		if field == "dev" && index+1 < len(fields) {
			device = fields[index+1]
		}
	}
	for _, keep := range cfg.Keep {
		if gate != "" && device != "" {
			mustRun("ip", "route", "add", keep.String()+"/32", "via", gate, "dev", device)
		}
	}
	if err := mustRun("ip", "route", "add", "0.0.0.0/1", "dev", cfg.Interface); err != nil {
		return err
	}
	if err := mustRun("ip", "route", "add", "128.0.0.0/1", "dev", cfg.Interface); err != nil {
		return err
	}
	if data, err := os.ReadFile(resolvConf); err == nil {
		os.WriteFile(resolvBackup, data, 0o644)
	}
	os.WriteFile(resolvConf, []byte("nameserver "+cfg.DNS+"\n"), 0o644)
	return nil
}

func restore(cfg Config) {
	mustRun("ip", "route", "delete", "0.0.0.0/1", "dev", cfg.Interface)
	mustRun("ip", "route", "delete", "128.0.0.0/1", "dev", cfg.Interface)
	for _, keep := range cfg.Keep {
		mustRun("ip", "route", "delete", keep.String()+"/32")
	}
	if data, err := os.ReadFile(resolvBackup); err == nil {
		os.WriteFile(resolvConf, data, 0o644)
		os.Remove(resolvBackup)
	}
}

func privileged() bool { return isRoot() }
