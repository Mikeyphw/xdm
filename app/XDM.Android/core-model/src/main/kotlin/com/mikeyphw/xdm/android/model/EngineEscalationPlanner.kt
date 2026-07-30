package com.mikeyphw.xdm.android.model

import java.net.URI
import java.util.Locale

/**
 * Human-facing engine escalation plan for Add Download review.
 *
 * This planner is deliberately pure and review-only: it does not start transfers, inspect files,
 * persist browser session state, or render raw URLs/headers/secrets. It turns request shape,
 * browser-session health, and backend compatibility into a clear next action so users do not have
 * to decode backend names or HTTP probe failures.
 */
data class EngineEscalationPlan(
    val title: String,
    val recommendedMethodLabel: String,
    val nextActionLabel: String,
    val reasonLabel: String,
    val guidance: String,
    val steps: List<EngineEscalationStep>,
    val alternatives: List<EngineEscalationAlternative>,
) {
    val hasAlternatives: Boolean get() = alternatives.isNotEmpty()
}

data class EngineEscalationStep(
    val label: String,
    val status: String,
    val guidance: String,
)

data class EngineEscalationAlternative(
    val methodLabel: String,
    val whenToUse: String,
)

object EngineEscalationPlanner {
    fun evaluate(
        draft: DownloadIntakeDraft?,
        recommendation: BackendRecommendation? = null,
        sessionHealth: BrowserSessionHealthReport? = null,
        lastHttpStatus: Int? = null,
    ): EngineEscalationPlan? {
        if (draft == null) return null
        val kind = draft.kind
        val hasProtectedContext = draft.requestHeaders.hasHeader("cookie") || draft.requestHeaders.hasHeader("authorization")
        val hasPageContext = draft.requestHeaders.hasHeader("referer") || !draft.pageUrl.isNullOrBlank()
        val hasBrowserIdentity = draft.requestHeaders.hasHeader("user-agent")
        val expiring = draft.url.hasCredentialBearingQuery() || draft.url.hasShortLivedPathHint() || sessionHealth?.expiryRiskLabel == "High"
        val blockedByServer = lastHttpStatus == 401 || lastHttpStatus == 403
        val largeDirectFile = kind == DownloadIntakeKind.DirectFile && (draft.contentLength ?: 0L) >= LargeFileThresholdBytes
        val site = draft.host?.takeIf(String::isNotBlank) ?: "source site"
        val suggested = suggestedMethod(
            kind = kind,
            recommendation = recommendation,
            hasProtectedContext = hasProtectedContext,
            expiring = expiring,
            blockedByServer = blockedByServer,
            largeDirectFile = largeDirectFile,
        )
        val nextAction = nextAction(
            kind = kind,
            hasProtectedContext = hasProtectedContext,
            hasPageContext = hasPageContext,
            hasBrowserIdentity = hasBrowserIdentity,
            expiring = expiring,
            blockedByServer = blockedByServer,
        )
        val reason = reasonLabel(
            kind = kind,
            hasProtectedContext = hasProtectedContext,
            expiring = expiring,
            blockedByServer = blockedByServer,
            largeDirectFile = largeDirectFile,
        )
        return EngineEscalationPlan(
            title = "Suggested method",
            recommendedMethodLabel = suggested,
            nextActionLabel = nextAction,
            reasonLabel = reason,
            guidance = guidance(
                site = site,
                kind = kind,
                hasProtectedContext = hasProtectedContext,
                hasPageContext = hasPageContext,
                hasBrowserIdentity = hasBrowserIdentity,
                expiring = expiring,
                blockedByServer = blockedByServer,
                largeDirectFile = largeDirectFile,
            ),
            steps = listOf(
                EngineEscalationStep(
                    label = "Request shape",
                    status = kind.safeLabel(),
                    guidance = requestShapeGuidance(kind),
                ),
                EngineEscalationStep(
                    label = "Session context",
                    status = when {
                        hasProtectedContext -> "Sign-in context captured"
                        hasPageContext || hasBrowserIdentity -> "Browser context partial"
                        else -> "No browser context"
                    },
                    guidance = "Private browser values stay backstage; only their presence is used for method choice.",
                ),
                EngineEscalationStep(
                    label = "Fallback path",
                    status = fallbackStatus(kind, largeDirectFile),
                    guidance = fallbackGuidance(kind, blockedByServer),
                ),
            ),
            alternatives = alternatives(kind, hasProtectedContext, largeDirectFile),
        )
    }

    private fun suggestedMethod(
        kind: DownloadIntakeKind,
        recommendation: BackendRecommendation?,
        hasProtectedContext: Boolean,
        expiring: Boolean,
        blockedByServer: Boolean,
        largeDirectFile: Boolean,
    ): String = when {
        blockedByServer -> "Refresh browser capture or inspect with yt-dlp"
        kind == DownloadIntakeKind.AdaptiveMedia -> "Media resolver or yt-dlp"
        kind == DownloadIntakeKind.PageOrUnknown -> "Inspect media before queueing"
        hasProtectedContext || expiring -> "XDM Native with captured session"
        largeDirectFile -> "aria2 segmented transfer"
        kind == DownloadIntakeKind.Torrent -> "Torrent-compatible handoff"
        recommendation?.backend == BackendType.Aria2 -> "aria2 segmented transfer"
        recommendation?.backend == BackendType.Native -> "XDM Native"
        else -> "Automatic safe default"
    }

    private fun nextAction(
        kind: DownloadIntakeKind,
        hasProtectedContext: Boolean,
        hasPageContext: Boolean,
        hasBrowserIdentity: Boolean,
        expiring: Boolean,
        blockedByServer: Boolean,
    ): String = when {
        blockedByServer -> "Refresh from browser"
        kind == DownloadIntakeKind.AdaptiveMedia -> "Inspect media first"
        kind == DownloadIntakeKind.PageOrUnknown -> "Inspect media first"
        hasProtectedContext && (!hasPageContext || !hasBrowserIdentity) -> "Refresh from browser"
        expiring -> "Queue before link expires"
        else -> "Add reviewed request"
    }

    private fun reasonLabel(
        kind: DownloadIntakeKind,
        hasProtectedContext: Boolean,
        expiring: Boolean,
        blockedByServer: Boolean,
        largeDirectFile: Boolean,
    ): String = when {
        blockedByServer -> "Server asked for browser access"
        kind == DownloadIntakeKind.AdaptiveMedia -> "Playlist needs resolver support"
        kind == DownloadIntakeKind.PageOrUnknown -> "Page needs media inspection"
        hasProtectedContext -> "Uses captured sign-in context"
        expiring -> "Link may be temporary"
        largeDirectFile -> "Large direct file benefits from segments"
        kind == DownloadIntakeKind.Torrent -> "Special handoff type"
        else -> "Direct request looks queueable"
    }

    private fun guidance(
        site: String,
        kind: DownloadIntakeKind,
        hasProtectedContext: Boolean,
        hasPageContext: Boolean,
        hasBrowserIdentity: Boolean,
        expiring: Boolean,
        blockedByServer: Boolean,
        largeDirectFile: Boolean,
    ): String = when {
        blockedByServer -> "$site refused a direct probe. Refresh the browser capture first, then use the captured session or inspect with yt-dlp when this is a watch page."
        kind == DownloadIntakeKind.AdaptiveMedia -> "This looks like HLS or DASH. Inspect media first so variants, audio, subtitles, and expiring playlist access are handled deliberately."
        kind == DownloadIntakeKind.PageOrUnknown -> "This may be a page instead of a file. Inspect media before queueing unless you know the URL itself is the downloadable resource."
        hasProtectedContext && (!hasPageContext || !hasBrowserIdentity) -> "Sign-in context was captured, but page or browser identity context is incomplete. Refreshing from the browser avoids another authentication failure."
        hasProtectedContext || expiring -> "This request may depend on temporary browser access. Native can use the captured session while it remains fresh."
        largeDirectFile -> "This is a large direct file without visible sign-in context. aria2 can improve retry and segmented transfer behavior."
        else -> "The request looks suitable for the normal queue. XDM can still fall back before any backend owns the destination."
    }

    private fun requestShapeGuidance(kind: DownloadIntakeKind): String = when (kind) {
        DownloadIntakeKind.DirectFile -> "A file-like URL can usually enter the queue directly."
        DownloadIntakeKind.DirectMedia -> "A direct media URL can queue, but inspection is useful for tracks or expiry."
        DownloadIntakeKind.AdaptiveMedia -> "Playlists should go through media resolution before transfer."
        DownloadIntakeKind.Torrent -> "Torrent handoffs need a compatible backend path."
        DownloadIntakeKind.PageOrUnknown -> "Pages should be inspected before direct transfer."
    }

    private fun fallbackStatus(kind: DownloadIntakeKind, largeDirectFile: Boolean): String = when {
        kind == DownloadIntakeKind.AdaptiveMedia || kind == DownloadIntakeKind.PageOrUnknown -> "yt-dlp available after inspection"
        largeDirectFile -> "aria2 available"
        kind == DownloadIntakeKind.DirectMedia -> "Native first, inspect if blocked"
        else -> "Automatic fallback allowed"
    }

    private fun fallbackGuidance(kind: DownloadIntakeKind, blockedByServer: Boolean): String = when {
        blockedByServer -> "Do not keep retrying the same stale direct request; recapture or inspect instead."
        kind == DownloadIntakeKind.AdaptiveMedia || kind == DownloadIntakeKind.PageOrUnknown -> "Resolver paths keep media-specific work out of the plain file queue."
        else -> "Fallback is chosen before a backend owns the destination, not after partial data is committed."
    }

    private fun alternatives(
        kind: DownloadIntakeKind,
        hasProtectedContext: Boolean,
        largeDirectFile: Boolean,
    ): List<EngineEscalationAlternative> = buildList {
        if (kind == DownloadIntakeKind.AdaptiveMedia || kind == DownloadIntakeKind.PageOrUnknown || kind == DownloadIntakeKind.DirectMedia) {
            add(EngineEscalationAlternative("yt-dlp/media resolver", "Use for pages, playlists, variants, and extractor-supported sites."))
        }
        if (largeDirectFile) {
            add(EngineEscalationAlternative("aria2", "Use for large direct files that benefit from segmented retry."))
        }
        if (hasProtectedContext) {
            add(EngineEscalationAlternative("XDM Native", "Use while captured browser session context is still fresh."))
        }
        if (isEmpty()) {
            add(EngineEscalationAlternative("Automatic", "Use when the direct request has no special media or session signals."))
        }
    }

    private fun DownloadIntakeKind.safeLabel(): String = when (this) {
        DownloadIntakeKind.DirectFile -> "Direct file"
        DownloadIntakeKind.DirectMedia -> "Direct media"
        DownloadIntakeKind.AdaptiveMedia -> "HLS or DASH playlist"
        DownloadIntakeKind.Torrent -> "Torrent handoff"
        DownloadIntakeKind.PageOrUnknown -> "Page or unknown"
    }

    private fun Map<String, String>.hasHeader(name: String): Boolean = keys.any { it.equals(name, ignoreCase = true) }

    private fun String.hasCredentialBearingQuery(): Boolean {
        val query = runCatching { URI(this).rawQuery.orEmpty() }.getOrDefault(substringAfter('?', ""))
        if (query.isBlank()) return false
        return query.split('&').any { part ->
            val key = part.substringBefore('=').lowercase(Locale.US)
            CredentialQueryKeys.any { token -> key == token || key.contains(token) }
        }
    }

    private fun String.hasShortLivedPathHint(): Boolean {
        val lower = lowercase(Locale.US)
        return ShortLivedPathHints.any { lower.contains(it) }
    }

    private const val LargeFileThresholdBytes = 256L * 1024L * 1024L

    private val CredentialQueryKeys = setOf(
        "token",
        "signature",
        "sig",
        "expires",
        "expiry",
        "expire",
        "policy",
        "credential",
        "access_token",
        "auth",
        "jwt",
        "key",
        "st",
    )

    private val ShortLivedPathHints = setOf(
        "/signed/",
        "/secure/",
        "/protected/",
        "/session/",
    )
}
