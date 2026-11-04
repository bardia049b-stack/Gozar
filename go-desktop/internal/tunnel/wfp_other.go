//go:build !windows

package tunnel

type WFP struct{}

func NewWFP(string) (*WFP, error) { return nil, nil }

func (w *WFP) Close() error { return nil }
