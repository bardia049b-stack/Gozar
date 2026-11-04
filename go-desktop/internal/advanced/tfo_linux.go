//go:build linux

package advanced

import (
	"syscall"

	"golang.org/x/sys/unix"
)

// tcpFastOpenConnect enables client-side TCP Fast Open (RFC 7413) so the first
// payload byte can travel in the SYN. The kernel silently performs a normal
// handshake when the peer or the local sysctl does not allow it.
const tcpFastOpenConnect = 30

func enableFastOpen(raw syscall.RawConn) error {
	var inner error
	if err := raw.Control(func(fd uintptr) {
		inner = unix.SetsockoptInt(int(fd), unix.IPPROTO_TCP, tcpFastOpenConnect, 1)
	}); err != nil {
		return err
	}
	return inner
}

// FastOpenSupported reports whether this build can request TFO at all.
func FastOpenSupported() bool { return true }
