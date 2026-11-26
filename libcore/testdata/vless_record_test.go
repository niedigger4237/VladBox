package encryption

import "testing"

func TestNieFiggerVlessRecordBounds(t *testing.T) {
	for _, n := range []int{17, 8192, 16640} {
		var header [5]byte
		EncodeHeader(header[:], n)
		if size, err := DecodeHeader(header[:]); err != nil || size != n {
			t.Fatalf("valid record %d rejected: %v", n, err)
		}
	}
	for _, n := range []int{0, 16, 16641, 17000} {
		var header [5]byte
		EncodeHeader(header[:], n)
		if _, err := DecodeHeader(header[:]); err == nil { t.Fatalf("invalid record %d accepted", n) }
	}
}
