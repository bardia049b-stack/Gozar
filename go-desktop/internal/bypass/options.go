package bypass

import (
	"errors"
	"runtime"
)

type Options struct {
	TLSFragment      bool `json:"tls_fragment,omitempty"`
	UTLS             bool `json:"utls,omitempty"`
	WSS              bool `json:"wss,omitempty"`
	H2Multiplex      bool `json:"h2_multiplex,omitempty"`
	DoHFallback      bool `json:"doh_fallback,omitempty"`
	DoQ              bool `json:"doq,omitempty"`
	ChainSOCKS5      bool `json:"chain_socks5,omitempty"`
	DomainFronting   bool `json:"domain_fronting,omitempty"`
	ECH              bool `json:"ech,omitempty"`
	NonStandardPorts bool `json:"non_standard_ports,omitempty"`
	TFO              bool `json:"tfo,omitempty"`
	MPTCP            bool `json:"mptcp,omitempty"`
	Padding          bool `json:"padding,omitempty"`
	Jitter           bool `json:"jitter,omitempty"`
	KeepAliveTuning  bool `json:"keep_alive_tuning,omitempty"`
}

func Default() Options { return Options{} }

func (o Options) Enabled() []string {
	var out []string
	for _, item := range []struct {
		name string
		on   bool
	}{
		{"tls_fragment", o.TLSFragment},
		{"utls", o.UTLS},
		{"wss", o.WSS},
		{"h2_multiplex", o.H2Multiplex},
		{"doh_fallback", o.DoHFallback},
		{"doq", o.DoQ},
		{"chain_socks5", o.ChainSOCKS5},
		{"domain_fronting", o.DomainFronting},
		{"ech", o.ECH},
		{"non_standard_ports", o.NonStandardPorts},
		{"tfo", o.TFO},
		{"mptcp", o.MPTCP},
		{"padding", o.Padding},
		{"jitter", o.Jitter},
		{"keep_alive_tuning", o.KeepAliveTuning},
	} {
		if item.on {
			out = append(out, item.name)
		}
	}
	return out
}

func (o Options) Validate() error {
	switch {
	case o.ECH && o.DomainFronting:
		return errors.New("ech and domain_fronting cannot be combined: ech encrypts the name that domain_fronting needs to show")
	case o.DomainFronting && o.NonStandardPorts:
		return errors.New("domain_fronting needs the standard https port to keep the cover name believable")
	case o.WSS && o.H2Multiplex:
		return errors.New("wss is an http/1.1 upgrade and cannot share a connection with h2_multiplex")
	case o.TFO && o.MPTCP:
		return errors.New("tfo and mptcp cannot be combined: the kernel does not accept fast open on a multipath socket")
	case o.MPTCP && runtime.GOOS != "linux":
		return errors.New("mptcp is only available on linux")
	case o.TFO && runtime.GOOS != "linux" && runtime.GOOS != "darwin":
		return errors.New("tfo is only available on linux and macos")
	case o.ECH && o.WSS:
		return errors.New("ech needs a direct tls handshake and cannot run inside the wss upgrade")
	}
	return nil
}

func FallbackOrder() []string {
	return []string{"tls_fragment", "utls", "standard"}
}
