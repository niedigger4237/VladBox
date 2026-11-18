package libcore

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/provider"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"

	box "github.com/sagernet/sing-box"
)

// Since the core moved to sing-box-extended (>= 1.14) the registries are taken
// from the upstream `include` package. Which protocols are really compiled in
// is controlled by build tags (see libcore/build.sh); everything else becomes a
// stub that reports "not included", so the registries stay in sync with the
// core automatically on every update.

func nekoboxAndroidInboundRegistry() *inbound.Registry   { return include.InboundRegistry() }
func nekoboxAndroidOutboundRegistry() *outbound.Registry { return include.OutboundRegistry() }
func nekoboxAndroidEndpointRegistry() *endpoint.Registry { return include.EndpointRegistry() }
func nekoboxAndroidProviderRegistry() *provider.Registry { return include.ProviderRegistry() }
func nekoboxAndroidServiceRegistry() *service.Registry   { return include.ServiceRegistry() }
func nekoboxAndroidCertificateProviderRegistry() *certificate.Registry {
	return include.CertificateProviderRegistry()
}

func nekoboxAndroidDNSTransportRegistry(localTransport LocalDNSTransport) *dns.TransportRegistry {
	registry := include.DNSTransportRegistry()
	if localTransport != nil {
		// Replace the stock "local" resolver with the one backed by Android's
		// ConnectivityManager (handled by the Java side).
		dns.RegisterTransport(registry, "local", func(ctx context.Context, logger log.ContextLogger, tag string, options option.LocalDNSServerOptions) (adapter.DNSTransport, error) {
			return newPlatformTransport(localTransport, tag, options), nil
		})
	}
	return registry
}

func nekoboxAndroidContext(ctx context.Context, localTransport LocalDNSTransport) context.Context {
	return box.Context(ctx,
		nekoboxAndroidInboundRegistry(),
		nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidProviderRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport),
		nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
}
