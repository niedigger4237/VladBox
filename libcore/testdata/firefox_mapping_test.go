//go:build with_utls

package tls

import (
	"testing"

	utls "github.com/metacubex/utls"
)

func TestNieFiggerFirefoxFingerprint(t *testing.T) {
	id, err := uTLSClientHelloID("firefox")
	if err != nil {
		t.Fatal(err)
	}
	if id.Client != utls.HelloFirefox_148.Client || id.Version != "148" {
		t.Fatalf("firefox resolved to %s/%s", id.Client, id.Version)
	}
}
