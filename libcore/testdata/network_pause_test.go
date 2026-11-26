package route

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// The embedded interfaces provide only the methods used by this test.
// Any unexpected call fails rather than silently simulating success.
type niefiggerInterfaceMonitor struct {
	tun.DefaultInterfaceMonitor
	current atomic.Pointer[control.Interface]
}

func (m *niefiggerInterfaceMonitor) DefaultInterface() *control.Interface {
	return m.current.Load()
}

type niefiggerEndpointManager struct{ adapter.EndpointManager }
func (niefiggerEndpointManager) Endpoints() []adapter.Endpoint { return nil }

type niefiggerInboundManager struct{ adapter.InboundManager }
func (niefiggerInboundManager) Inbounds() []adapter.Inbound { return nil }

type niefiggerOutboundManager struct{ adapter.OutboundManager }
func (niefiggerOutboundManager) Outbounds() []adapter.Outbound { return nil }

type niefiggerRouter struct {
	adapter.Router
	reset chan struct{}
}

func (r *niefiggerRouter) ResetNetwork() { r.reset <- struct{}{} }

type niefiggerPlatformMonitor struct{ adapter.PlatformInterface }
func (niefiggerPlatformMonitor) UsePlatformDefaultInterfaceMonitor() bool { return true }

func TestNieFiggerNetworkPauseRecovery(t *testing.T) {
	ctx, cancel := context.WithCancel(pause.WithDefaultManager(context.Background()))
	defer cancel()
	monitor := &niefiggerInterfaceMonitor{}
	router := &niefiggerRouter{reset: make(chan struct{}, 2)}
	manager := &NetworkManager{
		startedCtx: ctx,
		logger: logger.NOP(),
		pauseManager: service.FromContext[pause.Manager](ctx),
		interfaceMonitor: monitor,
		endpoint: niefiggerEndpointManager{},
		inbound: niefiggerInboundManager{},
		outbound: niefiggerOutboundManager{},
		router: router,
	}
	oldCtx, oldCancel := context.WithCancel(ctx)
	defer oldCancel()
	manager.interfaceUpdateCancel = oldCancel
	// An external ROOT core has no Android VpnService platform interface.
	manager.notifyInterfaceUpdate(nil, 0)
	manager.notifyInterfaceUpdate(nil, 0)
	if oldCtx.Err() != context.Canceled || manager.interfaceUpdateCancel != nil {
		t.Fatal("stale interface update was not canceled")
	}
	if !manager.pauseManager.IsNetworkPaused() || !manager.networkResetPending {
		t.Fatal("loss of the default interface must pause the network and retain a pending reset")
	}
	select {
	case <-router.reset:
		t.Fatal("network reset ran without a default interface")
	default:
	}
	// A new upstream arrives; no service or core restart is involved.
	iif := &control.Interface{}
	iif.Index = -1
	iif.Name = "test-upstream"
	monitor.current.Store(iif)
	manager.notifyInterfaceUpdate(iif, 0)
	if manager.pauseManager.IsNetworkPaused() {
		t.Fatal("network did not wake on interface recovery")
	}
	select {
	case <-router.reset:
	case <-time.After(5 * time.Second):
		t.Fatal("network recovery did not reset old connections")
	}
	manager.resetRunAccess.Lock()
	manager.resetRunAccess.Unlock()
	manager.interfaceUpdateAccess.Lock()
	pending := manager.networkResetPending
	manager.interfaceUpdateAccess.Unlock()
	if pending {
		t.Fatal("network reset remained pending after recovery")
	}
}

func TestNieFiggerPlatformMonitorNoPause(t *testing.T) {
	ctx := pause.WithDefaultManager(context.Background())
	oldCtx, oldCancel := context.WithCancel(ctx)
	defer oldCancel()
	manager := &NetworkManager{
		startedCtx: ctx,
		logger: logger.NOP(),
		pauseManager: service.FromContext[pause.Manager](ctx),
		interfaceMonitor: &niefiggerInterfaceMonitor{},
		platformInterface: niefiggerPlatformMonitor{},
		interfaceUpdateCancel: oldCancel,
	}
	manager.notifyInterfaceUpdate(nil, 0)
	if manager.pauseManager.IsNetworkPaused() {
		t.Fatal("platform-managed VPN must not enter native network pause")
	}
	if oldCtx.Err() != context.Canceled {
		t.Fatal("stale platform interface update was not canceled")
	}
}
