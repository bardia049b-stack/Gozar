//go:build darwin

package tundev

import (
	"errors"
	"strings"
)

func configure(cfg Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs administrator rights")
	}
	if err := mustRun("ifconfig", cfg.Interface, Address.String(), Address.String(),
		"netmask", "255.255.255.255", "up"); err != nil {
		return err
	}
	gate, _ := command("sh", "-c", "route -n get default | awk '/gateway/ {print $2}'")
	gate = strings.TrimSpace(gate)
	for _, keep := range cfg.Keep {
		if gate == "" {
			break
		}
		mustRun("route", "-n", "add", "-host", keep.String(), gate)
	}
	if err := mustRun("route", "-n", "add", "-net", "0.0.0.0/1", "-interface", cfg.Interface); err != nil {
		return err
	}
	if err := mustRun("route", "-n", "add", "-net", "128.0.0.0/1", "-interface", cfg.Interface); err != nil {
		return err
	}
	for _, service := range services() {
		mustRun("networksetup", "-setdnsservers", service, cfg.DNS)
	}
	return nil
}

func restore(cfg Config) {
	mustRun("route", "-n", "delete", "-net", "0.0.0.0/1", "-interface", cfg.Interface)
	mustRun("route", "-n", "delete", "-net", "128.0.0.0/1", "-interface", cfg.Interface)
	for _, keep := range cfg.Keep {
		mustRun("route", "-n", "delete", "-host", keep.String())
	}
	for _, service := range services() {
		mustRun("networksetup", "-setdnsservers", service, "Empty")
	}
}

func services() []string {
	out, err := command("networksetup", "-listallnetworkservices")
	if err != nil {
		return nil
	}
	var list []string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "*") || strings.Contains(line, "denotes") ||
			strings.Contains(strings.ToLower(line), "asterisk") {
			continue
		}
		list = append(list, line)
	}
	return list
}

func privileged() bool { return isRoot() }
