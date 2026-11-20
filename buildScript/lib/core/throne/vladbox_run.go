// Standalone runner for VladBox root modes: runs/checks a sing-box config with Throne's core
// (throneproj sing-box fork + wireguard-go), without Throne's GUI IPC. Copied into
// core/cmd/vladbox-run of the Throne checkout at build time (internal packages are only
// importable from inside that module).
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"syscall"

	"ThroneCore/internal/boxmain"

	C "github.com/sagernet/sing-box/constant"
)

// geoip:/geosite: local rule-sets are VladBox's own database format (not .srs); this core cannot
// read them, so they are removed together with the rules that only consisted of them.
func dropLegacyRuleSets(raw []byte) []byte {
	var cfg map[string]any
	if json.Unmarshal(raw, &cfg) != nil {
		return raw
	}
	route, _ := cfg["route"].(map[string]any)
	if route == nil {
		return raw
	}
	dead := map[string]bool{}
	if sets, ok := route["rule_set"].([]any); ok {
		kept := []any{}
		for _, s := range sets {
			m, _ := s.(map[string]any)
			p, _ := m["path"].(string)
			if m != nil && m["type"] == "local" && (strings.HasPrefix(p, "geosite:") || strings.HasPrefix(p, "geoip:")) {
				dead[m["tag"].(string)] = true
				continue
			}
			kept = append(kept, s)
		}
		if len(dead) == 0 {
			return raw
		}
		route["rule_set"] = kept
	}
	var fix func(rules []any) []any
	fix = func(rules []any) []any {
		out := []any{}
		for _, r := range rules {
			m, _ := r.(map[string]any)
			if m == nil {
				out = append(out, r)
				continue
			}
			if rs, ok := m["rule_set"]; ok {
				var tags []any
				switch v := rs.(type) {
				case string:
					tags = []any{v}
				case []any:
					tags = v
				}
				left := []any{}
				for _, t := range tags {
					if s, _ := t.(string); !dead[s] {
						left = append(left, t)
					}
				}
				if len(left) == 0 {
					delete(m, "rule_set")
					// nothing but the action is left: the rule matched only the removed sets
					only := true
					for k := range m {
						if k != "action" && k != "outbound" && k != "method" && k != "server" && k != "strategy" {
							only = false
						}
					}
					if only {
						fmt.Fprintln(os.Stderr, "vladbox-run: dropped a rule that only used geoip/geosite sets")
						continue
					}
				} else {
					m["rule_set"] = left
				}
			}
			out = append(out, r)
		}
		return out
	}
	if rules, ok := route["rules"].([]any); ok {
		route["rules"] = fix(rules)
	}
	if dns, ok := cfg["dns"].(map[string]any); ok {
		if rules, ok := dns["rules"].([]any); ok {
			dns["rules"] = fix(rules)
		}
	}
	out, err := json.Marshal(cfg)
	if err != nil {
		return raw
	}
	return out
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: vladbox-run run|check -c config [-D dir] | version")
		os.Exit(2)
	}
	cmd := os.Args[1]
	if cmd == "version" || cmd == "--version" {
		fmt.Println("sing-box (throne) " + C.Version + " " + runtime.Version())
		return
	}
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	cfgPath := fs.String("c", "", "config")
	dir := fs.String("D", "", "working dir")
	fs.Parse(os.Args[2:])
	if *dir != "" {
		if err := os.Chdir(*dir); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	raw, err := os.ReadFile(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	raw = dropLegacyRuleSets(raw)
	boxmain.DisableColor()
	switch cmd {
	case "check":
		if err := boxmain.Check(raw); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "run":
		_, cancel, err := boxmain.Create(raw, nil)
		if err != nil {
			fmt.Fprintln(os.Stderr, "FATAL", err)
			os.Exit(1)
		}
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, os.Interrupt, syscall.SIGTERM, syscall.SIGHUP)
		<-sig
		cancel()
	default:
		fmt.Fprintln(os.Stderr, "unknown command", cmd)
		os.Exit(2)
	}
}
