package io.nekohasekai.sagernet.bg.root

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

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
