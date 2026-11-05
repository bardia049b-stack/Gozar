package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/version"
)

func TestVersionLineMatchesTag(t *testing.T) {
	tag := os.Getenv("GOZAR_RELEASE_TAG")
	if tag == "" {
		tag = "v1.10.4"
	}
	want := "Gozar " + strings.TrimPrefix(tag, "v")
	if got := versionLine(); got != want {
		t.Fatalf("--version line = %q, want %q", got, want)
	}
	if version.Version != strings.TrimPrefix(tag, "v") {
		t.Fatalf("version package mismatch")
	}
}

func TestVersionFlagOutput(t *testing.T) {
	if testing.Short() {
		t.Skip("builds the core binary")
	}
	name := "gozar-core"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	exe := filepath.Join(t.TempDir(), name)
	build := exec.Command("go", "build", "-o", exe, ".")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	out, err := exec.Command(exe, "--version").CombinedOutput()
	if err != nil {
		t.Fatalf("--version: %v\n%s", err, out)
	}
	tag := os.Getenv("GOZAR_RELEASE_TAG")
	if tag == "" {
		tag = "v1.10.4"
	}
	want := "Gozar " + strings.TrimPrefix(tag, "v")
	if strings.TrimSpace(string(out)) != want {
		t.Fatalf("--version = %q, want %q", strings.TrimSpace(string(out)), want)
	}
}
