package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore

/** Готовые DNS-серверы для Remote и Direct DNS (+ свой адрес). */
object DnsPresets {
    val all = linkedMapOf(
        "local" to "local",
        "cloudflare" to "https://1.1.1.1/dns-query",
        "quad9" to "https://9.9.9.9/dns-query",
        "google" to "https://8.8.8.8/dns-query",
        "adguard" to "https://dns.adguard-dns.com/dns-query",
    )

    private fun resolve(preset: String, custom: String, fallback: String): String =
        if (preset == "custom") custom else all[preset] ?: all[fallback]!!

    fun remote(): String = resolve(DataStore.remoteDnsPreset, DataStore.remoteDns, "google")
    fun direct(): String = resolve(DataStore.directDnsPreset, DataStore.directDns, "quad9")
}
