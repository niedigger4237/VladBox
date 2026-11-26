package io.nekohasekai.sagernet.fmt.v2ray

import java.util.Base64

internal fun validateRealityMldsa65(value: String): String {
    val key = value.trim()
    if (key.isEmpty()) return ""
    require(key.length == 2603 && key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) { "Invalid ML-DSA-65 public key" }
    val decoded = Base64.getUrlDecoder().decode(key)
    require(decoded.size == 1952 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == key) { "Invalid ML-DSA-65 public key" }
    return key
}

internal fun isValidRealityMldsa65(value: String): Boolean =
    runCatching { validateRealityMldsa65(value) }.isSuccess
