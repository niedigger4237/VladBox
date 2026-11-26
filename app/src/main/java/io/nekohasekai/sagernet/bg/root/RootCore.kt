package io.nekohasekai.sagernet.bg.root

import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Manages the external sing-box binary that the root modes run.
 *
 * Layout (root owned):
 *   /data/adb/nekobox/cores/<label>/sing-box   installed versions, side by side
 *   /data/adb/nekobox/current                  text file with the active label
 *   /data/adb/nekobox/previous                 label to roll back to
 *
 * Updating never touches a running core: a new version is staged, verified
 * (`sing-box version`), then activated by atomically replacing `current`. The old
 * binary stays on disk, so a running instance keeps its inode and the next start
 * picks the new one. If the active core is broken the bundled one is used.
 */
object RootCore {

    const val BASE = "/data/adb/nekobox"
    private const val CORES = "$BASE/cores"
    private const val CURRENT = "$BASE/current"
    private const val PREVIOUS = "$BASE/previous"
    private const val LEGACY_EXTERNAL_OPT_IN = "$BASE/external-opt-in-v1"
    private const val KEEP = 3
    private val labelPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private fun validLabel(label: String) = labelPattern.matches(label)

    // This file belongs to the current installation, is shared by app
    // processes and is excluded from Android Auto Backup. Never migrate
    // consent from /data/adb: that directory survives uninstall/clear data.
    private val externalSelection: File
        get() = File(SagerNet.application.noBackupFilesDir, "root-external-selection-v2")

    private fun selectedExternalLabel(): String? = runCatching {
        val file = externalSelection
        if (!file.isFile || file.length() !in 1L..128L) return null
        file.readText(Charsets.UTF_8).takeIf(::validLabel)
    }.getOrNull()

    private fun authorizeExternal(label: String): Boolean = runCatching {
        require(validLabel(label))
        val target = externalSelection
        val temp = File.createTempFile("root-selection-", ".tmp", target.parentFile)
        try {
            temp.writeText(label, Charsets.UTF_8)
            check(temp.renameTo(target))
        } finally {
            temp.delete()
        }
        true
    }.getOrDefault(false)

    private const val REPO = "shtorm-7/sing-box-extended"

    /** sing-box shipped inside the apk (jniLibs/<abi>/libsingbox.so), may be missing */
    val bundled: File
        get() = File(SagerNet.application.applicationInfo.nativeLibraryDir, "libsingbox.so")

    data class Active(val path: String, val label: String, val bundled: Boolean)

    private fun abiToken(): List<String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> listOf("arm64", "aarch64")
            abi.startsWith("armeabi") -> listOf("armv7", "armv6", "arm")
            abi == "x86_64" -> listOf("amd64", "x86_64")
            else -> listOf("386", "x86")
        }
    }

    fun activeLabel(): String? {
        val r = RootShell.run("cat $CURRENT 2>/dev/null")
        return r.out.trim().takeIf { r.ok && validLabel(it) }
    }

    fun selectedLabel(): String = resolve()?.let { if (it.bundled) "Bundled (patched)" else "External: ${it.label}" } ?: "Unavailable"

    private data class BundledIdentity(
        val path: String,
        val size: Long,
        val modified: Long,
        val device: Long,
        val inode: Long,
        val changed: Long,
        val apkUpdated: Long
    ) {
        fun toJson() = JSONObject().apply {
            put("path", path)
            put("size", size)
            put("modified", modified)
            put("device", device)
            put("inode", inode)
            put("changed", changed)
            put("apkUpdated", apkUpdated)
        }

        fun matches(json: JSONObject): Boolean =
            json.optString("path") == path &&
                json.optLong("size", -1) == size &&
                json.optLong("modified", -1) == modified &&
                json.optLong("device", -1) == device &&
                json.optLong("inode", -1) == inode &&
                json.optLong("changed", -1) == changed &&
                json.optLong("apkUpdated", -1) == apkUpdated
    }

    private data class BundledFingerprint(val identity: BundledIdentity, val hash: String)
    private var bundledFingerprint: BundledFingerprint? = null
    private val validFingerprint = Regex("[0-9a-fA-F]{64}")
    private val fingerprintCacheFile: File
        get() = File(SagerNet.application.noBackupFilesDir, "root-core-fingerprint-v1.json")

    private fun bundledIdentity(file: File): BundledIdentity? {
        if (file.absolutePath != bundled.absolutePath) return null
        return runCatching {
            val stat = android.system.Os.stat(file.absolutePath)
            if (!android.system.OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0) return null
            val app = SagerNet.application
            val updated = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            BundledIdentity(file.absolutePath, stat.st_size, file.lastModified(),
                stat.st_dev, stat.st_ino, stat.st_ctime, updated)
        }.getOrNull()
    }

    private fun readFingerprint(identity: BundledIdentity): String? = runCatching {
        val cache = fingerprintCacheFile
        if (!cache.isFile || cache.length() !in 1L..4096L) return null
        val json = JSONObject(cache.readText(Charsets.UTF_8))
        if (json.optInt("version") != 1 || !identity.matches(json.getJSONObject("identity"))) return null
        json.optString("sha256").takeIf { validFingerprint.matches(it) }
    }.getOrNull()

    private fun saveFingerprint(value: BundledFingerprint) {
        // Unique temporary file + atomic rename: readers never see partial JSON,
        // even if another app process starts at the same time.
        runCatching {
            val target = fingerprintCacheFile
            val temp = File.createTempFile("root-fingerprint-", ".tmp", target.parentFile)
            try {
                temp.writeText(JSONObject().apply {
                    put("version", 1)
                    put("identity", value.identity.toJson())
                    put("sha256", value.hash)
                }.toString(), Charsets.UTF_8)
                check(temp.renameTo(target))
            } finally {
                temp.delete()
            }
        }.onFailure { Logs.w("ROOT fingerprint cache write failed: ${it.javaClass.simpleName}") }
    }

    // Diagnostic cache only. External cores are always hashed; the cache does
    // not replace signature verification or the normal sing-box config check.
    @Synchronized
    fun fingerprint(binary: String): String {
        val file = File(binary)
        val identity = bundledIdentity(file)
        if (identity != null) {
            bundledFingerprint?.takeIf { it.identity == identity }?.let {
                Logs.i("ROOT fingerprint: memory cache hit")
                return it.hash
            }
            readFingerprint(identity)?.let { hash ->
                bundledFingerprint = BundledFingerprint(identity, hash)
                Logs.i("ROOT fingerprint: disk cache hit")
                return hash
            }
        }
        Logs.i("ROOT fingerprint: calculating SHA256")
        val result = RootShell.run("sha256sum ${RootShell.q(binary)}")
        val hash = result.out.trim().substringBefore(" ")
            .takeIf { result.ok && validFingerprint.matches(it) } ?: "unavailable"
        // Never cache a failed calculation or a file that changed while hashing.
        if (identity != null && hash != "unavailable" && bundledIdentity(file) == identity) {
            val value = BundledFingerprint(identity, hash)
            bundledFingerprint = value
            saveFingerprint(value)
        }
        return hash
    }

    fun installedLabels(): List<String> =
        RootShell.run("ls -1 $CORES 2>/dev/null").out.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun versionOf(binary: String): String? {
        val r = RootShell.run("${RootShell.q(binary)} version")
        return if (r.ok) r.out.lines().firstOrNull()?.trim() else null
    }

    /** Resolves the binary to run, falling back to the bundled one when the active core is unusable. */
    fun resolve(): Active? {
        // No consent in this installation => bundled, even if a legacy
        // root marker and downloaded core still exist after reinstall.
        selectedExternalLabel()?.let { label ->
            if (activeLabel() == label) {
                val path = "$CORES/$label/sing-box"
                if (versionOf(path) != null) return Active(path, label, false)
                Logs.w("active core $label is broken, falling back to the bundled core")
            }
        }
        val b = bundled
        if (b.exists()) return Active(b.absolutePath, "bundled", true)
        return null
    }

    /** `sing-box check` for the generated config, as root. Returns an error text or null. */
    fun check(binary: String, config: File): String? {
        val r = RootShell.run("${RootShell.q(binary)} check -c ${RootShell.q(config)}")
        return if (r.ok) null else (r.err.ifBlank { r.out }).ifBlank { "check failed" }
    }

    /**
     * Installs a binary as a new version and makes it active.
     * @param binary local file (any readable path), already a plain executable
     * @return the installed version string
     */
    @Synchronized
    fun install(binary: File, label: String): Result<String> {
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!validLabel(safe)) return Result.failure(IllegalArgumentException("Invalid core label"))
        val stage = "$CORES/.$safe.tmp"
        val final = "$CORES/$safe"
        val prev = activeLabel()
        val r = RootShell.run(
            "set -e",
            "mkdir -p $CORES",
            "rm -rf $stage && mkdir -p $stage",
            "cp ${RootShell.q(binary)} $stage/sing-box && chmod 755 $stage/sing-box",
            // verify before it becomes visible
            "$stage/sing-box version >/dev/null || { rm -rf $stage; exit 9; }",
            "rm -rf $final && mv $stage $final",
            // atomic switch: rename(2) over a regular file
            "echo ${RootShell.q(safe)} > $CURRENT.tmp && mv -f $CURRENT.tmp $CURRENT",
            "rm -f $LEGACY_EXTERNAL_OPT_IN",
            if (prev != null && prev != safe) "echo ${RootShell.q(prev)} > $PREVIOUS.tmp && mv -f $PREVIOUS.tmp $PREVIOUS" else "true",
            timeoutMs = 60_000
        )
        if (!r.ok) {
            return Result.failure(
                IllegalStateException(
                    if (r.code == 9) "The binary does not run on this device (wrong ABI?)" else r.err.ifBlank { "install failed (${r.code})" }
                )
            )
        }
        if (!authorizeExternal(safe)) return Result.failure(IllegalStateException("Cannot authorize external core for this installation"))
        prune(keep = setOf(safe, prev ?: ""))
        return Result.success(versionOf("$final/sing-box") ?: safe)
    }

    @Synchronized
    fun rollback(): Boolean {
        val prev = RootShell.run("cat $PREVIOUS 2>/dev/null").out.trim()
        if (!validLabel(prev) || versionOf("$CORES/$prev/sing-box") == null) return false
        val cur = activeLabel()
        return RootShell.run(
            "set -e",
            "echo ${RootShell.q(prev)} > $CURRENT.tmp && mv -f $CURRENT.tmp $CURRENT",
            "rm -f $LEGACY_EXTERNAL_OPT_IN",
            if (cur != null) "echo ${RootShell.q(cur)} > $PREVIOUS.tmp && mv -f $PREVIOUS.tmp $PREVIOUS" else "true"
        ).ok && authorizeExternal(prev)
    }

    /** Back to the core shipped with the app; downloaded files stay intact. */
    @Synchronized
    fun useBundled(): Boolean {
        val file = externalSelection
        if (file.exists() && !file.delete()) return false
        // Older versions used this marker. It is no longer authoritative.
        RootShell.run("rm -f $LEGACY_EXTERNAL_OPT_IN")
        return true
    }

    private fun prune(keep: Set<String>) {
        val all = installedLabels()
        if (all.size <= KEEP) return
        val removable = all.filter { it !in keep }.dropLast(maxOf(0, KEEP - keep.size))
        removable.forEach { RootShell.run("rm -rf $CORES/${RootShell.q(it)}") }
    }

    // ---- download ----

    data class Release(val tag: String, val assetName: String, val url: String)

    fun fetchLatest(client: OkHttpClient = OkHttpClient()): Result<Release> = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "GitHub: HTTP ${resp.code}" }
            val json = JSONObject(resp.body!!.string())
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            val tokens = abiToken()
            var best: Pair<String, String>? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name").lowercase()
                if (!name.contains("android")) continue
                if (!(name.endsWith(".tar.gz") || name.endsWith(".tgz"))) continue
                if (tokens.none { name.contains(it) }) continue
                // prefer the most specific token (arm64 over arm)
                if (best == null || tokens.indexOfFirst { name.contains(it) } <
                    tokens.indexOfFirst { best!!.first.lowercase().contains(it) }
                ) best = a.getString("name") to a.getString("browser_download_url")
            }
            val (n, u) = best ?: error("No Android asset for ${tokens.first()} in release $tag")
            Release(tag, n, u)
        }
    }

    /** Downloads the release archive, extracts it as root and installs the binary. */
    fun downloadAndInstall(rel: Release, client: OkHttpClient = OkHttpClient()): Result<String> = runCatching {
        val dir = File(SagerNet.application.cacheDir, "rootcore").apply { deleteRecursively(); mkdirs() }
        val archive = File(dir, "core.tar.gz")
        client.newCall(Request.Builder().url(rel.url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "download: HTTP ${resp.code}" }
            archive.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        val x = File(dir, "x").apply { mkdirs() }
        val r = RootShell.run(
            "tar -xzf ${RootShell.q(archive)} -C ${RootShell.q(x)}",
            "find ${RootShell.q(x)} -type f -name 'sing-box*' ! -name '*.sha*' | head -1",
            timeoutMs = 60_000
        )
        val bin = r.out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: error("sing-box not found in the archive")
        // make it readable for the app process before install() copies it
        RootShell.run("cp ${RootShell.q(bin)} ${RootShell.q(File(dir, "sing-box"))} && chmod 644 ${RootShell.q(File(dir, "sing-box"))}")
        val res = install(File(dir, "sing-box"), rel.tag).getOrThrow()
        dir.deleteRecursively()
        res
    }
}
