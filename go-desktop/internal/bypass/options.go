package bypass

import "encoding/json"

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
	_, err := json.Marshal(o)
	return err
}

func FallbackOrder() []string {
	return []string{"tls_fragment", "utls", "standard"}
}
