//go:build windows

package tundev

import (
	"errors"
	"strconv"
	"strings"

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
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 5 && fields[0] == "0.0.0.0" && fields[1] == "0.0.0.0" {
			return fields[2], fields[3]
		}
	}
	return "", ""
}

func configure(cfg Config) error {
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
	for _, keep := range cfg.Keep {
		if gate == "" || index == "" {
			break
		}
		mustRun("route", "add", keep.String(), "mask", "255.255.255.255", gate, "metric", "1", "if", index)
	}
	mustRun("netsh", "interface", "ipv4", "set", "interface", cfg.Interface, "metric=1")
	if err := mustRun("netsh", "interface", "ipv4", "add", "route", "0.0.0.0/1", cfg.Interface); err != nil {
		return err
	}
	if err := mustRun("netsh", "interface", "ipv4", "add", "route", "128.0.0.0/1", cfg.Interface); err != nil {
		return err
	}
	return nil
}

func restore(cfg Config) {
	mustRun("netsh", "interface", "ipv4", "delete", "route", "0.0.0.0/1", cfg.Interface)
	mustRun("netsh", "interface", "ipv4", "delete", "route", "128.0.0.0/1", cfg.Interface)
	for _, keep := range cfg.Keep {
		mustRun("route", "delete", keep.String())
	}
}

var _ = strconv.Itoa

func privileged() bool { return isWindowsAdmin() }
