//go:build !windows

package rpc

import "net"

func listenWindows(path string) (net.Listener, error) {
	return net.Listen("unix", path)
}

func dialSocket(path string) (net.Conn, error) {
	return net.Dial("unix", path)
}
