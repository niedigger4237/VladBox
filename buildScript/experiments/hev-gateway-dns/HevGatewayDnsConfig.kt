package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Staged core-config transformation; NOT wired to RootDaemon or the APK.
 * Caller must establish eligibility with RootTetherDnsPolicy, reserve the port,
 * verify both transports/families and own the INPUT guard before starting core.
 * This does not allocate a socket or install any firewall rule.
 */
internal object HevGatewayDnsConfig {
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

// Standalone host checks. Not a substitute for the patched core's config check
// or Android TCP/UDP, reverse-NAT and dual-stack readiness verification.
fun main() {
    fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject().apply {
        fields.forEach { (key, value) -> add(key, value) }
    }
    fun array(vararg items: JsonElement) = JsonArray().apply { items.forEach { add(it) } }
    fun str(value: String) = JsonPrimitive(value)
    fun JsonObject.f(key: String) = entrySet().first { it.key == key }.value
    val oldRule = obj("port" to array(JsonPrimitive(53)), "action" to str("hijack-dns"))
    val later = obj("action" to str("route"), "outbound" to str("proxy"))
    val socks = obj("type" to str("socks"), "tag" to str("socks-in"), "listen_port" to JsonPrimitive(10808))
    val base = obj("inbounds" to array(socks), "dns" to obj(), "route" to obj("rules" to array(oldRule, later)))
    fun prepare(source: JsonObject = base, port: Int = 15353, ipv6: Boolean = true,
                occupied: Set<Int> = emptySet(), eligible: Boolean = true) =
        HevGatewayDnsConfig.prepare(source, port, ipv6, occupied, eligible)
    var count = 0
    fun checkCase(value: Boolean) { check(value); count++ }
    val output = prepare()!!
    val newInbounds = output.f("inbounds").asJsonArray.toList()
    val newRules = output.f("route").asJsonObject.f("rules").asJsonArray.toList()
    val listener = newInbounds.last().asJsonObject
    checkCase(newInbounds.size == 2 && newInbounds.first() == socks)
    checkCase(listener.f("tag").asString == HevGatewayDnsConfig.TAG)
    checkCase(listener.f("type").asString == "direct")
    checkCase(listener.f("listen_port").asNumber.toInt() == 15353)
    checkCase(listener.f("listen").asString == "::")
    checkCase(listener.entrySet().none { it.key == "network" })
    checkCase(newRules.first().asJsonObject.f("inbound").asJsonArray.single().asString == HevGatewayDnsConfig.TAG)
    checkCase(newRules.first().asJsonObject.f("action").asString == "hijack-dns")
    checkCase(newRules.drop(1) == listOf(oldRule, later))
    checkCase(base.f("inbounds").asJsonArray.count() == 1)
    checkCase(base.f("route").asJsonObject.f("rules").asJsonArray.count() == 2)
    checkCase(prepare(ipv6 = false)!!.f("inbounds").asJsonArray.last().asJsonObject.f("listen").asString == "0.0.0.0")
    checkCase(prepare(port = 53) == null)
    checkCase(prepare(port = 65536) == null)
    checkCase(prepare(port = 10808) == null)
    checkCase(prepare(occupied = setOf(15353)) == null)
    checkCase(prepare(eligible = false) == null)
    checkCase(prepare(source = output) == null)
    checkCase(prepare(source = obj("inbounds" to array(socks))) == null)
    checkCase(prepare(source = obj("inbounds" to array(socks), "dns" to obj(), "route" to obj("rules" to array()))) == null)
    println("PASS $count/$count")
}
