package geo

import (
	"bufio"
	"net"
	"net/netip"
	"os"
	"strings"
)

type Router struct {
	DirectSuffixes []string
	DirectCIDRs    []netip.Prefix
	LookupIP       func(string) ([]net.IP, error)
}

func LoadRouter(path string) (*Router, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	router := &Router{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if prefix, err := netip.ParsePrefix(line); err == nil {
			router.DirectCIDRs = append(router.DirectCIDRs, prefix)
			continue
		}
		if address, err := netip.ParseAddr(line); err == nil {
			bits := 128
			if address.Is4() {
				bits = 32
			}
			router.DirectCIDRs = append(router.DirectCIDRs, netip.PrefixFrom(address, bits))
			continue
		}
		suffix := strings.ToLower(strings.TrimSuffix(line, "."))
		if suffix == "" {
			continue
		}
		if !strings.HasPrefix(suffix, ".") {
			suffix = "." + suffix
		}
		router.DirectSuffixes = append(router.DirectSuffixes, suffix)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return router, nil
}

func (r *Router) DNS(host string) string {
	if r.Direct(host) {
		return "10.111.0.53"
	}
	return ""
}
