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
    private const val EXTERNAL_OPT_IN = "$BASE/external-opt-in-v1"
    private const val KEEP = 3

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
        return r.out.trim().takeIf { r.ok && it.isNotEmpty() }
    }

    fun selectedLabel(): String = resolve()?.let { if (it.bundled) "Bundled (patched)" else "External: ${it.label}" } ?: "Unavailable"

    fun fingerprint(binary: String): String = RootShell.run("sha256sum ${RootShell.q(binary)}").out.trim().substringBefore(" ").ifBlank { "unavailable" }

    fun installedLabels(): List<String> =
        RootShell.run("ls -1 $CORES 2>/dev/null").out.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun versionOf(binary: String): String? {
        val r = RootShell.run("${RootShell.q(binary)} version")
        return if (r.ok) r.out.lines().firstOrNull()?.trim() else null
    }

    /** Resolves the binary to run, falling back to the bundled one when the active core is unusable. */
    fun resolve(): Active? {
        activeLabel()?.takeIf { RootShell.run("test -f $EXTERNAL_OPT_IN").ok }?.let { label ->
            val path = "$CORES/$label/sing-box"
            if (versionOf(path) != null) return Active(path, label, false)
            Logs.w("active core $label is broken, falling back to the bundled core")
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
    fun install(binary: File, label: String): Result<String> {
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stage = "$CORES/.$safe.tmp"
        val final = "$CORES/$safe"
        val prev = activeLabel()
        val r = RootShell.run(
            "mkdir -p $CORES",
            "rm -rf $stage && mkdir -p $stage",
            "cp ${RootShell.q(binary)} $stage/sing-box && chmod 755 $stage/sing-box",
            // verify before it becomes visible
            "$stage/sing-box version >/dev/null || { rm -rf $stage; exit 9; }",
            "rm -rf $final && mv $stage $final",
            // atomic switch: rename(2) over a regular file
            "echo ${RootShell.q(safe)} > $CURRENT.tmp && mv -f $CURRENT.tmp $CURRENT",
            "touch $EXTERNAL_OPT_IN",
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
        prune(keep = setOf(safe, prev ?: ""))
        return Result.success(versionOf("$final/sing-box") ?: safe)
    }

    fun rollback(): Boolean {
        val prev = RootShell.run("cat $PREVIOUS 2>/dev/null").out.trim()
        if (prev.isEmpty() || versionOf("$CORES/$prev/sing-box") == null) return false
        val cur = activeLabel()
        return RootShell.run(
            "echo ${RootShell.q(prev)} > $CURRENT.tmp && mv -f $CURRENT.tmp $CURRENT",
            "touch $EXTERNAL_OPT_IN",
            if (cur != null) "echo ${RootShell.q(cur)} > $PREVIOUS.tmp && mv -f $PREVIOUS.tmp $PREVIOUS" else "true"
        ).ok
    }

    /** Back to the core shipped with the app. */
    fun useBundled() = RootShell.run("rm -f $EXTERNAL_OPT_IN").ok

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
