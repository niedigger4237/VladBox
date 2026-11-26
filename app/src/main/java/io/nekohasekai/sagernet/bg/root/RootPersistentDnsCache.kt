package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs

/** Called only after the ROOT boot monitor has been quiesced and cleanup verified.
 * A prepared absolute path is carried unchanged into boot and live configs.
 * Never manage user cache_file options or alter firewall/tethering state. */
internal object RootPersistentDnsCache {
    private val directory get() = "${RootCore.BASE}/nf-dns-cache-v1"

    fun prepare(config: String, scope: String?): String {
        return runCatching {
            val root = JsonParser.parseString(config).asJsonObject
            val existing = root.get("experimental")
            if (existing != null && !existing.isJsonObject) return config
            val experimental = existing?.asJsonObject ?: JsonObject()
            if (experimental.has("cache_file")) return config
            val script = SagerNet.application.assets.open("root/dns-cache-prepare.sh")
                .bufferedReader().use { it.readText() }
            val result = RootShell.run(
                "sh -c ${RootShell.q(script)} sh ${RootShell.q(directory)} ${RootShell.q(scope.orEmpty())}",
                timeoutMs = 10000
            )
            if (!result.ok || result.out != if (scope == null) "memory" else "persistent") {
                Logs.w("ROOT managed DNS cache unavailable; keeping memory cache")
                return config
            }
            if (scope == null) return config
            experimental.add("cache_file", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("path", "$directory/cache.db")
                addProperty("store_dns", true)
                addProperty("store_fakeip", false)
            })
            root.add("experimental", experimental)
            Logs.i("ROOT managed persistent DNS cache prepared")
            root.toString()
        }.getOrElse {
            Logs.w("ROOT managed DNS cache preparation failed; keeping memory cache")
            config
        }
    }
}
