//go:build windows

package rpc

import (
	"net"

	"github.com/Microsoft/go-winio"
)

func listenWindows(path string) (net.Listener, error) {
	return winio.ListenPipe(path, &winio.PipeConfig{SecurityDescriptor: "D:P(A;;GA;;;SY)(A;;GA;;;BA)"})
}

func dialSocket(path string) (net.Conn, error) {
	return winio.DialPipe(path, nil)
}
