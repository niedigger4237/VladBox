package firefox_test

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	utls "github.com/metacubex/utls"
)

// Read actual TLS records over an in-memory connection.
func captureHello(t *testing.T, config *utls.Config, id utls.ClientHelloID) []byte {
	t.Helper()
	client, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	deadline := time.Now().Add(5 * time.Second)
	if err := client.SetDeadline(deadline); err != nil {
		t.Fatal(err)
	}
	if err := peer.SetDeadline(deadline); err != nil {
		t.Fatal(err)
	}
	u := utls.UClient(client, config, id)
	done := make(chan error, 1)
	go func() { done <- u.Handshake() }()
	defer func() {
		peer.Close()
		client.Close()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("ClientHello handshake did not terminate")
		}
	}()
	var hello []byte
	for len(hello) < 4 || len(hello) < 4+(int(hello[1])<<16|int(hello[2])<<8|int(hello[3])) {
		var header [5]byte
		if _, err := io.ReadFull(peer, header[:]); err != nil {
			t.Fatalf("read TLS record: %v", err)
		}
		n := int(binary.BigEndian.Uint16(header[3:]))
		if header[0] != 22 || n == 0 || n > 16384 || len(hello)+n > 65536 {
			t.Fatal("unexpected TLS handshake record")
		}
		record := make([]byte, n)
		if _, err := io.ReadFull(peer, record); err != nil {
			t.Fatal(err)
		}
		hello = append(hello, record...)
	}
	if hello[0] != 1 || len(hello) != 4+(int(hello[1])<<16|int(hello[2])<<8|int(hello[3])) {
		t.Fatal("invalid ClientHello framing")
	}
	return hello[4:]
}

type cursor struct {
	t *testing.T
	b []byte
}

func (c *cursor) take(n int) []byte {
	c.t.Helper()
	if n < 0 || n > len(c.b) {
		c.t.Fatal("truncated ClientHello field")
	}
	p := c.b[:n]
	c.b = c.b[n:]
	return p
}
func (c *cursor) u8() int  { return int(c.take(1)[0]) }
func (c *cursor) u16() int { return int(binary.BigEndian.Uint16(c.take(2))) }

func TestFirefox148Wire(t *testing.T) {
	if utls.HelloFirefox_Auto.Version != "148" {
		t.Fatalf("unexpected Firefox Auto version: %s", utls.HelloFirefox_Auto.Version)
	}
	const hybrid = 0x11ec // X25519MLKEM768
	const x25519 = 29
	const p256 = 23
	for i := 0; i < 3; i++ {
		config := &utls.Config{ServerName: "example.test"}
		if i == 1 {
			config.NextProtos = []string{"h2", "http/1.1"}
		}
		c := cursor{t, captureHello(t, config, utls.HelloFirefox_Auto)}
		c.take(34)
		c.take(c.u8())
		c.take(c.u16())
		c.take(c.u8())
		ext := cursor{t, c.take(c.u16())}
		if len(c.b) != 0 {
			t.Fatal("trailing ClientHello bytes")
		}
		var groups []int
		var order []int
		shares := map[int][]byte{}
		for len(ext.b) > 0 {
			kind := ext.u16()
			field := cursor{t, ext.take(ext.u16())}
			switch kind {
			case 10:
				list := cursor{t, field.take(field.u16())}
				for len(list.b) > 0 {
					groups = append(groups, list.u16())
				}
				if len(field.b) != 0 {
					t.Fatal("trailing supported_groups")
				}
			case 51:
				list := cursor{t, field.take(field.u16())}
				for len(list.b) > 0 {
					group := list.u16()
					if _, exists := shares[group]; exists {
						t.Fatal("duplicate key share")
					}
					order = append(order, group)
					shares[group] = list.take(list.u16())
				}
				if len(field.b) != 0 {
					t.Fatal("trailing key_share")
				}
			}
		}
		if len(groups) < 3 || groups[0] != hybrid || groups[1] != x25519 || groups[2] != p256 {
			t.Fatalf("unexpected Firefox supported group order: %v", groups)
		}
		if len(order) != 3 || order[0] != hybrid || order[1] != x25519 || order[2] != p256 {
			t.Fatalf("unexpected Firefox key share order: %v", order)
		}
		h, x, p := shares[hybrid], shares[x25519], shares[p256]
		if len(h) != 1216 || len(x) != 32 || len(p) != 65 {
			t.Fatalf("unexpected key share lengths: %d, %d, %d", len(h), len(x), len(p))
		}
		if !bytes.Equal(h[len(h)-32:], x) {
			t.Fatal("Firefox hybrid and classical X25519 key shares do not reuse the same public key")
		}
	}
}

func TestModernBrowserHybridWire(t *testing.T) {
	for _, tc := range []struct {
		name string
		id utls.ClientHelloID
		version string
	}{
		{"chrome", utls.HelloChrome_Auto, "133"},
		{"safari", utls.HelloSafari_Auto, "26.3"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if tc.id.Version != tc.version {
				t.Fatalf("unexpected version %s, expected %s", tc.id.Version, tc.version)
			}
			c := cursor{t, captureHello(t, &utls.Config{ServerName:"example.test"}, tc.id)}
			c.take(34)
			c.take(c.u8())
			c.take(c.u16())
			c.take(c.u8())
			ext := cursor{t, c.take(c.u16())}
			groups := map[int]bool{}
			shares := map[int]int{}
			for len(ext.b) > 0 {
				kind := ext.u16()
				field := cursor{t, ext.take(ext.u16())}
				switch kind {
				case 10:
					list := cursor{t, field.take(field.u16())}
					for len(list.b) > 0 { groups[list.u16()] = true }
				case 51:
					list := cursor{t, field.take(field.u16())}
					for len(list.b) > 0 {
						group := list.u16()
						shares[group] = len(list.take(list.u16()))
					}
				}
			}
			if !groups[0x11ec] || !groups[29] || shares[0x11ec] != 1216 || shares[29] != 32 {
				t.Fatalf("missing or malformed hybrid/classical key shares: %v %v", groups, shares)
			}
		})
	}
}
