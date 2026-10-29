//go:build windows

package wintun

import (
	"bytes"
	_ "embed"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

//go:embed bin/wintun_amd64.dll
var library []byte

func Ensure() error {
	executable, err := os.Executable()
	if err == nil {
		target := filepath.Join(filepath.Dir(executable), "wintun.dll")
		if current, err := os.ReadFile(target); err != nil || !bytes.Equal(current, library) {
			if err := os.WriteFile(target, library, 0o644); err == nil {
				return nil
			}
		} else {
			return nil
		}
	}
	folder := filepath.Join(os.Getenv("LOCALAPPDATA"), "Gozar")
	if err := os.MkdirAll(folder, 0o755); err != nil {
		return err
	}
	target := filepath.Join(folder, "wintun.dll")
	if err := os.WriteFile(target, library, 0o644); err != nil {
		return err
	}
	return windows.SetDllDirectory(folder)
}
