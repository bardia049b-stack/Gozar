//go:build windows

package tunnel

import (
	"os"
	"testing"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tundev"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/wintun"
)

func TestWFPFilterGUIDs(t *testing.T) {
	if layerAleAuthConnectV4.Data1 != 0xc38d57d1 || layerAleAuthConnectV6.Data1 != 0x4a72393b {
		t.Fatal("unexpected ALE connect layer GUID")
	}
	if condIPRemotePort.Data1 != 0xc35a604d || condIPLocalInterface.Data1 != 0x4cd62a49 {
		t.Fatal("unexpected WFP condition GUID")
	}
}

func TestWFPLiveAdapter(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_WFP") != "1" {
		t.Skip("set GOZAR_LIVE_WFP=1 to run the live WFP adapter test")
	}
	if !tundev.Privileged() {
		t.Skip("live WFP test requires administrator privileges")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	device, err := tundev.Open(1280)
	if err != nil {
		t.Fatal(err)
	}
	defer device.Close()
	guard, err := NewWFP(device.Name())
	if err != nil {
		t.Fatal(err)
	}
	if err := guard.Close(); err != nil {
		t.Fatal(err)
	}
}
