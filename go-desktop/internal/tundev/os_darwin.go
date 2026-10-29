package tundev

import (
	"errors"
	"net"
	"strings"
)

func configure(cfg *Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs administrator rights")
	}
	if err := mustRun("ifconfig", cfg.Interface, Address.String(), Address.String(),
		"netmask", "255.255.255.255", "up"); err != nil {
		return err
	}
	out, err := command("route", "-n", "get", "default")
	if err != nil {
		return err
	}
	var gateway string
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && fields[0] == "gateway:" {
			gateway = fields[1]
			break
		}
	}
	if gateway == "" {
		return errors.New("no usable default gateway")
	}
	for _, keep := range cfg.Keep {
		if err := mustRun("route", "-n", "add", "-host", keep.String(), gateway); err != nil {
			return err
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	if err := mustRun("route", "-n", "add", "-net", "0.0.0.0/1", "-interface", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[0] = true
	if err := mustRun("route", "-n", "add", "-net", "128.0.0.0/1", "-interface", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[1] = true
	cfg.ServiceDNS = make(map[string][]string)
	for _, service := range services() {
		before, err := command("networksetup", "-getdnsservers", service)
		if err != nil {
			return err
		}
		var addresses []string
		for _, line := range strings.Split(before, "\n") {
			value := strings.TrimSpace(line)
			if net.ParseIP(strings.Split(value, "%")[0]) != nil {
				addresses = append(addresses, value)
			}
		}
		cfg.ServiceDNS[service] = addresses
		if err := mustRun("networksetup", "-setdnsservers", service, cfg.DNS); err != nil {
			return err
		}
		cfg.ChangedServices = append(cfg.ChangedServices, service)
	}
	return nil
}

func restore(cfg *Config) {
	for _, service := range cfg.ChangedServices {
		values := cfg.ServiceDNS[service]
		if len(values) == 0 {
			values = []string{"Empty"}
		}
		args := append([]string{"-setdnsservers", service}, values...)
		mustRun("networksetup", args...)
	}
	cfg.ChangedServices = nil
	if cfg.Routes[0] {
		mustRun("route", "-n", "delete", "-net", "0.0.0.0/1", "-interface", cfg.Interface)
		cfg.Routes[0] = false
	}
	if cfg.Routes[1] {
		mustRun("route", "-n", "delete", "-net", "128.0.0.0/1", "-interface", cfg.Interface)
		cfg.Routes[1] = false
	}
	for _, keep := range cfg.AddedKeep {
		mustRun("route", "-n", "delete", "-host", keep.String())
	}
	cfg.AddedKeep = nil
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
