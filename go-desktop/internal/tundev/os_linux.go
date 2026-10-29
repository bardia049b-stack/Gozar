package tundev

import (
	"errors"
	"os"
	"strings"
)

const resolvConf = "/etc/resolv.conf"

func configure(cfg *Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs root")
	}
	if err := mustRun("ip", "link", "set", "dev", cfg.Interface, "up"); err != nil {
		return err
	}
	if err := mustRun("ip", "addr", "add", Address.String()+"/32", "dev", cfg.Interface); err != nil {
		return err
	}
	out, err := command("ip", "route", "show", "default")
	if err != nil {
		return err
	}
	fields := strings.Fields(strings.SplitN(out, "\n", 2)[0])
	var gateway, device string
	for index, field := range fields {
		if field == "via" && index+1 < len(fields) {
			gateway = fields[index+1]
		}
		if field == "dev" && index+1 < len(fields) {
			device = fields[index+1]
		}
	}
	if gateway == "" || device == "" {
		return errors.New("no usable default gateway")
	}
	for _, keep := range cfg.Keep {
		if err := mustRun("ip", "route", "add", keep.String()+"/32", "via", gateway, "dev", device); err != nil {
			return err
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	if err := mustRun("ip", "route", "add", "0.0.0.0/1", "dev", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[0] = true
	if err := mustRun("ip", "route", "add", "128.0.0.0/1", "dev", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[1] = true
	before, err := os.ReadFile(resolvConf)
	if err != nil {
		return err
	}
	cfg.DNSBefore = before
	cfg.DNSChanged = true
	if err := os.WriteFile(resolvConf, []byte("nameserver "+cfg.DNS+"\n"), 0o644); err != nil {
		return err
	}
	return nil
}

func restore(cfg *Config) {
	if cfg.DNSChanged {
		os.WriteFile(resolvConf, cfg.DNSBefore, 0o644)
		cfg.DNSChanged = false
	}
	if cfg.Routes[0] {
		mustRun("ip", "route", "delete", "0.0.0.0/1", "dev", cfg.Interface)
		cfg.Routes[0] = false
	}
	if cfg.Routes[1] {
		mustRun("ip", "route", "delete", "128.0.0.0/1", "dev", cfg.Interface)
		cfg.Routes[1] = false
	}
	for _, keep := range cfg.AddedKeep {
		mustRun("ip", "route", "delete", keep.String()+"/32")
	}
	cfg.AddedKeep = nil
}

func privileged() bool { return isRoot() }
