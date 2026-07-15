package com.mikeyphw.xdm.android.transfer.nativeengine

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class NativeCheckpointStore {
    fun load(path: Path): NativeCheckpoint? {
        if (!Files.exists(path)) return null
        val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        return NativeCheckpoint(
            downloadId = text.requiredString("downloadId"),
            sourceUrl = text.requiredString("sourceUrl"),
            effectiveUrl = text.requiredString("effectiveUrl"),
            destinationPath = text.requiredString("destinationPath"),
            partialPath = text.requiredString("partialPath"),
            expectedLength = text.optionalLong("expectedLength"),
            etag = text.optionalString("etag"),
            lastModified = text.optionalString("lastModified"),
            rangeSupported = text.requiredBoolean("rangeSupported"),
            segments = text.requiredSegments(),
            persistedAtEpochMs = text.requiredLong("persistedAtEpochMs"),
        )
    }

    fun save(path: Path, checkpoint: NativeCheckpoint) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        val document = buildString {
            append('{')
            appendJsonString("downloadId", checkpoint.downloadId); append(',')
            appendJsonString("sourceUrl", checkpoint.sourceUrl); append(',')
            appendJsonString("effectiveUrl", checkpoint.effectiveUrl); append(',')
            appendJsonString("destinationPath", checkpoint.destinationPath); append(',')
            appendJsonString("partialPath", checkpoint.partialPath); append(',')
            appendJsonLong("expectedLength", checkpoint.expectedLength); append(',')
            appendJsonString("etag", checkpoint.etag); append(',')
            appendJsonString("lastModified", checkpoint.lastModified); append(',')
            append("\"rangeSupported\":").append(checkpoint.rangeSupported).append(',')
            append("\"segments\":[")
            checkpoint.segments.forEachIndexed { index, segment ->
                if (index > 0) append(',')
                append('{')
                append("\"index\":").append(segment.index).append(',')
                append("\"startByte\":").append(segment.startByte).append(',')
                appendJsonLong("endByteInclusive", segment.endByteInclusive); append(',')
                append("\"completedBytes\":").append(segment.completedBytes).append(',')
                append("\"complete\":").append(segment.complete)
                append('}')
            }
            append("],\"persistedAtEpochMs\":").append(checkpoint.persistedAtEpochMs)
            append('}')
        }
        val bytes = document.toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        runCatching { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    fun delete(path: Path) {
        Files.deleteIfExists(path)
    }
}

private fun String.requiredString(name: String): String = requireNotNull(optionalString(name)) { "Missing checkpoint field $name" }
private fun String.optionalString(name: String): String? {
    val match = Regex("\\\"${Regex.escape(name)}\\\":(null|\\\"((?:\\\\.|[^\\\"])*)\\\")").find(this) ?: return null
    if (match.groupValues[1] == "null") return null
    return unescapeJson(match.groupValues[2])
}
private fun String.requiredLong(name: String): Long = requireNotNull(optionalLong(name)) { "Missing checkpoint field $name" }
private fun String.optionalLong(name: String): Long? {
    val match = Regex("\\\"${Regex.escape(name)}\\\":(null|-?\\d+)").find(this) ?: return null
    return match.groupValues[1].takeUnless { it == "null" }?.toLong()
}
private fun String.requiredBoolean(name: String): Boolean {
    val value = Regex("\\\"${Regex.escape(name)}\\\":(true|false)").find(this)?.groupValues?.get(1)
        ?: error("Missing checkpoint field $name")
    return value.toBooleanStrict()
}
private fun String.requiredSegments(): List<NativeSegmentCheckpoint> {
    val body = Regex("\\\"segments\\\":\\[(.*?)]").find(this)?.groupValues?.get(1)
        ?: error("Missing checkpoint segments")
    if (body.isBlank()) return emptyList()
    return Regex("\\{([^{}]+)}").findAll(body).map { match ->
        val item = match.value
        NativeSegmentCheckpoint(
            index = item.requiredLong("index").toInt(),
            startByte = item.requiredLong("startByte"),
            endByteInclusive = item.optionalLong("endByteInclusive"),
            completedBytes = item.requiredLong("completedBytes"),
            complete = item.requiredBoolean("complete"),
        )
    }.toList()
}

private fun StringBuilder.appendJsonString(name: String, value: String?) {
    append('"').append(name).append("\":")
    if (value == null) append("null") else append('"').append(escapeJson(value)).append('"')
}
private fun StringBuilder.appendJsonLong(name: String, value: Long?) {
    append('"').append(name).append("\":").append(value ?: "null")
}
private fun escapeJson(value: String): String = buildString(value.length + 8) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}
private fun unescapeJson(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character != '\\' || index >= value.length) {
            result.append(character)
            continue
        }
        when (val escaped = value[index++]) {
            '\\' -> result.append('\\')
            '"' -> result.append('"')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                require(index + 4 <= value.length) { "Invalid Unicode escape in checkpoint" }
                result.append(value.substring(index, index + 4).toInt(16).toChar())
                index += 4
            }
            else -> result.append(escaped)
        }
    }
    return result.toString()
}
