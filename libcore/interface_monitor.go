package libcore

import (
	"net"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// wtf

type interfaceMonitorStub struct{}

func (s *interfaceMonitorStub) Start() error {
	return nil
}

func (s *interfaceMonitorStub) Close() error {
	return nil
}

// The platform (Android) owns the real default network: sockets are protected through VpnService and
// are not bound by name. A nil here makes the network manager report "missing default interface" and
// pause every WireGuard/AWG endpoint right after start, so hand out a placeholder instead.
var placeholderInterface = &control.Interface{Index: 1, MTU: 1500, Name: "android-default", Flags: net.FlagUp}

func (s *interfaceMonitorStub) DefaultInterface() *control.Interface {
	return placeholderInterface
}

func (s *interfaceMonitorStub) OverrideAndroidVPN() bool {
	return false
}

func (s *interfaceMonitorStub) AndroidVPNEnabled() bool {
	return false
}

func (s *interfaceMonitorStub) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}

func (s *interfaceMonitorStub) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
}

func (s *interfaceMonitorStub) RegisterMyInterface(interfaceName string) {
}

func (s *interfaceMonitorStub) MyInterfaces() []string {
	return nil
}
