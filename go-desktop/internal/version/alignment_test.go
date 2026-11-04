package version

import (
	"os"
	"regexp"
	"strings"
	"testing"
)

func TestDesktopAndAndroidVersionsAligned(t *testing.T) {
	root := "../../.."
	pubspec, err := os.ReadFile(root + "/desktop_flutter/pubspec.yaml")
	if err != nil {
		t.Fatal(err)
	}
	m := regexp.MustCompile(`(?m)^version:\s*([0-9]+\.[0-9]+\.[0-9]+)`).FindSubmatch(pubspec)
	if m == nil || string(m[1]) != Version {
		t.Fatalf("pubspec version %q does not match core %s", m, Version)
	}
	gradle, err := os.ReadFile(root + "/app/build.gradle")
	if err != nil {
		t.Fatal(err)
	}
	g := regexp.MustCompile(`versionName\s+'([^']+)'`).FindSubmatch(gradle)
	if g == nil || string(g[1]) != Version {
		t.Fatalf("Android versionName %q does not match core %s", g, Version)
	}
	mainDart, err := os.ReadFile(root + "/desktop_flutter/lib/main.dart")
	if err == nil && strings.Contains(string(mainDart), "const current = '") {
		d := regexp.MustCompile(`const current = '([^']+)'`).FindSubmatch(mainDart)
		if d == nil || string(d[1]) != Version {
			t.Fatalf("Flutter current version %q does not match core %s", d, Version)
		}
	}
}
