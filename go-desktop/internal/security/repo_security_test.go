package security

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// The repository must not ship private keys or personal access tokens. Test
// fixtures are excluded; production source, scripts, workflows and packaging
// are scanned.
func TestNoPrivateKeysOrTokensInProductionFiles(t *testing.T) {
	root := "../../.."
	patterns := []*regexp.Regexp{
		regexp.MustCompile(`-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----`),
		regexp.MustCompile(`ghp_[A-Za-z0-9]{30,}`),
		regexp.MustCompile(`github_pat_[A-Za-z0-9_]{30,}`),
		regexp.MustCompile(`(?i)ed25519[_-]?seed\s*[:=]\s*["'][A-Za-z0-9+/=]{32,}["']`),
	}
	skipDirs := map[string]bool{
		".git": true, "node_modules": true, "build": true, ".dart_tool": true,
		".gradle": true, "testdata": true, "out": true, "dist": true,
	}
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			if skipDirs[entry.Name()] {
				return filepath.SkipDir
			}
			return nil
		}
		name := entry.Name()
		if strings.HasSuffix(name, "_test.go") || strings.HasSuffix(name, "Test.kt") || strings.HasSuffix(name, "_test.dart") {
			return nil
		}
		switch filepath.Ext(name) {
		case ".go", ".kt", ".java", ".dart", ".sh", ".py", ".yml", ".yaml", ".gradle", ".nsi", ".rc", ".init":
		default:
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		text := string(data)
		for _, pattern := range patterns {
			if pattern.MatchString(text) {
				t.Errorf("secret-like material found in %s", path)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
