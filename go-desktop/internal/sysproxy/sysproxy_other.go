//go:build !windows

package sysproxy

import (
	"os/exec"
	"runtime"
	"strconv"
	"strings"
)

func isMac() bool { return runtime.GOOS == "darwin" }

func reg(name, value string) error   { return nil }
func dword(name, value string) error { return nil }

// Current is the port the system proxy points at, or 0. Only the ports this
// app hands out are considered.
func Current() int {
	if isMac() {
		out, err := exec.Command("networksetup", "-getwebproxy", "Wi-Fi").Output()
		if err != nil {
			return 0
		}
		text := string(out)
		if !strings.Contains(text, "Enabled: Yes") {
			return 0
		}
		for _, line := range strings.Split(text, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "Port:") {
				value := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "Port:"))
				port, err := strconv.Atoi(value)
				if err == nil {
					return port
				}
			}
		}
		return 0
	}
	out, err := exec.Command("gsettings", "get", "org.gnome.system.proxy.http", "port").Output()
	if err != nil {
		return 0
	}
	port, err := strconv.Atoi(strings.TrimSpace(string(out)))
	if err != nil {
		return 0
	}
	return port
}
