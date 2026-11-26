package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Managed HEV core-config transformation; activation remains explicitly gated.
 * Caller must establish eligibility with RootTetherDnsPolicy, reserve the port,
 * verify both transports/families and own the INPUT guard before starting core.
 * This does not allocate a socket or install any firewall rule.
 */
internal object HevGatewayDnsConfig {
    // Experimental branch only: RootDaemon restricts eligibility to a
    // verified active IPv4 HEV downstream without boot or Service Control.
    // Native guard, ownership and journal admission remain mandatory.
    const val ENABLE_RUNTIME = true
    const val PORT = 15353
    const val TAG = "nf-hev-gateway-dns"

    private fun JsonObject.field(name: String): JsonElement? =
        entrySet().firstOrNull { it.key == name }?.value
    private fun JsonElement.containsTag(): Boolean = when {
        isJsonObject -> asJsonObject.entrySet().any { it.key == TAG || it.value.containsTag() }
        isJsonArray -> asJsonArray.any { it.containsTag() }
        isJsonPrimitive -> asJsonPrimitive.isString && asString == TAG
        else -> false
    }
    private fun JsonElement.copyTree(): JsonElement = when {
        isJsonObject -> JsonObject().also { out ->
            asJsonObject.entrySet().forEach { out.add(it.key, it.value.copyTree()) }
        }
        isJsonArray -> JsonArray().also { out -> asJsonArray.forEach { out.add(it.copyTree()) } }
        else -> this // Gson primitives and null are immutable.
    }

    fun prepare(source: JsonObject, port: Int, ipv6: Boolean,
                occupiedPorts: Set<Int>, eligible: Boolean): JsonObject? {
        if (!eligible || port !in 1024..65535 || port in occupiedPorts || source.containsTag()) return null
        val existing = source.field("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val route = source.field("route")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val rules = route.field("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (source.field("dns")?.isJsonObject != true || existing.none() || rules.none()) return null
        for (inbound in existing) {
            if (!inbound.isJsonObject) return null
            val configuredPort = inbound.asJsonObject.field("listen_port") ?: continue
            if (!configuredPort.isJsonPrimitive || !configuredPort.asJsonPrimitive.isNumber) return null
            val value = configuredPort.asNumber.toString().toIntOrNull() ?: return null
            if (value !in 0..65535 || value == port) return null
        }
        if (rules.any { !it.isJsonObject }) return null
        val result = source.copyTree().asJsonObject
        val inbounds = result.field("inbounds")!!.asJsonArray
        inbounds.add(JsonObject().apply {
            add("type", JsonPrimitive("direct"))
            add("tag", JsonPrimitive(TAG))
            add("listen", JsonPrimitive(if (ipv6) "::" else "0.0.0.0"))
            add("listen_port", JsonPrimitive(port))
            // Omit network: the direct inbound must handle TCP and UDP.
        })
        val copiedRoute = result.field("route")!!.asJsonObject
        val previous = copiedRoute.field("rules")!!.asJsonArray
        copiedRoute.add("rules", JsonArray().apply {
            add(JsonObject().apply {
                add("inbound", JsonArray().apply { add(JsonPrimitive(TAG)) })
                add("action", JsonPrimitive("hijack-dns"))
            })
            previous.forEach { add(it) }
        })
        return result
    }
}

