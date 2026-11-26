package libcore

import (
	"encoding/json"
	"errors"
	"log"
	"path/filepath"
)

// ClearManagedDnsCache only touches the application's reserved directory.
// A live core's lease or database lock prevents deletion.
func ClearManagedDnsCache(directory string) error {
	_, err := prepareManagedDNSCache(directory, "")
	return err
}

// NewManagedDnsCacheInstance is called only for real managed embedded VPN
// sessions. Raw/custom cache_file options and all other constructors retain
// their original behavior. Unavailable persistence falls back to memory.
func NewManagedDnsCacheInstance(config string, localTransport LocalDNSTransport, directory, scope string) (*BoxInstance, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		return NewSingBoxInstance(config, localTransport)
	}
	experimental := map[string]json.RawMessage{}
	if raw, ok := root["experimental"]; ok {
		if err := json.Unmarshal(raw, &experimental); err != nil {
			return NewSingBoxInstance(config, localTransport)
		}
	}
	if experimental == nil {
		experimental = map[string]json.RawMessage{}
	}
	if _, exists := experimental["cache_file"]; exists {
		return NewSingBoxInstance(config, localTransport)
	}
	lease, err := prepareManagedDNSCache(directory, scope)
	if err != nil {
		log.Println("Managed DNS cache unavailable; using memory cache")
		return NewSingBoxInstance(config, localTransport)
	}
	if lease == nil {
		return NewSingBoxInstance(config, localTransport)
	}
	attached := false
	defer func() {
		if !attached {
			lease.Close()
		}
	}()
	experimental["cache_file"], err = json.Marshal(map[string]any{
		"enabled": true, "path": filepath.Join(directory, "cache.db"),
		"store_dns": true, "store_fakeip": false,
	})
	if err != nil {
		return nil, err
	}
	root["experimental"], err = json.Marshal(experimental)
	if err != nil {
		return nil, err
	}
	effective, err := json.Marshal(root)
	if err != nil {
		return nil, err
	}
	instance, err := NewSingBoxInstance(string(effective), localTransport)
	if err != nil {
		return nil, err
	}
	if instance == nil {
		return nil, errors.New("managed DNS core was not created")
	}
	instance.managedDNSCacheLease = lease
	attached = true
	return instance, nil
}
