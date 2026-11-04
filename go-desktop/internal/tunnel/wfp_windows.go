package tunnel

import (
	"errors"
	"fmt"
	"net"
	"runtime"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	sessionName        = "GozarVPN"
	fwpmSessionDynamic = 0x00000001
	rpcCAuthnWinNT     = 10
	fwpMatchEqual      = 0
	fwpActionBlock     = 0x1001
	fwpActionPermit    = 0x1002
	fwpUint8           = 1
	fwpUint16          = 2
	fwpUint32          = 3
	fwpUint64          = 4
)

type wfpDisplayData struct {
	Name        *uint16
	Description *uint16
}

type wfpValue struct {
	Type  uint32
	_     uint32
	Value uint64
}

type wfpByteBlob struct {
	Size uint32
	_    uint32
	Data *byte
}

type wfpSession struct {
	SessionKey     windows.GUID
	DisplayData    wfpDisplayData
	Flags          uint32
	TxnWaitTimeout uint32
	ProcessID      uint32
	SID            *windows.SID
	Username       *uint16
	KernelMode     int32
}

type wfpFilterCondition struct {
	FieldKey       windows.GUID
	MatchType      uint32
	ConditionValue wfpValue
}

type wfpAction struct {
	Type       uint32
	FilterType windows.GUID
}

type wfpFilter struct {
	FilterKey           windows.GUID
	DisplayData         wfpDisplayData
	Flags               uint32
	ProviderKey         *windows.GUID
	ProviderData        wfpByteBlob
	LayerKey            windows.GUID
	SubLayerKey         windows.GUID
	Weight              wfpValue
	NumFilterConditions uint32
	FilterCondition     *wfpFilterCondition
	Action              wfpAction
	RawContext          uint64
	Reserved            *windows.GUID
	FilterID            uint64
	EffectiveWeight     wfpValue
}

type wfpSublayer struct {
	SubLayerKey  windows.GUID
	DisplayData  wfpDisplayData
	Flags        uint32
	ProviderKey  *windows.GUID
	ProviderData wfpByteBlob
	Weight       uint16
}

var (
	fwpuclnt                   = windows.NewLazySystemDLL("fwpuclnt.dll")
	procFwpmEngineOpen0        = fwpuclnt.NewProc("FwpmEngineOpen0")
	procFwpmEngineClose0       = fwpuclnt.NewProc("FwpmEngineClose0")
	procFwpmTransactionBegin0  = fwpuclnt.NewProc("FwpmTransactionBegin0")
	procFwpmTransactionCommit0 = fwpuclnt.NewProc("FwpmTransactionCommit0")
	procFwpmTransactionAbort0  = fwpuclnt.NewProc("FwpmTransactionAbort0")
	procFwpmSubLayerAdd0       = fwpuclnt.NewProc("FwpmSubLayerAdd0")
	procFwpmFilterAdd0         = fwpuclnt.NewProc("FwpmFilterAdd0")
)

func wfpGUID(a uint32, b uint16, c uint16, d [8]byte) windows.GUID {
	return windows.GUID{Data1: a, Data2: b, Data3: c, Data4: d}
}

var (
	layerAleAuthConnectV4 = wfpGUID(0xc38d57d1, 0x05a7, 0x4c33, [8]byte{0x90, 0x4f, 0x7f, 0xbc, 0xee, 0xe6, 0x0e, 0x82})
	layerAleAuthConnectV6 = wfpGUID(0x4a72393b, 0x319f, 0x44bc, [8]byte{0x84, 0xc3, 0xba, 0x54, 0xdc, 0xb3, 0xb6, 0xb4})
	condIPRemotePort      = wfpGUID(0xc35a604d, 0xd22b, 0x4e1a, [8]byte{0x91, 0xb4, 0x68, 0xf6, 0x74, 0xee, 0x67, 0x4b})
	condIPRemoteAddress   = wfpGUID(0x667fd755, 0xd695, 0x434a, [8]byte{0x8a, 0xf5, 0xd3, 0x83, 0x5a, 0x13, 0x2c, 0x1b})
	condIPProtocol        = wfpGUID(0x3971ef2b, 0x623e, 0x4f9a, [8]byte{0x8c, 0xb1, 0x6e, 0x79, 0xb8, 0x06, 0xb9, 0xa7})
	condIPLocalInterface  = wfpGUID(0x4cd62a49, 0x59c3, 0x4969, [8]byte{0xb7, 0xf3, 0xbd, 0xa5, 0xd3, 0x28, 0x90, 0xa4})
	gozarSublayer         = wfpGUID(0x476f7a61, 0x7256, 0x504e, [8]byte{0x53, 0x75, 0x62, 0x6c, 0x61, 0x79, 0x65, 0x72})
)

type WFP struct {
	mu     sync.Mutex
	handle uintptr
	closed bool
}

func wfpName(text string) *uint16 {
	p, err := windows.UTF16PtrFromString(text)
	if err != nil {
		return nil
	}
	return p
}

func wfpResult(ret uintptr) error {
	if ret == 0 {
		return nil
	}
	return syscall.Errno(ret)
}

func NewWFP(tunInterface string) (*WFP, error) {
	name := wfpName(sessionName)
	session := &wfpSession{
		DisplayData: wfpDisplayData{Name: name, Description: name},
		Flags:       fwpmSessionDynamic,
	}
	var handle uintptr
	ret, _, _ := procFwpmEngineOpen0.Call(0, rpcCAuthnWinNT, 0, uintptr(unsafe.Pointer(session)), uintptr(unsafe.Pointer(&handle)))
	if err := wfpResult(ret); err != nil {
		return nil, fmt.Errorf("FwpmEngineOpen0: %w", err)
	}
	w := &WFP{handle: handle}
	if err := w.install(tunInterface); err != nil {
		w.Close()
		return nil, err
	}
	return w, nil
}

func (w *WFP) install(tunInterface string) error {
	ret, _, _ := procFwpmTransactionBegin0.Call(w.handle, 0)
	if err := wfpResult(ret); err != nil {
		return fmt.Errorf("FwpmTransactionBegin0: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			procFwpmTransactionAbort0.Call(w.handle)
		}
	}()
	name := wfpName(sessionName)
	sublayer := &wfpSublayer{
		SubLayerKey: gozarSublayer,
		DisplayData: wfpDisplayData{Name: name, Description: name},
		Flags:       0,
		Weight:      0xffff,
	}
	ret, _, _ = procFwpmSubLayerAdd0.Call(w.handle, uintptr(unsafe.Pointer(sublayer)), 0)
	if err := wfpResult(ret); err != nil {
		return fmt.Errorf("FwpmSubLayerAdd0: %w", err)
	}
	tunLUID, err := interfaceLUID(tunInterface)
	if err != nil {
		return err
	}
	if err := w.addDNSFilters(); err != nil {
		return err
	}
	if err := w.addIPv6Filters(tunInterface, tunLUID); err != nil {
		return err
	}
	ret, _, _ = procFwpmTransactionCommit0.Call(w.handle)
	if err := wfpResult(ret); err != nil {
		return fmt.Errorf("FwpmTransactionCommit0: %w", err)
	}
	committed = true
	return nil
}

func (w *WFP) addFilter(layer windows.GUID, action uint32, weight uint64, conditions []wfpFilterCondition, label string) error {
	name := wfpName(label)
	// FWP_VALUE0 stores UINT64 by pointer, not inline.
	weightValue := weight
	filter := &wfpFilter{
		DisplayData: wfpDisplayData{Name: name, Description: name},
		LayerKey:    layer,
		SubLayerKey: gozarSublayer,
		Weight:      wfpValue{Type: fwpUint64, Value: uint64(uintptr(unsafe.Pointer(&weightValue)))},
		Action:      wfpAction{Type: action},
	}
	var kept []*uint64
	var conds []wfpFilterCondition
	if len(conditions) > 0 {
		conds = make([]wfpFilterCondition, len(conditions))
		copy(conds, conditions)
		for i := range conds {
			if conds[i].ConditionValue.Type == fwpUint64 {
				v := conds[i].ConditionValue.Value
				p := &v
				kept = append(kept, p)
				conds[i].ConditionValue.Value = uint64(uintptr(unsafe.Pointer(p)))
			}
		}
		filter.NumFilterConditions = uint32(len(conds))
		filter.FilterCondition = &conds[0]
	}
	var id uint64
	ret, _, _ := procFwpmFilterAdd0.Call(w.handle, uintptr(unsafe.Pointer(filter)), 0, uintptr(unsafe.Pointer(&id)))
	runtime.KeepAlive(weightValue)
	runtime.KeepAlive(kept)
	runtime.KeepAlive(conds)
	runtime.KeepAlive(filter)
	if err := wfpResult(ret); err != nil {
		return fmt.Errorf("FwpmFilterAdd0(%s): %w", label, err)
	}
	return nil
}

func uint8Condition(field windows.GUID, value uint8) wfpFilterCondition {
	return wfpFilterCondition{FieldKey: field, MatchType: fwpMatchEqual, ConditionValue: wfpValue{Type: fwpUint8, Value: uint64(value)}}
}

func uint16Condition(field windows.GUID, value uint16) wfpFilterCondition {
	return wfpFilterCondition{FieldKey: field, MatchType: fwpMatchEqual, ConditionValue: wfpValue{Type: fwpUint16, Value: uint64(value)}}
}

func uint32Condition(field windows.GUID, value uint32) wfpFilterCondition {
	return wfpFilterCondition{FieldKey: field, MatchType: fwpMatchEqual, ConditionValue: wfpValue{Type: fwpUint32, Value: uint64(value)}}
}

func uint64Condition(field windows.GUID, value uint64) wfpFilterCondition {
	return wfpFilterCondition{FieldKey: field, MatchType: fwpMatchEqual, ConditionValue: wfpValue{Type: fwpUint64, Value: value}}
}

func (w *WFP) addDNSFilters() error {
	internalDNS := net.ParseIP("10.111.0.53").To4()
	if internalDNS == nil {
		return errors.New("invalid internal DNS address")
	}
	address := uint32(internalDNS[0])<<24 | uint32(internalDNS[1])<<16 | uint32(internalDNS[2])<<8 | uint32(internalDNS[3])
	for _, protocol := range []uint16{17, 6} {
		permit := []wfpFilterCondition{
			uint16Condition(condIPRemotePort, 53),
			uint8Condition(condIPProtocol, protocol),
			uint32Condition(condIPRemoteAddress, address),
		}
		if err := w.addFilter(layerAleAuthConnectV4, fwpActionPermit, 300, permit, "Gozar permit internal DNS"); err != nil {
			return err
		}
		block := []wfpFilterCondition{
			uint16Condition(condIPRemotePort, 53),
			uint8Condition(condIPProtocol, protocol),
		}
		if err := w.addFilter(layerAleAuthConnectV4, fwpActionBlock, 200, block, "Gozar block external DNS"); err != nil {
			return err
		}
	}
	return nil
}

func (w *WFP) addIPv6Filters(tunName string, tunLUID uint64) error {
	permit := []wfpFilterCondition{uint64Condition(condIPLocalInterface, tunLUID)}
	if err := w.addFilter(layerAleAuthConnectV6, fwpActionPermit, 300, permit, "Gozar permit tunnel IPv6"); err != nil {
		return err
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return err
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || iface.Name == tunName {
			continue
		}
		luid, err := interfaceLUID(iface.Name)
		if err != nil || luid == tunLUID {
			continue
		}
		block := []wfpFilterCondition{uint64Condition(condIPLocalInterface, luid)}
		if err := w.addFilter(layerAleAuthConnectV6, fwpActionBlock, 200, block, "Gozar block physical IPv6"); err != nil {
			return err
		}
	}
	return nil
}

func interfaceLUID(name string) (uint64, error) {
	iface, err := net.InterfaceByName(name)
	if err != nil {
		return 0, err
	}
	row := windows.MibIfRow2{InterfaceIndex: uint32(iface.Index)}
	if err := windows.GetIfEntry2Ex(windows.MibIfEntryNormalWithoutStatistics, &row); err != nil {
		return 0, err
	}
	return row.InterfaceLuid, nil
}

func (w *WFP) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed || w.handle == 0 {
		w.closed = true
		return nil
	}
	w.closed = true
	ret, _, _ := procFwpmEngineClose0.Call(w.handle)
	w.handle = 0
	return wfpResult(ret)
}
