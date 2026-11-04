package geo

import (
	"net"
	"net/netip"
	"strings"
)

type Router struct {
	DirectSuffixes []string
	DirectCIDRs    []netip.Prefix
}

func DefaultIranRouter() *Router {
	return &Router{
		DirectSuffixes: []string{".ir", "ایران.", "ایران"},
		DirectCIDRs:    IranCIDRs(),
	}
}

func (r *Router) Direct(host string) bool {
	name := strings.ToLower(strings.Trim(host, "[]"))
	if name == "" {
		return false
	}
	if strings.HasSuffix(name, ".ir") || name == "ir" {
		return true
	}
	for _, suffix := range r.DirectSuffixes {
		suffix = strings.ToLower(suffix)
		if strings.HasPrefix(suffix, ".") {
			if strings.HasSuffix(name, suffix) {
				return true
			}
		} else if name == suffix || strings.HasSuffix(name, "."+suffix) {
			return true
		}
	}
	if address, err := netip.ParseAddr(name); err == nil {
		return r.DirectIP(address)
	}
	addresses, err := net.LookupIP(name)
	if err != nil {
		return false
	}
	for _, address := range addresses {
		if parsed, ok := netip.AddrFromSlice(address); ok && r.DirectIP(parsed.Unmap()) {
			return true
		}
	}
	return false
}

func (r *Router) DirectIP(address netip.Addr) bool {
	for _, prefix := range r.DirectCIDRs {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func IranCIDRs() []netip.Prefix {
	values := []string{
		"2.144.0.0/14", "2.176.0.0/12", "5.1.0.0/16", "5.22.0.0/17", "5.23.0.0/16",
		"5.34.0.0/16", "5.52.0.0/16", "5.53.0.0/16", "5.56.0.0/16", "5.57.0.0/16",
		"5.61.0.0/16", "5.62.0.0/16", "5.63.0.0/16", "5.72.0.0/16", "5.73.0.0/16",
		"5.74.0.0/16", "5.75.0.0/16", "5.76.0.0/16", "5.77.0.0/16", "5.78.0.0/16",
		"5.79.0.0/16", "5.80.0.0/16", "5.81.0.0/16", "5.82.0.0/16", "5.83.0.0/16",
		"5.84.0.0/16", "5.85.0.0/16", "5.86.0.0/16", "5.87.0.0/16", "5.88.0.0/16",
		"5.89.0.0/16", "5.90.0.0/16", "5.91.0.0/16", "5.92.0.0/16", "5.93.0.0/16",
		"5.94.0.0/16", "5.95.0.0/16", "5.96.0.0/16", "5.97.0.0/16", "5.98.0.0/16",
		"5.99.0.0/16", "5.100.0.0/16", "5.101.0.0/16", "5.102.0.0/16", "5.103.0.0/16",
		"5.104.0.0/16", "5.105.0.0/16", "5.106.0.0/16", "5.107.0.0/16", "5.108.0.0/16",
		"5.109.0.0/16", "5.110.0.0/16", "5.111.0.0/16", "5.112.0.0/16", "5.113.0.0/16",
		"5.114.0.0/16", "5.115.0.0/16", "5.116.0.0/16", "5.117.0.0/16", "5.118.0.0/16",
		"5.119.0.0/16", "5.120.0.0/16", "5.121.0.0/16", "5.122.0.0/16", "5.123.0.0/16",
		"5.124.0.0/16", "5.125.0.0/16", "5.126.0.0/16", "5.127.0.0/16",
	}
	out := make([]netip.Prefix, 0, len(values))
	for _, value := range values {
		if prefix, err := netip.ParsePrefix(value); err == nil {
			out = append(out, prefix)
		}
	}
	return out
}
