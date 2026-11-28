package io.nekohasekai.sagernet.fmt

/** Shared labels independent of the reflective DNS rule codec. */
internal object DnsTargetCatalog {
    val entries = listOf("dns-local" to "System DNS", "dns-direct" to "Direct DNS",
        "dns-remote" to "Remote DNS", "dns-bootstrap" to "Bootstrap DNS", "block" to "Block (NXDOMAIN)")
    fun label(target: String) = entries.firstOrNull { it.first == target }?.second ?: target
}
