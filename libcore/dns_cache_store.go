package libcore

import (
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

const managedDNSCacheLimit int64 = 32 * 1024 * 1024

// The app-private parent is supplied by the Android application. A separate
// lease serializes managed instances/clear requests across processes; the
// database's own flock must also be available before any unlink.
func managedCacheOpen(path string, flags int) (*os.File, error) {
	fd, err := syscall.Open(path, flags|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0600)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), path)
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() || stat.Uid != uint32(os.Getuid()) ||
		stat.Nlink != 1 || info.Mode().Perm()&0077 != 0 {
		file.Close()
		return nil, errors.New("unsafe managed DNS cache file")
	}
	return file, nil
}

func lockManagedCache(file *os.File) error {
	return syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
}

func readManagedScope(path string) (string, error) {
	file, err := managedCacheOpen(path, syscall.O_RDONLY)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	defer file.Close()
	content, err := io.ReadAll(io.LimitReader(file, 65))
	return string(content), err
}

// Returns a lease held until core close, or nil for memory/clear operations.
// Empty scope clears the reserved database. Never removes a locked DB.
func prepareManagedDNSCache(dir, scope string) (lease *os.File, err error) {
	if !filepath.IsAbs(dir) || filepath.Base(dir) != "nf-dns-cache-v1" {
		return nil, errors.New("invalid managed DNS cache directory")
	}
	if scope != "" {
		if decoded, e := hex.DecodeString(scope); e != nil || len(decoded) != 32 {
			return nil, errors.New("invalid managed DNS cache scope")
		}
		if err = os.Mkdir(dir, 0700); err != nil && !errors.Is(err, os.ErrExist) {
			return nil, err
		}
	}
	info, err := os.Lstat(dir)
	if scope == "" && errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.IsDir() || stat.Uid != uint32(os.Getuid()) || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("unsafe managed DNS cache directory")
	}
	lock, err := managedCacheOpen(filepath.Join(dir, "lease"), syscall.O_RDWR|syscall.O_CREAT)
	if err != nil {
		return nil, err
	}
	defer func() {
		if lease == nil {
			lock.Close()
		}
	}()
	if err = lockManagedCache(lock); err != nil {
		return nil, fmt.Errorf("managed DNS cache is busy: %w", err)
	}
	dbPath, scopePath := filepath.Join(dir, "cache.db"), filepath.Join(dir, "scope")
	previous, err := readManagedScope(scopePath)
	if err != nil {
		return nil, err
	}
	db, err := managedCacheOpen(dbPath, syscall.O_RDWR)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if db != nil {
		defer db.Close()
		if err = lockManagedCache(db); err != nil {
			return nil, fmt.Errorf("managed DNS database is busy: %w", err)
		}
		dbInfo, err := db.Stat()
		if err != nil {
			return nil, err
		}
		if scope == "" || previous != scope || dbInfo.Size() > managedDNSCacheLimit {
			if err = os.Remove(dbPath); err != nil {
				return nil, err
			}
		}
	}
	if scope == "" {
		if err = os.Remove(scopePath); err != nil && !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
		return nil, nil
	}
	// Precreate with 0600, even though sing-box defaults to 0666 before umask.
	if _, err = os.Lstat(dbPath); errors.Is(err, os.ErrNotExist) {
		f, e := managedCacheOpen(dbPath, syscall.O_RDWR|syscall.O_CREAT|syscall.O_EXCL)
		if e != nil {
			return nil, e
		}
		f.Close()
	} else if err != nil {
		return nil, err
	}
	if previous != scope {
		tmp := scopePath + ".new"
		if err = os.Remove(tmp); err != nil && !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
		f, e := managedCacheOpen(tmp, syscall.O_WRONLY|syscall.O_CREAT|syscall.O_EXCL)
		if e != nil {
			return nil, e
		}
		_, e = f.WriteString(scope)
		if e == nil {
			e = f.Sync()
		}
		closeErr := f.Close()
		if e != nil {
			return nil, e
		}
		if closeErr != nil {
			return nil, closeErr
		}
		if err = os.Rename(tmp, scopePath); err != nil {
			return nil, err
		}
	}
	return lock, nil
}
