package libcore

import (
	"strings"
	"testing"
)

func TestLogRedaction(t *testing.T) {
	state := &logRedactionState{}
	for _, input := range []string{
		`{"private_key":"sample-secret"}`,
		`{"flowMetadataToken":"sample-secret"}`,
		"Authorization: Bearer sample-secret",
		"hysteria2://sample-secret@example.org:443",
		"https://example.org/sub/sample-secret",
	} {
		if strings.Contains(state.redact(input), "sample-secret") {
			t.Fatal("credential not removed")
		}
	}
	plain := "AWG handshake: authenticated response received\nip route 192.168.43.0/24 dev wlan2"
	if got := state.redact(plain); got != plain {
		t.Fatalf("normal diagnostic changed: %q", got)
	}
	state.redact("-----BEGIN PRIVATE KEY-----")
	if strings.Contains(state.redact("sample-secret"), "sample-secret") {
		t.Fatal("multiline private key not removed")
	}
	state.redact("-----END PRIVATE KEY-----")
	if got := state.redact("connected"); got != "connected" {
		t.Fatal("redaction state not reset")
	}
}

func TestLogCredentialContinuation(t *testing.T) {
	state := &logRedactionState{}
	for _, input := range []string{
		"password:\n\ntiny-value",
		"authentication: tiny-value",
		"authKey: tiny-value",
		"access_key: tiny-value",
		"registrationKey: tiny-value",
		"license: tiny-value",
		"{\\\"password\\\":\\\"tiny-value\\\"}",
	} {
		if strings.Contains(state.redact(input), "tiny-value") {
			t.Fatal("credential not removed")
		}
	}
	state.redact("password:")
	if strings.Contains(state.redact("tiny-value"), "tiny-value") {
		t.Fatal("credential continuation leaked")
	}
	if got := state.redact("connected"); got != "connected" {
		t.Fatalf("redaction did not reset: %q", got)
	}
}
