package rpc

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/app"
)

func TestControlSocket(t *testing.T) {
	current := app.New("test")
	defer current.Close()
	path := filepath.Join(t.TempDir(), "control.sock")
	if runtime.GOOS == "windows" {
		path = fmt.Sprintf(`\\.\pipe\gozar-test-%d`, os.Getpid())
	}
	listener, err := Listen(current, path)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go Serve(current, listener)
	response, err := RequestOnce(path, Request{ID: 7, Method: "state"})
	if err != nil {
		t.Fatal(err)
	}
	if response.ID != 7 || response.State == nil || response.State.Version != "test" {
		t.Fatalf("bad response: %+v", response)
	}
}
