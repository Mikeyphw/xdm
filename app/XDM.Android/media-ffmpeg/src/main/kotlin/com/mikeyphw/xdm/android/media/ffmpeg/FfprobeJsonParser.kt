package com.mikeyphw.xdm.android.media.ffmpeg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object FfprobeJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): FfprobeResult {
        val root = json.parseToJsonElement(raw).jsonObject
        val format = root["format"]?.jsonObject
        val streams = root["streams"]?.jsonArray.orEmpty().map { element ->
            val stream = element.jsonObject
            val tags = stream["tags"] as? JsonObject
            FfprobeStream(
                index = stream.int("index") ?: -1,
                codecType = stream.text("codec_type"),
                codecName = stream.text("codec_name"),
                codecLongName = stream.text("codec_long_name"),
                profile = stream.text("profile"),
                width = stream.int("width"),
                height = stream.int("height"),
                sampleRate = stream.text("sample_rate")?.toIntOrNull(),
                channels = stream.int("channels"),
                language = tags?.get("language")?.jsonPrimitive?.contentOrNull,
            )
        }
        return FfprobeResult(
            formatName = format?.text("format_name"),
            formatLongName = format?.text("format_long_name"),
            durationSeconds = format?.text("duration")?.toDoubleOrNull(),
            sizeBytes = format?.long("size"),
            bitRate = format?.long("bit_rate"),
            streams = streams,
        )
    }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.longOrNull ?: text(key)?.toLongOrNull()
}
