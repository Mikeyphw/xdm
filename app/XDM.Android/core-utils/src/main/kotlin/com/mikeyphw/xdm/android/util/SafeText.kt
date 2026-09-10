package com.mikeyphw.xdm.android.util

private val reservedFileNameCharacters = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
private val internalExceptionMarkers = listOf(
    "PatternSyntaxException",
    "java.util.regex",
    "kotlin.text.Regex",
    "Unclosed character class",
    "Dangling meta character",
    "Illegal repetition",
    "Look-behind group",
    "Unknown character property name",
)

fun sanitizeFileName(value: String, fallback: String = "download.bin", maxLength: Int = 180): String {
    val cleaned = buildString(value.length) {
        value.trim().forEach { character ->
            append(
                when {
                    character in reservedFileNameCharacters -> '_'
                    character.code in 0x00..0x1F -> '_'
                    character.code == 0x7F -> '_'
                    else -> character
                },
            )
        }
    }
        .trim('.', ' ')
        .ifBlank { fallback }
        .take(maxLength)
        .trim('.', ' ')
    return cleaned.ifBlank { fallback }
}

fun sanitizeNotificationText(message: String?, fallback: String, maxLength: Int = 180): String {
    var normalized = collapseUserVisibleWhitespace(message.orEmpty())
    if (normalized.isBlank()) return fallback
    if (internalExceptionMarkers.any { marker -> normalized.contains(marker, ignoreCase = true) }) return fallback
    if (normalized.contains("Exception:") || normalized.contains(" at ")) return fallback

    // Notifications can appear on lock screens. Redact credentials more aggressively than ordinary UI.
    normalized = normalized
        .replace(Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password)\s*[:=]\s*[^\s,;]+""")) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        .replace(Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}"""), "$1 <redacted>")
        .replace(Regex("""(?i)(https?://)([^/@\s:]+):([^/@\s]+)@"""), "$1<redacted>@")
        .replace(Regex("""(?i)([?&](?:token|access_token|refresh_token|api[_-]?key|key|secret|password|signature|sig|auth|authorization)=)[^&#\s]+"""), "$1<redacted>")

    return normalized.take(maxLength).trim().ifBlank { fallback }
}

private fun collapseUserVisibleWhitespace(value: String): String = buildString(value.length) {
    var previousWasSpace = false
    value.forEach { character ->
        val output = if (character.code in 0x00..0x1F || character.code == 0x7F || character.isWhitespace()) ' ' else character
        if (output == ' ') {
            if (!previousWasSpace) append(output)
            previousWasSpace = true
        } else {
            append(output)
            previousWasSpace = false
        }
    }
}.trim()
