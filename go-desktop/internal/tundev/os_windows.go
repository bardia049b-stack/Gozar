package tundev

import (
	"errors"
	"net"

	"golang.org/x/sys/windows"
)

func isWindowsAdmin() bool {
	var sid *windows.SID
	err := windows.AllocateAndInitializeSid(&windows.SECURITY_NT_AUTHORITY, 2,
		windows.SECURITY_BUILTIN_DOMAIN_RID, windows.DOMAIN_ALIAS_RID_ADMINS,
		0, 0, 0, 0, 0, 0, &sid)
	if err != nil {
		return false
	}
	defer windows.FreeSid(sid)
	token := windows.Token(0)
	member, err := token.IsMember(sid)
	return err == nil && member
}

func gateway() (string, string) {
	out, err := command("route", "print", "-4", "0.0.0.0")
	if err != nil {
		return "", ""
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return "", ""
	}
	indexes := make(map[string]int)
	for _, iface := range interfaces {
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			candidate, _, err := net.ParseCIDR(address.String())
			if err == nil && candidate.To4() != nil {
				indexes[candidate.String()] = iface.Index
			}
		}
	}
	return windowsDefaultRoute(out, indexes)
}

func configure(cfg *Config) error {
	if !isWindowsAdmin() {
		return errors.New("the tunnel needs administrator rights")
	}
	if err := mustRun("netsh", "interface", "ipv4", "set", "address", "name="+cfg.Interface,
		"static", Address.String(), "255.255.255.255"); err != nil {
		return err
	}
	if err := mustRun("netsh", "interface", "ipv4", "set", "dnsservers", "name="+cfg.Interface,
		"static", cfg.DNS, "primary"); err != nil {
		return err
	}
	gate, index := gateway()
	if gate == "" || index == "" {
		return errors.New("no usable default gateway")
	}
	for _, keep := range cfg.Keep {
		if err := mustRun("route", "add", keep.String(), "mask", "255.255.255.255", gate, "metric", "1", "if", index); err != nil {
			return err
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	if err := mustRun("netsh", "interface", "ipv4", "set", "interface", cfg.Interface, "metric=1"); err != nil {
		return err
	}
	if err := mustRun("netsh", "interface", "ipv4", "add", "route", "0.0.0.0/1", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[0] = true
	if err := mustRun("netsh", "interface", "ipv4", "add", "route", "128.0.0.0/1", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[1] = true
	return nil
}

func restore(cfg *Config) {
	if cfg.Routes[0] {
		mustRun("netsh", "interface", "ipv4", "delete", "route", "0.0.0.0/1", cfg.Interface)
		cfg.Routes[0] = false
	}
	if cfg.Routes[1] {
		mustRun("netsh", "interface", "ipv4", "delete", "route", "128.0.0.0/1", cfg.Interface)
		cfg.Routes[1] = false
	}
	for _, keep := range cfg.AddedKeep {
		mustRun("route", "delete", keep.String())
	}
	cfg.AddedKeep = nil
}

func privileged() bool { return isWindowsAdmin() }
