package version

import (
	"os"
	"strings"
	"testing"
)

func TestVersionMatchesReleaseTag(t *testing.T) {
	tag := os.Getenv("GOZAR_RELEASE_TAG")
	if tag == "" {
		tag = "v1.10.3"
	}
	if !strings.HasPrefix(tag, "v") {
		t.Fatalf("release tag %q must start with v", tag)
	}
	if got := strings.TrimPrefix(tag, "v"); got != Version {
		t.Fatalf("core version %s does not match release tag %s", Version, tag)
	}
}
