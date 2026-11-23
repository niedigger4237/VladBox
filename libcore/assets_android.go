//go:build android

package libcore

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"golang.org/x/mobile/asset"
)

func extractAssets() {
	useOfficialAssets := intfNB4A.UseOfficialAssets()

	for _, name := range []string{geoipDat, geositeDat, yacdDstFolder} {
		if err := extractAssetName(name, useOfficialAssets); err != nil {
			log.Printf("Extract %s failed: %v", name, err)
		}
	}
	if err := extractRuleSets(); err != nil {
		log.Println("Extract sing-box SRS Rule Sets failed:", err)
	}
}

// extractRuleSets installs the official SRS files packaged by buildScript/lib/assets.sh.
// The manifest version is written only after every file has been copied successfully.
func extractRuleSets() error {
	const assetDir = "sing-box/rule-set/"
	manifest, err := asset.Open(assetDir + "manifest.txt")
	if err != nil {
		return fmt.Errorf("open Rule Set manifest: %w", err)
	}
	manifestBytes, readErr := io.ReadAll(manifest)
	closeErr := manifest.Close()
	if readErr != nil {
		return fmt.Errorf("read Rule Set manifest: %w", readErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close Rule Set manifest: %w", closeErr)
	}
	names := strings.Fields(string(manifestBytes))
	if len(names) == 0 {
		return fmt.Errorf("Rule Set manifest is empty")
	}

	versionAsset, err := asset.Open(assetDir + "version.txt")
	if err != nil {
		return fmt.Errorf("open Rule Set version: %w", err)
	}
	versionBytes, readErr := io.ReadAll(versionAsset)
	closeErr = versionAsset.Close()
	if readErr != nil {
		return fmt.Errorf("read Rule Set version: %w", readErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close Rule Set version: %w", closeErr)
	}
	version := strings.TrimSpace(string(versionBytes))
	if version == "" {
		return fmt.Errorf("Rule Set version is empty")
	}

	checksumsAsset, err := asset.Open(assetDir + "checksums.txt")
	if err != nil {
		return fmt.Errorf("open Rule Set checksums: %w", err)
	}
	checksumsBytes, readErr := io.ReadAll(checksumsAsset)
	closeErr = checksumsAsset.Close()
	if readErr != nil {
		return fmt.Errorf("read Rule Set checksums: %w", readErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close Rule Set checksums: %w", closeErr)
	}
	checksums := make(map[string]string, len(names))
	for _, line := range strings.Split(string(checksumsBytes), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		checksums[fields[1]] = fields[0]
	}
	if len(checksums) != len(names) {
		return fmt.Errorf("Rule Set checksum count mismatch: manifest=%d checksums=%d", len(names), len(checksums))
	}

	versionPath := filepath.Join(externalAssetsPath, "rule-set.version.txt")
	if current, err := os.ReadFile(versionPath); err == nil && strings.TrimSpace(string(current)) == version {
		complete := true
		for _, name := range names {
			if filepath.Base(name) != name || !strings.HasSuffix(name, ".srs") {
				return fmt.Errorf("invalid Rule Set filename in manifest: %q", name)
			}
			info, statErr := os.Stat(filepath.Join(externalAssetsPath, name))
			if statErr != nil || !info.Mode().IsRegular() || info.Size() == 0 {
				complete = false
				break
			}
		}
		if complete {
			return nil
		}
	}

	if err := os.MkdirAll(externalAssetsPath, 0o755); err != nil {
		return fmt.Errorf("create external assets directory: %w", err)
	}
	for _, name := range names {
		if filepath.Base(name) != name || !strings.HasSuffix(name, ".srs") {
			return fmt.Errorf("invalid Rule Set filename in manifest: %q", name)
		}
		expectedChecksum, ok := checksums[name]
		if !ok || len(expectedChecksum) != 64 {
			return fmt.Errorf("missing or invalid checksum for %s", name)
		}
		source, err := asset.Open(assetDir + name)
		if err != nil {
			return fmt.Errorf("open bundled %s: %w", name, err)
		}
		tmpPath := filepath.Join(externalAssetsPath, name+".tmp")
		targetPath := filepath.Join(externalAssetsPath, name)
		out, err := os.Create(tmpPath)
		if err != nil {
			source.Close()
			return fmt.Errorf("create %s: %w", tmpPath, err)
		}
		hasher := sha256.New()
		_, copyErr := io.Copy(io.MultiWriter(out, hasher), source)
		sourceCloseErr := source.Close()
		outCloseErr := out.Close()
		if copyErr != nil || sourceCloseErr != nil || outCloseErr != nil {
			os.Remove(tmpPath)
			if copyErr != nil {
				return fmt.Errorf("copy %s: %w", name, copyErr)
			}
			if sourceCloseErr != nil {
				return fmt.Errorf("close %s: %w", name, sourceCloseErr)
			}
			return fmt.Errorf("close output %s: %w", name, outCloseErr)
		}
		if info, err := os.Stat(tmpPath); err != nil || info.Size() == 0 {
			os.Remove(tmpPath)
			return fmt.Errorf("bundled Rule Set %s is empty", name)
		}
		if actualChecksum := hex.EncodeToString(hasher.Sum(nil)); actualChecksum != expectedChecksum {
			os.Remove(tmpPath)
			return fmt.Errorf("checksum mismatch for %s", name)
		}
		if err := os.Rename(tmpPath, targetPath); err != nil {
			os.Remove(tmpPath)
			return fmt.Errorf("install %s: %w", name, err)
		}
	}
	versionTmp := versionPath + ".tmp"
	if err := os.WriteFile(versionTmp, []byte(version), 0o644); err != nil {
		return fmt.Errorf("write Rule Set version: %w", err)
	}
	if err := os.Rename(versionTmp, versionPath); err != nil {
		os.Remove(versionTmp)
		return fmt.Errorf("install Rule Set version: %w", err)
	}
	return nil
}

// extractAssetName restores an APK-bundled asset. Geo databases are stored in
// externalAssetsPath so they can be replaced by the user; Yacd stays internal.
func extractAssetName(name string, useOfficialAssets bool) error {
	replaceable := true
	var version, apkPrefix string
	switch name {
	case geoipDat:
		version, apkPrefix = geoipVersion, apkAssetPrefixSingBox
	case geositeDat:
		version, apkPrefix = geositeVersion, apkAssetPrefixSingBox
	case yacdDstFolder:
		version, replaceable = yacdVersion, false
	default:
		return fmt.Errorf("unknown bundled asset %q", name)
	}

	dir := externalAssetsPath
	if !replaceable {
		dir = internalAssetsPath
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("create asset directory: %w", err)
	}
	dstName := filepath.Join(dir, name)
	versionPath := filepath.Join(dir, version)

	av, err := asset.Open(apkPrefix + version)
	if err != nil {
		return fmt.Errorf("open bundled version %s: %w", version, err)
	}
	versionBytes, readErr := io.ReadAll(av)
	closeErr := av.Close()
	if readErr != nil {
		return fmt.Errorf("read bundled version %s: %w", version, readErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close bundled version %s: %w", version, closeErr)
	}
	assetVersion := string(versionBytes)

	localVersion := ""
	doExtract := false
	info, statErr := os.Stat(dstName)
	if statErr != nil || !info.Mode().IsRegular() || info.Size() == 0 {
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		b, err := os.ReadFile(versionPath)
		if err != nil {
			doExtract = true
		} else {
			localVersion = string(b)
			if localVersion != "Custom" {
				av, avErr := strconv.ParseUint(assetVersion, 10, 64)
				if avErr != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, lvErr := strconv.ParseUint(localVersion, 10, 64)
					doExtract = lvErr != nil || av > lv
				}
			}
		}
	}
	if !doExtract {
		return nil
	}

	if name == yacdDstFolder {
		f, err := asset.Open("yacd.zip")
		if err != nil {
			return fmt.Errorf("open bundled yacd.zip: %w", err)
		}
		zipPath := dstName + ".zip"
		if err = extractAsset(f, zipPath); err != nil {
			return fmt.Errorf("copy bundled yacd.zip: %w", err)
		}
		if err = Unzip(zipPath, internalAssetsPath); err != nil {
			os.Remove(zipPath)
			return fmt.Errorf("extract yacd.zip: %w", err)
		}
		os.Remove(zipPath)
		matches, err := filepath.Glob(filepath.Join(internalAssetsPath, "Yacd-*"))
		if err != nil {
			return fmt.Errorf("glob Yacd: %w", err)
		}
		if len(matches) != 1 {
			return fmt.Errorf("glob Yacd found %d results, expected 1", len(matches))
		}
		if err = os.RemoveAll(dstName); err != nil {
			return fmt.Errorf("remove old Yacd: %w", err)
		}
		if err = os.Rename(matches[0], dstName); err != nil {
			return fmt.Errorf("install Yacd: %w", err)
		}
	} else {
		f, err := asset.Open(apkPrefix + name + ".xz")
		if err != nil {
			return fmt.Errorf("open bundled %s.xz: %w", name, err)
		}
		xzPath := dstName + ".xz"
		if err = extractAsset(f, xzPath); err != nil {
			os.Remove(xzPath)
			return fmt.Errorf("copy bundled %s.xz: %w", name, err)
		}
		tmpPath := dstName + ".install"
		_ = os.Remove(tmpPath)
		if err = Unxz(xzPath, tmpPath); err != nil {
			os.Remove(xzPath)
			os.Remove(tmpPath)
			return fmt.Errorf("decompress bundled %s.xz: %w", name, err)
		}
		os.Remove(xzPath)
		st, err := os.Stat(tmpPath)
		if err != nil || st.Size() == 0 {
			os.Remove(tmpPath)
			return fmt.Errorf("decompressed %s is missing or empty", name)
		}
		if err = os.Rename(tmpPath, dstName); err != nil {
			os.Remove(tmpPath)
			return fmt.Errorf("install %s: %w", name, err)
		}
	}

	// Only mark the asset as installed after extraction and final-file validation.
	st, err := os.Stat(dstName)
	if err != nil || (name != yacdDstFolder && (!st.Mode().IsRegular() || st.Size() == 0)) {
		return fmt.Errorf("installed asset %s is missing or empty", name)
	}
	versionTmp := versionPath + ".tmp"
	if err = os.WriteFile(versionTmp, versionBytes, 0644); err != nil {
		return fmt.Errorf("write asset version: %w", err)
	}
	if err = os.Rename(versionTmp, versionPath); err != nil {
		os.Remove(versionTmp)
		return fmt.Errorf("commit asset version: %w", err)
	}
	return nil
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
