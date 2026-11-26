package io.nekohasekai.sagernet.fmt.v2ray

internal fun parseRealityHybridMode(value: String?): String = when (value?.trim()?.lowercase(java.util.Locale.ROOT)) {
    null, "", "auto", "automatic" -> "auto"
    "1", "true", "enabled", "on" -> "enabled"
    "0", "false", "disabled", "off" -> "disabled"
    else -> throw IllegalArgumentException("Invalid X25519MLKEM768 setting")
}

internal fun realityHybridQueryValue(mode: String): String? = when (mode) {
    "enabled" -> "true"
    "disabled" -> "false"
    else -> null
}
