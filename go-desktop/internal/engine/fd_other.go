//go:build !linux && !android

package engine

import "errors"

type FDOptions struct {
	MTU     uint32
	Packets PacketDialer
	Address [4]byte
}

func StartFD(fd int, dial Dialer, options FDOptions) (*Engine, error) {
	return nil, errors.New("the file descriptor tunnel is only available on Linux and Android")
}
