package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.ExternalUrlPolicy

/** User-facing FFmpeg runtime policy. Embedded remains the safe/default owner. */
enum class MediaFfmpegRuntimePreference(val label: String, val description: String) {
    Automatic(
        "Automatic",
        "Use XDM's embedded FFmpeg first; use a verified Termux FFmpeg only for session-safe fallback.",
    ),
    Embedded(
        "Embedded FFmpeg",
        "Always use the app-owned FFmpeg/FFprobe runtime. Never fall back to Termux.",
    ),
    Termux(
        "Termux FFmpeg",
        "Use the user-managed Termux FFmpeg/FFprobe runtime when the media session can cross that boundary safely.",
    ),
}

enum class MediaFfmpegRuntimeSource(val label: String) {
    Embedded("Embedded FFmpeg"),
    Termux("Termux FFmpeg"),
    Unavailable("Unavailable"),
}

data class MediaFfmpegRuntimeContext(
    val preference: MediaFfmpegRuntimePreference = MediaFfmpegRuntimePreference.Automatic,
    val embeddedReady: Boolean = true,
    val termuxBridgeReady: Boolean = false,
    val termuxFfmpegReady: Boolean = false,
    val termuxFfprobeReady: Boolean = false,
    /** False when cookies, Authorization, signed URLs, or other execution-sensitive session state is required. */
    val termuxNetworkFallbackEligible: Boolean = false,
) {
    val termuxReady: Boolean
        get() = termuxBridgeReady && termuxFfmpegReady && termuxFfprobeReady
}

data class MediaFfmpegRuntimeDecision(
    val source: MediaFfmpegRuntimeSource,
    val requestedPreference: MediaFfmpegRuntimePreference,
    val fallbackUsed: Boolean,
    val runnable: Boolean,
    val reason: String,
    val warnings: List<String> = emptyList(),
) {
    val summary: String
        get() = listOf(
            "requested=${requestedPreference.label}",
            "selected=${source.label}",
            if (fallbackUsed) "fallback" else "primary",
            if (runnable) "ready" else "blocked",
            reason,
        ).joinToString(" • ")
}

object MediaFfmpegRuntimeRoutingPolicy {
    fun decide(context: MediaFfmpegRuntimeContext): MediaFfmpegRuntimeDecision = when (context.preference) {
        MediaFfmpegRuntimePreference.Embedded -> if (context.embeddedReady) {
            MediaFfmpegRuntimeDecision(
                source = MediaFfmpegRuntimeSource.Embedded,
                requestedPreference = context.preference,
                fallbackUsed = false,
                runnable = true,
                reason = "The app-owned FFmpeg/FFprobe runtime passed its capability probe.",
            )
        } else {
            MediaFfmpegRuntimeDecision(
                source = MediaFfmpegRuntimeSource.Unavailable,
                requestedPreference = context.preference,
                fallbackUsed = false,
                runnable = false,
                reason = "Embedded FFmpeg was explicitly selected but is not ready.",
                warnings = listOf("Repair or reinstall the embedded media runtime before retrying."),
            )
        }

        MediaFfmpegRuntimePreference.Termux -> termuxDecision(context, fallbackUsed = false)

        MediaFfmpegRuntimePreference.Automatic -> when {
            context.embeddedReady -> MediaFfmpegRuntimeDecision(
                source = MediaFfmpegRuntimeSource.Embedded,
                requestedPreference = context.preference,
                fallbackUsed = false,
                runnable = true,
                reason = "Automatic routing selected the app-owned FFmpeg/FFprobe runtime.",
            )
            context.termuxReady && context.termuxNetworkFallbackEligible -> termuxDecision(context, fallbackUsed = true)
            context.termuxReady -> MediaFfmpegRuntimeDecision(
                source = MediaFfmpegRuntimeSource.Unavailable,
                requestedPreference = context.preference,
                fallbackUsed = false,
                runnable = false,
                reason = "Embedded FFmpeg is unavailable and this authenticated or signed media session cannot safely cross into Termux.",
                warnings = listOf("Refresh or repair embedded FFmpeg; XDM will not leak session credentials to an external process."),
            )
            else -> MediaFfmpegRuntimeDecision(
                source = MediaFfmpegRuntimeSource.Unavailable,
                requestedPreference = context.preference,
                fallbackUsed = false,
                runnable = false,
                reason = "Neither the embedded runtime nor a verified Termux FFmpeg/FFprobe pair is available.",
            )
        }
    }

    fun termuxFallbackEligible(spec: MediaQueuedDownloadSpec): Boolean {
        if (spec.requestHeaders.isNotEmpty()) return false
        val inputs = if (spec.selectedInputs.isNotEmpty()) spec.selectedInputs else emptyList()
        if (inputs.any { it.headers.isNotEmpty() }) return false
        val urls = buildList {
            add(spec.sourceUrl)
            inputs.forEach { add(it.url) }
        }.filter(String::isNotBlank)
        return MediaExecutionSecurityPolicy.termuxNetworkEligible(urls, spec.requestHeaders)
    }

    private fun termuxDecision(
        context: MediaFfmpegRuntimeContext,
        fallbackUsed: Boolean,
    ): MediaFfmpegRuntimeDecision = when {
        !context.termuxBridgeReady -> MediaFfmpegRuntimeDecision(
            MediaFfmpegRuntimeSource.Unavailable,
            context.preference,
            fallbackUsed = false,
            runnable = false,
            reason = "Termux or its RUN_COMMAND bridge is not ready.",
        )
        !context.termuxFfmpegReady || !context.termuxFfprobeReady -> MediaFfmpegRuntimeDecision(
            MediaFfmpegRuntimeSource.Unavailable,
            context.preference,
            fallbackUsed = false,
            runnable = false,
            reason = "A fresh Termux probe has not verified both FFmpeg and FFprobe.",
        )
        !context.termuxNetworkFallbackEligible -> MediaFfmpegRuntimeDecision(
            MediaFfmpegRuntimeSource.Unavailable,
            context.preference,
            fallbackUsed = false,
            runnable = false,
            reason = "This media session contains credentials, signed URLs, private-network state, or request headers that must remain app-owned.",
            warnings = listOf("Use Embedded FFmpeg for authenticated or signed media."),
        )
        else -> MediaFfmpegRuntimeDecision(
            source = MediaFfmpegRuntimeSource.Termux,
            requestedPreference = context.preference,
            fallbackUsed = fallbackUsed,
            runnable = true,
            reason = if (fallbackUsed) {
                "The embedded runtime is unavailable; Automatic routing selected a fresh verified Termux FFmpeg/FFprobe pair for this public session."
            } else {
                "Termux FFmpeg was explicitly selected and its FFmpeg/FFprobe probe is fresh."
            },
            warnings = if (fallbackUsed) listOf("Using user-managed Termux media runtime for this attempt.") else emptyList(),
        )
    }
}
