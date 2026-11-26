package xhttp

import "testing"

func TestNieFiggerXHTTPAutoMode(t *testing.T) {
	for _, tc := range []struct {
		mode string
		reality, download bool
		want string
	}{
		{"auto", false, false, "packet-up"},
		{"auto", false, true, "packet-up"},
		{"auto", true, false, "stream-one"},
		{"auto", true, true, "stream-up"},
		{"packet-up", true, false, "packet-up"},
		{"stream-up", false, false, "stream-up"},
	} {
		if got := resolveClientMode(tc.mode, tc.reality, tc.download); got != tc.want {
			t.Fatalf("mode %s reality %v download %v: got %s want %s", tc.mode, tc.reality, tc.download, got, tc.want)
		}
	}
}
