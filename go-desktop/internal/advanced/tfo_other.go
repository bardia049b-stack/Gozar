//go:build !linux

package advanced

import "syscall"

// enableFastOpen is a no-op outside Linux: the dial still succeeds, it just
// uses the normal three-way handshake. This is the documented fallback.
func enableFastOpen(raw syscall.RawConn) error { return nil }

// FastOpenSupported reports whether this build can request TFO at all.
func FastOpenSupported() bool { return false }
