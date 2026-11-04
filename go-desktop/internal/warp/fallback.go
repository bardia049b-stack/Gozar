package warp

import (
	"context"
	"net"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
)

type DialResult struct {
	Packet *connectip.Conn
	TCP    net.Conn
}

func (r *DialResult) Close() error {
	if r == nil {
		return nil
	}
	if r.Packet != nil {
		return r.Packet.Close()
	}
	if r.TCP != nil {
		return r.TCP.Close()
	}
	return nil
}

func (c *Client) DialWithFallback(ctx context.Context, timeout time.Duration, tcpDial func(context.Context) (net.Conn, error)) (*DialResult, error) {
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	warpCtx, cancel := context.WithTimeout(ctx, timeout)
	conn, err := c.Dial(warpCtx)
	cancel()
	if err == nil {
		return &DialResult{Packet: conn}, nil
	}
	if tcpDial == nil {
		return nil, err
	}
	tcp, fallbackErr := tcpDial(ctx)
	if fallbackErr != nil {
		return nil, fallbackErr
	}
	return &DialResult{TCP: tcp}, nil
}
