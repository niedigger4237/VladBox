// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package io.nekohasekai.sagernet.bg.root

import java.nio.charset.StandardCharsets

internal const val MaxServiceControlListSize = 64
private const val MaxCronBytes = 256

sealed interface ServiceCronParseResult {
    data class Valid(val expression: String) : ServiceCronParseResult

    data class Invalid(val reason: ServiceCronError) : ServiceCronParseResult
}

enum class ServiceCronError {
    FieldCount,
    EmptyTerm,
    InvalidSyntax,
    OutOfRange,
    InvalidStep,
}

fun parseServiceCron(value: String): ServiceCronParseResult {
    val expression = value.trim()
    if (expression.isEmpty() || expression.length > MaxCronBytes) return ServiceCronParseResult.Invalid(ServiceCronError.FieldCount)
    val fields = expression.split(Regex("\\s+"))
    if (fields.size != CronField.entries.size) {
        return ServiceCronParseResult.Invalid(ServiceCronError.FieldCount)
    }
    fields.zip(CronField.entries).forEach { (field, definition) ->
        parseCronField(field, definition)?.let { return ServiceCronParseResult.Invalid(it) }
    }
    return ServiceCronParseResult.Valid(expression)
}

private enum class CronField(val minimum: Int, val maximum: Int) {
    Minute(0, 59),
    Hour(0, 23),
    DayOfMonth(1, 31),
    Month(1, 12),
    DayOfWeek(0, 7),
}

private fun parseCronField(value: String, field: CronField): ServiceCronError? {
    if (value.isEmpty()) return ServiceCronError.InvalidSyntax
    val terms = value.split(',')
    if (terms.any(String::isEmpty)) return ServiceCronError.EmptyTerm
    for (term in terms) {
        val stepParts = term.split('/')
        if (stepParts.size > 2 || stepParts[0].isEmpty()) return ServiceCronError.InvalidSyntax
        if (stepParts.size == 2) {
            val step = stepParts[1].takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull() ?: return ServiceCronError.InvalidStep
            if (step !in 1L..4294967295L) return ServiceCronError.InvalidStep
        }
        val base = stepParts[0]
        if (base == "*") continue
        val rangeParts = base.split('-')
        if (rangeParts.size > 2 || rangeParts.any(String::isEmpty)) {
            return ServiceCronError.InvalidSyntax
        }
        val start = rangeParts[0].toAsciiIntOrNull() ?: return ServiceCronError.InvalidSyntax
        val end = if (rangeParts.size == 2) {
            rangeParts[1].toAsciiIntOrNull() ?: return ServiceCronError.InvalidSyntax
        } else {
            start
        }
        if (start !in field.minimum..field.maximum || end !in field.minimum..field.maximum) {
            return ServiceCronError.OutOfRange
        }
        if (start > end) return ServiceCronError.OutOfRange
    }
    return null
}

private fun String.toAsciiIntOrNull(): Int? =
    takeIf { value -> value.isNotEmpty() && value.all { character -> character in '0'..'9' } }
        ?.toIntOrNull()

fun normalizeBssidOrNull(value: String): String? {
    val compact = value.filterNot { character -> character == ':' || character == '-' }
    if (compact.length != 12 || compact.any { character -> character !in '0'..'9' && character.lowercaseChar() !in 'a'..'f' }) {
        return null
    }
    return compact.lowercase().chunked(2).joinToString(":")
}

fun isValidServiceSsid(value: String): Boolean {
    val size = value.toByteArray(StandardCharsets.UTF_8).size
    return size in 1..32 && '\u0000' !in value
}

// Preserve the original comma/semicolon-separated inputs for compatibility.
internal fun serviceControlIdentifiers(value: String): List<String> =
    value.split('\n', ',', ';').map(String::trim).filter(String::isNotEmpty).distinct()

internal fun validServiceControlSsids(value: String): Boolean =
    serviceControlIdentifiers(value).let { it.size <= MaxServiceControlListSize && it.all(::isValidServiceSsid) }

internal fun validServiceControlBssids(value: String): Boolean =
    serviceControlIdentifiers(value).let { it.size <= MaxServiceControlListSize && it.all { id -> normalizeBssidOrNull(id) != null } }
