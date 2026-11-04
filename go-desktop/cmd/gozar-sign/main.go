package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func main() {
	keyPath := flag.String("key", "", "")
	dir := flag.String("dir", ".", "")
	flag.Parse()
	if *keyPath == "" {
		fmt.Fprintln(os.Stderr, "missing -key")
		os.Exit(2)
	}
	seed, err := os.ReadFile(*keyPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
	if len(seed) != ed25519.SeedSize {
		fmt.Fprintln(os.Stderr, "key must be a 32-byte Ed25519 seed")
		os.Exit(1)
	}
	private := ed25519.NewKeyFromSeed(seed)
	entries, err := os.ReadDir(*dir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
	var manifest strings.Builder
	for _, entry := range entries {
		if entry.IsDir() || entry.Name() == "SHA256SUMS.txt" || entry.Name() == "SHA256SUMS.txt.sig" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(*dir, entry.Name()))
		if err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			os.Exit(1)
		}
		sum := sha256.Sum256(data)
		fmt.Fprintf(&manifest, "%s  %s\n", hex.EncodeToString(sum[:]), entry.Name())
	}
	if manifest.Len() == 0 {
		fmt.Fprintln(os.Stderr, "no release files")
		os.Exit(1)
	}
	body := []byte(manifest.String())
	if err := os.WriteFile(filepath.Join(*dir, "SHA256SUMS.txt"), body, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
	signature := ed25519.Sign(private, body)
	if err := os.WriteFile(filepath.Join(*dir, "SHA256SUMS.txt.sig"), signature, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
	fmt.Print(hex.EncodeToString(private.Public().(ed25519.PublicKey)))
}
