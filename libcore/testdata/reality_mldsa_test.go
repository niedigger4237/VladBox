//go:build with_utls

package tls

import (
	"crypto/hmac"
	"crypto/sha512"
	"testing"

	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
)

func TestNieFiggerRealityMldsa65(t *testing.T) {
	pk, sk, err := mldsa65.GenerateKey(nil)
	if err != nil { t.Fatal(err) }
	auth := []byte("test authentication key")
	pub := make([]byte, 32)
	client := []byte("client hello")
	server := []byte("server hello")
	h := hmac.New(sha512.New, auth)
	h.Write(pub)
	h.Write(client)
	h.Write(server)
	sig := make([]byte, mldsa65.SignatureSize)
	if err := mldsa65.SignTo(sk, h.Sum(nil), nil, false, sig); err != nil { t.Fatal(err) }
	if !verifyRealityMldsa65(pk, auth, pub, client, server, sig) { t.Fatal("valid signature rejected") }
	if verifyRealityMldsa65(pk, auth, pub, client, []byte("modified hello"), sig) { t.Fatal("modified transcript accepted") }
	if verifyRealityMldsa65(pk, auth, pub, client, server, nil) { t.Fatal("missing signature accepted") }
	sig[0] ^= 1
	if verifyRealityMldsa65(pk, auth, pub, client, server, sig) { t.Fatal("corrupt signature accepted") }
}
