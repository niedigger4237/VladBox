//go:build with_utls

package tls

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	stdtls "crypto/tls"
	"errors"
	"net"
	"strings"
	"testing"
	"time"

	utls "github.com/metacubex/utls"
)

// Exercise the production REALITY ClientHandshake rather than only the
// fingerprint resolver. The test server deliberately aborts after ClientHello;
// it does not impersonate a REALITY server or test certificate authentication.
func TestNieFiggerRealityHybridOffer(t *testing.T) {
	for _, fingerprint := range []string{"chrome", "firefox"} {
		for _, enabled := range []bool{false, true} {
			name := "disabled"
			if enabled { name = "automatic" }
			t.Run(fingerprint+"/"+name, func(t *testing.T) {
				id, err := uTLSClientHelloID(fingerprint)
				if err != nil { t.Fatal(err) }
				key, err := ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil { t.Fatal(err) }
				config := &RealityClientConfig{
					ctx: context.Background(),
					uClient: &UTLSClientConfig{
						config: &utls.Config{ServerName: "example.test"},
						serverName: "example.test",
						id: id,
					},
					publicKey: key.PublicKey().Bytes(),
					supportX25519MLKEM768: enabled,
				}
				client, server := net.Pipe()
				defer client.Close()
				defer server.Close()
				deadline := time.Now().Add(3 * time.Second)
				if err := client.SetDeadline(deadline); err != nil { t.Fatal(err) }
				if err := server.SetDeadline(deadline); err != nil { t.Fatal(err) }
				observed := make(chan bool, 1)
				done := make(chan error, 1)
				go func() {
					peer := stdtls.Server(server, &stdtls.Config{
						GetConfigForClient: func(info *stdtls.ClientHelloInfo) (*stdtls.Config, error) {
							found := false
							for _, group := range info.SupportedCurves {
								if uint16(group) == uint16(utls.X25519MLKEM768) { found = true }
							}
							observed <- found
							return nil, errors.New("test completed")
						},
					})
					done <- peer.Handshake()
				}()
				ctx, cancel := context.WithDeadline(context.Background(), deadline)
				defer cancel()
				_, err = config.ClientHandshake(ctx, client)
				if err == nil { t.Fatal("test server must reject handshake") }
				client.Close()
				<-done
				select {
				case found := <-observed:
					if found != enabled { t.Fatalf("hybrid group present=%v want=%v", found, enabled) }
				default:
					t.Fatalf("REALITY failed before sending ClientHello: %v", err)
				}
			})
		}
	}
}

func TestNieFiggerRealityHybridRequiredRejectsUnsupportedFingerprint(t *testing.T) {
	id, err := uTLSClientHelloID("android")
	if err != nil { t.Fatal(err) }
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	client.SetDeadline(time.Now().Add(time.Second))
	config := &RealityClientConfig{
		ctx: ctx,
		uClient: &UTLSClientConfig{config: &utls.Config{ServerName: "example.test"}, serverName: "example.test", id: id},
		supportX25519MLKEM768: true,
		requireX25519MLKEM768: true,
	}
	_, err = config.ClientHandshake(ctx, client)
	if err == nil || !strings.Contains(err.Error(), "fingerprint does not offer X25519MLKEM768") {
		t.Fatalf("expected explicit hybrid fingerprint rejection, got %v", err)
	}
}
