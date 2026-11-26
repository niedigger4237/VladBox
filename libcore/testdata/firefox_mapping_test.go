//go:build with_utls

package tls

import (
	"testing"

	utls "github.com/metacubex/utls"
)

func TestNieFiggerFirefoxFingerprint(t *testing.T) {
	for _, tc := range []struct {
		name string
		want utls.ClientHelloID
	}{
		{"firefox", utls.HelloFirefox_148},
		{"chrome", utls.HelloChrome_133},
		{"safari", utls.HelloSafari_26_3},
		{"ios", utls.HelloIOS_14},
		{"edge", utls.HelloEdge_85},
		{"android", utls.HelloAndroid_11_OkHttp},
		{"360", utls.Hello360_7_5},
		{"qq", utls.HelloQQ_11_1},
	} {
		id, err := uTLSClientHelloID(tc.name)
		if err != nil { t.Fatal(err) }
		if id.Client != tc.want.Client || id.Version != tc.want.Version {
			t.Fatalf("%s resolved to %s/%s; expected %s/%s", tc.name, id.Client, id.Version, tc.want.Client, tc.want.Version)
		}
	}
}
