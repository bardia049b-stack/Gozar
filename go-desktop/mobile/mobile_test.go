package mobile

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/advanced"
)

func TestParseSettingsDefaults(t *testing.T) {
	config, err := parseSettings(`{"proxy_host":"example.org","pass":"secret"}`)
	if err != nil {
		t.Fatal(err)
	}
	if config.ProxyPort != 443 || config.MTU != 1280 || config.TimeoutMS != 12000 {
		t.Fatalf("defaults were not applied: %+v", config)
	}
	if config.UDP {
		t.Fatal("udp must stay off unless it is asked for")
	}
}

func TestParseSettingsGameModeEnablesUDP(t *testing.T) {
	config, err := parseSettings(`{"proxy_host":"example.org","game_mode":true,"warp":true}`)
	if err != nil {
		t.Fatal(err)
	}
	if !config.UDP {
		t.Fatal("game mode must turn the datagram relay on")
	}
}

func TestParseSettingsRejectsBadInput(t *testing.T) {
	cases := []string{
		``,
		`{`,
		`{"proxy_host":"example.org","mtu":10}`,
		`{"proxy_host":"example.org","mtu":90000}`,
		`{"proxy_host":"example.org","advanced":{"ech":true,"domain_fronting":true}}`,
		`{"proxy_host":"example.org","advanced":{"wss":true,"h2_multiplex":true}}`,
	}
	for _, raw := range cases {
		if _, err := parseSettings(raw); err == nil {
			t.Fatalf("%q was accepted", raw)
		}
	}
}

func TestParseSettingsRequiresProxy(t *testing.T) {
	if _, err := parseSettings(`{"pass":"x"}`); err == nil {
		t.Fatal("a tunnel without a proxy host was accepted")
	}
}

func TestValidateNeedsParams(t *testing.T) {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	list, err := advanced.BuildECHConfigList(advanced.ECHConfigSpec{ConfigID: 7, KEMID: 0x0020, PublicKey: key.PublicKey().Bytes(), PublicName: "cover.example"})
	if err != nil {
		t.Fatal(err)
	}
	encoded := base64.StdEncoding.EncodeToString(list)
	if msg := Validate(`{"proxy_host":"a","advanced":{"ech":true}}`); !strings.Contains(msg, "ech_config_list") {
		t.Fatalf("ech without a config list reported %q", msg)
	}
	if msg := Validate(`{"proxy_host":"a","advanced":{"domain_fronting":true}}`); !strings.Contains(msg, "front_host") {
		t.Fatalf("fronting without a host reported %q", msg)
	}
	if msg := Validate(`{"proxy_host":"a","advanced":{"ech":true},"params":{"ech_config_list":"` + encoded + `"}}`); msg != "" {
		t.Fatalf("a complete ech setup reported %q", msg)
	}
}

func TestStatsWhenStopped(t *testing.T) {
	var state map[string]any
	if err := json.Unmarshal([]byte(Stats()), &state); err != nil {
		t.Fatal(err)
	}
	if state["running"] != false {
		t.Fatal("stats must report a stopped tunnel")
	}
}

func TestStartRejectsBadDescriptor(t *testing.T) {
	if err := Start(-1, `{"proxy_host":"example.org"}`); err == nil {
		t.Fatal("a negative descriptor was accepted")
	}
	if Running() {
		t.Fatal("a failed start left a session behind")
	}
}

func TestSetAdvancedNeedsRunningTunnel(t *testing.T) {
	if err := SetAdvanced(`{"advanced":{"padding":true}}`); err == nil {
		t.Fatal("advanced options were applied without a tunnel")
	}
	if err := SetUDP(true); err == nil {
		t.Fatal("udp was changed without a tunnel")
	}
}
