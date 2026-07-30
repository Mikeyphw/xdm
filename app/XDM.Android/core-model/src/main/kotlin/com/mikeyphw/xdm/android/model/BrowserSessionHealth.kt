package com.mikeyphw.xdm.android.model

import java.net.URI
import java.util.Locale

/** Review-time summary of browser context captured with an external handoff.
 *
 * The report deliberately exposes only yes/no labels and safe host context. Raw Cookie,
 * Authorization, bearer, token, signature, and URL values must remain transient and redacted. */
data class BrowserSessionHealthReport(
    val sourceSiteLabel: String,
    val contextSummary: String,
    val browserContextLabel: String,
    val protectedRequestLabel: String,
    val pageContextLabel: String,
    val browserIdentityLabel: String,
    val expiryRiskLabel: String,
    val suggestedMethodLabel: String,
    val primaryActionLabel: String,
    val guidance: String,
    val signals: List<BrowserSessionHealthSignal>,
) {
    val hasBrowserContext: Boolean get() = browserContextLabel == "Captured"
    val needsRefresh: Boolean get() = primaryActionLabel == "Refresh from browser"
}

data class BrowserSessionHealthSignal(
    val label: String,
    val value: String,
    val guidance: String,
)

object BrowserSessionHealthPlanner {
    fun evaluate(
        draft: DownloadIntakeDraft?,
        recommendation: BackendRecommendation? = null,
    ): BrowserSessionHealthReport? {
        if (draft == null) return null
        val headers = draft.requestHeaders
        val hasCookie = headers.hasHeader("cookie")
        val hasAuthorization = headers.hasHeader("authorization")
        val hasReferer = headers.hasHeader("referer") || ExternalUrlPolicy.normalizedUrl(draft.pageUrl) != null
        val hasUserAgent = headers.hasHeader("user-agent")
        val hasBrowserContext = hasCookie || hasAuthorization || hasReferer || hasUserAgent
        val protectedContext = hasCookie || hasAuthorization
        val expiringUrl = draft.url.hasCredentialBearingQuery() || draft.url.hasShortLivedPathHint()
        val site = draft.host?.takeIf { it.isNotBlank() } ?: "source site"
        val kindNeedsBrowserRefresh = draft.kind == DownloadIntakeKind.PageOrUnknown || draft.kind == DownloadIntakeKind.AdaptiveMedia || draft.kind == DownloadIntakeKind.DirectMedia
        val expiryRisk = when {
            expiringUrl || protectedContext -> "High"
            kindNeedsBrowserRefresh && (!hasReferer || !hasUserAgent) -> "Medium"
            else -> "Low"
        }
        val suggested = when {
            draft.kind == DownloadIntakeKind.AdaptiveMedia -> "Media resolver or yt-dlp"
            draft.kind == DownloadIntakeKind.PageOrUnknown -> "Media resolver first"
            recommendation?.backend == BackendType.Aria2 -> "aria2"
            recommendation?.backend == BackendType.Native -> "XDM Native"
            else -> "Automatic"
        }
        val primaryAction = when {
            protectedContext && (!hasReferer || !hasUserAgent) -> "Refresh from browser"
            expiryRisk == "High" -> "Use captured session"
            draft.canInspectAsMedia -> "Inspect media first"
            else -> "Add reviewed request"
        }
        val guidance = when {
            protectedContext && (!hasReferer || !hasUserAgent) -> "The site looks signed in, but the handoff is missing some browser context. Refreshing from the browser is safest before retrying."
            expiryRisk == "High" -> "This link may depend on temporary browser access. Queue it soon, and recapture if the server asks for sign-in again."
            draft.kind == DownloadIntakeKind.PageOrUnknown -> "This looks like a page or resolver handoff. Inspect media before creating a direct transfer."
            else -> "The request has enough visible context for normal review. XDM still keeps private session details backstage."
        }
        return BrowserSessionHealthReport(
            sourceSiteLabel = site,
            contextSummary = if (hasBrowserContext) "Browser context captured" else "No browser context captured",
            browserContextLabel = if (hasBrowserContext) "Captured" else "Not captured",
            protectedRequestLabel = if (protectedContext) "Detected" else "Not detected",
            pageContextLabel = if (hasReferer) "Available" else "Missing",
            browserIdentityLabel = if (hasUserAgent) "Available" else "Missing",
            expiryRiskLabel = expiryRisk,
            suggestedMethodLabel = suggested,
            primaryActionLabel = primaryAction,
            guidance = guidance,
            signals = listOf(
                BrowserSessionHealthSignal(
                    label = "Source site",
                    value = site,
                    guidance = "Only the site name is shown here, not the full link.",
                ),
                BrowserSessionHealthSignal(
                    label = "Protected request",
                    value = if (protectedContext) "Detected" else "Not detected",
                    guidance = "Private sign-in values stay hidden and process-local.",
                ),
                BrowserSessionHealthSignal(
                    label = "Page context",
                    value = if (hasReferer) "Available" else "Missing",
                    guidance = "Some sites reject downloads without the page that opened them.",
                ),
                BrowserSessionHealthSignal(
                    label = "Browser identity",
                    value = if (hasUserAgent) "Available" else "Missing",
                    guidance = "A browser identity helps servers treat the request like the original browser tab.",
                ),
                BrowserSessionHealthSignal(
                    label = "Link expiry risk",
                    value = expiryRisk,
                    guidance = "High risk means the link may expire or require a fresh browser capture.",
                ),
            ),
        )
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
