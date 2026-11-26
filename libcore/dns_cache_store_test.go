package libcore

import (
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestManagedCacheLifecycle(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "nf-dns-cache-v1")
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	lease, err := prepareManagedDNSCache(dir, a)
	if err != nil || lease == nil {
		t.Fatalf("prepare: %v", err)
	}
	db := filepath.Join(dir, "cache.db")
	if err := os.WriteFile(db, []byte("saved"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := prepareManagedDNSCache(dir, ""); err == nil {
		t.Fatal("live lease allowed clear")
	}
	lease.Close()
	lease, err = prepareManagedDNSCache(dir, a)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(db)
	if string(got) != "saved" {
		t.Fatal("same scope was not preserved")
	}
	lease.Close()
	lease, err = prepareManagedDNSCache(dir, b)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(db)
	if err != nil || info.Size() != 0 || info.Mode().Perm() != 0600 {
		t.Fatal("changed scope did not reset privately")
	}
	lease.Close()
	if err := os.Truncate(db, managedDNSCacheLimit+1); err != nil {
		t.Fatal(err)
	}
	lease, err = prepareManagedDNSCache(dir, b)
	if err != nil {
		t.Fatal(err)
	}
	lease.Close()
	info, err = os.Stat(db)
	if err != nil || info.Size() != 0 {
		t.Fatal("oversized DB not reset at startup")
	}
	if _, err := prepareManagedDNSCache(dir, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(db); !os.IsNotExist(err) {
		t.Fatal("database not removed")
	}
}

func TestManagedCacheRefusesLockedDatabase(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "nf-dns-cache-v1")
	lease, err := prepareManagedDNSCache(dir, strings.Repeat("a", 64))
	if err != nil {
		t.Fatal(err)
	}
	lease.Close()
	file, err := managedCacheOpen(filepath.Join(dir, "cache.db"), syscall.O_RDWR)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if err := lockManagedCache(file); err != nil {
		t.Fatal(err)
	}
	if _, err := prepareManagedDNSCache(dir, ""); err == nil {
		t.Fatal("DB lock ignored")
	}
}

func TestManagedCacheRefusesSymlink(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "nf-dns-cache-v1")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "other.db")
	if err := os.WriteFile(target, []byte("untouched"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(dir, "cache.db")); err != nil {
		t.Fatal(err)
	}
	if _, err := prepareManagedDNSCache(dir, ""); err == nil {
		t.Fatal("symlink accepted")
	}
	got, err := os.ReadFile(target)
	if err != nil || string(got) != "untouched" {
		t.Fatal("target modified")
	}
}
