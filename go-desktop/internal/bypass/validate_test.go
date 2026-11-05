package bypass

import (
	"runtime"
	"strings"
	"testing"
)

func TestValidateRejectsConflicts(t *testing.T) {
	cases := []struct {
		name    string
		options Options
		want    string
	}{
		{"ech with fronting", Options{ECH: true, DomainFronting: true}, "cannot be combined"},
		{"fronting on odd port", Options{DomainFronting: true, NonStandardPorts: true}, "standard https port"},
		{"wss with h2", Options{WSS: true, H2Multiplex: true}, "http/1.1 upgrade"},
		{"tfo with mptcp", Options{TFO: true, MPTCP: true}, "fast open"},
		{"ech inside wss", Options{ECH: true, WSS: true}, "direct tls handshake"},
	}
	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			err := item.options.Validate()
			if err == nil {
				t.Fatalf("%+v was accepted", item.options)
			}
			if !strings.Contains(err.Error(), item.want) {
				t.Fatalf("error %q does not mention %q", err, item.want)
			}
		})
	}
}

func TestValidateAcceptsWorkingSets(t *testing.T) {
	sets := []Options{
		{},
		{TLSFragment: true, UTLS: true},
		{DoQ: true, DoHFallback: true},
		{Padding: true, Jitter: true, KeepAliveTuning: true},
		{WSS: true, TLSFragment: true},
		{ECH: true, Padding: true},
		{DomainFronting: true, UTLS: true},
		{H2Multiplex: true, Padding: true, Jitter: true},
	}
	if runtime.GOOS == "linux" {
		sets = append(sets, Options{MPTCP: true}, Options{TFO: true})
	}
	for index, options := range sets {
		if err := options.Validate(); err != nil {
			t.Fatalf("set %d (%v) was rejected: %v", index, options.Enabled(), err)
		}
	}
}

func TestValidatePlatformLimits(t *testing.T) {
	err := Options{MPTCP: true}.Validate()
	if runtime.GOOS == "linux" {
		if err != nil {
			t.Fatalf("mptcp must be allowed on linux: %v", err)
		}
	} else if err == nil {
		t.Fatalf("mptcp must be rejected on %s", runtime.GOOS)
	}
	err = Options{TFO: true}.Validate()
	if runtime.GOOS == "linux" || runtime.GOOS == "darwin" {
		if err != nil {
			t.Fatalf("tfo must be allowed on %s: %v", runtime.GOOS, err)
		}
	} else if err == nil {
		t.Fatalf("tfo must be rejected on %s", runtime.GOOS)
	}
}
