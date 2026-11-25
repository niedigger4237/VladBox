package libcore

import (
	"io"
	"regexp"
	"strings"
	"sync"
)

// Keep this policy consistent with the Android LogRedactor. Never modify
// protocol traffic or config files: this filter is only for diagnostic sinks.
var (
	logCredential = regexp.MustCompile(`(?i)(private[\s_-]*key|pre[\s_-]*shared[\s_-]*key|password|passwd|passphrase|secret|token|authorization|cookie|api[\s_-]*key|license[\s_-]*key|credential|subscription[\s_-]*(url|link)|psk|uuid|auth|user[\s_-]*id|auth[\s_-]*str|auth[\s_-]*string|username|user[\s_-]*info|short[\s_-]*id)\s*["']?\s*([:=]|->|\s+["'])`)
	logBearer = regexp.MustCompile(`(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+`)
	logURI = regexp.MustCompile(`(?i)\b(https?|vless|vmess|ss|ssr|trojan|hysteria2?|hy2|tuic|socks5?h?|wireguard|wg|awg|ssh|naive(\+https)?)://[^\s<>"']+`)
	logUUID = regexp.MustCompile(`(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b`)
	logOpaque = regexp.MustCompile(`[A-Za-z0-9_+/-]{32,}={0,2}`)
	logPEMBegin = regexp.MustCompile(`-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----`)
	logPEMEnd = regexp.MustCompile(`-----END [A-Z0-9 ]*PRIVATE KEY-----`)
)

type logRedactionState struct {
	mu sync.Mutex
	inPrivateKey bool
}

func (s *logRedactionState) redact(message string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	lines := strings.Split(message, "\n")
	for i, line := range lines {
		if logPEMBegin.MatchString(line) {
			s.inPrivateKey = true
		}
		if s.inPrivateKey {
			if logPEMEnd.MatchString(line) {
				s.inPrivateKey = false
			}
			lines[i] = "[private key material omitted]"
			continue
		}
		if len(line) > 65536 || logCredential.MatchString(line) || logPEMEnd.MatchString(line) {
			lines[i] = "[credential-bearing or oversized diagnostic omitted]"
			continue
		}
		line = logBearer.ReplaceAllString(line, "[redacted]")
		line = logURI.ReplaceAllString(line, "[redacted]")
		line = logUUID.ReplaceAllString(line, "[redacted]")
		lines[i] = logOpaque.ReplaceAllString(line, "[redacted]")
	}
	return strings.Join(lines, "\n")
}

type redactedLogWriter struct {
	next io.Writer
	state logRedactionState
}

func (w *redactedLogWriter) Write(p []byte) (int, error) {
	_, err := io.WriteString(w.next, w.state.redact(string(p)))
	if err != nil {
		return 0, err
	}
	// io.Writer reports consumption of the input, not the redacted size.
	return len(p), nil
}

var platformLogRedaction logRedactionState

func redactPlatformMessage(message string) string {
	return platformLogRedaction.redact(message)
}
