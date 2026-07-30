package com.mikeyphw.xdm.android.model

import java.net.URI
import java.util.Locale

/** Human recovery guidance for a failed or recovery-required download.
 *
 * Phase57 deliberately keeps this as a pure planning layer: it does not start transfers, delete
 * files, persist headers, or expose raw links. UI surfaces choose which already-existing callbacks
 * to invoke after the user taps an action. */
enum class RuntimeFailureRecoveryCause {
    ServerAccess,
    BrowserSessionExpired,
    MediaResolverNeeded,
    StorageVisibility,
    PartialRecovery,
    BackendFallback,
    QueuePolicy,
    GenericRetry,
}

enum class RuntimeFailureRecoveryActionKind {
    RetryWithCurrentSetup,
    RetryWithCapturedSession,
    RefreshFromBrowser,
    TryYtDlp,
    TryAria2,
    TryNative,
    RecheckStorageVisibility,
    OpenRecoveryDoctor,
    CopyRedactedReport,
}

data class RuntimeFailureRecoveryAction(
    val kind: RuntimeFailureRecoveryActionKind,
    val label: String,
    val guidance: String,
    val primary: Boolean = false,
)

data class RuntimeFailureRecoveryStep(
    val label: String,
    val status: String,
    val guidance: String,
)

data class RuntimeFailureRecoveryPlan(
    val title: String,
    val sourceSiteLabel: String,
    val causeLabel: String,
    val impactLabel: String,
    val recommendedActionLabel: String,
    val guidance: String,
    val steps: List<RuntimeFailureRecoveryStep>,
    val actions: List<RuntimeFailureRecoveryAction>,
    val redactedReport: String,
)

object RuntimeFailureRecoveryPlanner {
    fun evaluate(download: Download): RuntimeFailureRecoveryPlan? {
        if (!download.needsRuntimeRecoveryCard()) return null
        val cause = classifyCause(download)
        val site = siteLabel(download.sourceUrl)
        val fileLabel = download.fileName.takeIf { it.isNotBlank() } ?: "download"
        val steps = stepsFor(download, cause)
        val actions = actionsFor(download, cause)
        val causeLabel = cause.safeLabel()
        val impact = impactFor(cause)
        val recommended = actions.firstOrNull { it.primary }?.label ?: "Review options"
        val guidance = guidanceFor(site, cause)
        val redactedError = download.errorMessage?.let(::redactFailureMessage)?.takeIf { it.isNotBlank() }
        val report = buildString {
            appendLine("XDM runtime recovery report")
            appendLine("File: $fileLabel")
            appendLine("State: ${download.state.safeLabel()}")
            appendLine("Method: ${download.backend.safeLabel()}")
            appendLine("Source site: $site")
            appendLine("Cause: $causeLabel")
            appendLine("Impact: $impact")
            appendLine("Recommended action: $recommended")
            redactedError?.let { appendLine("Last error: $it") }
            appendLine("Private values: cookies, authorization values, bearer tokens, signatures, and credential query values are redacted.")
        }.trimEnd()
        return RuntimeFailureRecoveryPlan(
            title = "Recovery options",
            sourceSiteLabel = site,
            causeLabel = causeLabel,
            impactLabel = impact,
            recommendedActionLabel = recommended,
            guidance = guidance,
            steps = steps,
            actions = actions,
            redactedReport = report,
        )
    }

    private fun Download.needsRuntimeRecoveryCard(): Boolean = when (state) {
        DownloadState.Failed,
        DownloadState.RecoveryRequired,
        -> true
        DownloadState.Completed -> destinationUri.isBlank() || errorMessage.isStorageLike()
        else -> errorMessage.isQueuePolicy()
    }

    private fun classifyCause(download: Download): RuntimeFailureRecoveryCause {
        val error = download.errorMessage.orEmpty().lowercase(Locale.US)
        val kind = DownloadIntakeClassifier.classify(download.sourceUrl, download.fileName, download.mimeType)
        return when {
            download.state == DownloadState.RecoveryRequired -> RuntimeFailureRecoveryCause.PartialRecovery
            error.isQueuePolicy() -> RuntimeFailureRecoveryCause.QueuePolicy
            error.hasAny("http 401", "http 403", "authentication required", "access was denied", "server access was denied", "forbidden", "unauthorized") ->
                RuntimeFailureRecoveryCause.ServerAccess
            download.sourceUrl.hasCredentialBearingQuery() || error.hasAny("expired", "signed", "session") ->
                RuntimeFailureRecoveryCause.BrowserSessionExpired
            kind == DownloadIntakeKind.AdaptiveMedia || kind == DownloadIntakeKind.PageOrUnknown || error.hasAny("m3u8", "dash", "playlist", "extractor", "media resolver") ->
                RuntimeFailureRecoveryCause.MediaResolverNeeded
            error.isStorageLike() || download.destinationUri.isBlank() ->
                RuntimeFailureRecoveryCause.StorageVisibility
            shouldOfferBackendFallback(download) -> RuntimeFailureRecoveryCause.BackendFallback
            else -> RuntimeFailureRecoveryCause.GenericRetry
        }
    }

    private fun stepsFor(download: Download, cause: RuntimeFailureRecoveryCause): List<RuntimeFailureRecoveryStep> = buildList {
        add(RuntimeFailureRecoveryStep("Problem", cause.safeLabel(), problemGuidance(cause)))
        add(RuntimeFailureRecoveryStep("Current method", download.backend.safeLabel(), methodGuidance(download.backend, cause)))
        add(RuntimeFailureRecoveryStep("Saved data", savedDataStatus(download), savedDataGuidance(download, cause)))
        add(RuntimeFailureRecoveryStep("Privacy", "Protected", "Recovery guidance uses the source site and redacted error summary only."))
    }

    private fun actionsFor(download: Download, cause: RuntimeFailureRecoveryCause): List<RuntimeFailureRecoveryAction> = buildList {
        when (cause) {
            RuntimeFailureRecoveryCause.ServerAccess -> {
                add(action(RuntimeFailureRecoveryActionKind.RefreshFromBrowser, "Refresh from browser", "Open the source page in the browser, then share/capture it to XDM again so fresh session context is available.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCapturedSession, "Retry with captured session", "Use this only when the browser handoff already captured valid session context."))
                add(action(RuntimeFailureRecoveryActionKind.TryYtDlp, "Try yt-dlp", "Use for watch pages, HLS/DASH, and sites where the direct file URL is not stable."))
            }
            RuntimeFailureRecoveryCause.BrowserSessionExpired -> {
                add(action(RuntimeFailureRecoveryActionKind.RefreshFromBrowser, "Refresh from browser", "Recapture the link before retrying because the saved request may be temporary.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCapturedSession, "Retry with captured session", "Retry only after confirming the browser capture is fresh."))
                if (looksMediaLike(download)) add(action(RuntimeFailureRecoveryActionKind.TryYtDlp, "Try yt-dlp", "Let the media resolver inspect variants and temporary playlists."))
            }
            RuntimeFailureRecoveryCause.MediaResolverNeeded -> {
                add(action(RuntimeFailureRecoveryActionKind.TryYtDlp, "Try yt-dlp", "Inspect the source as media before creating another plain-file attempt.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.RefreshFromBrowser, "Refresh from browser", "Recapture the page if the resolver needs a fresh session."))
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup, "Retry current request", "Use only when this is definitely a direct media file."))
            }
            RuntimeFailureRecoveryCause.StorageVisibility -> {
                add(action(RuntimeFailureRecoveryActionKind.RecheckStorageVisibility, "Re-check storage visibility", "Ask Android to surface the completed destination again before assuming the file is gone.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor, "Open Recovery Doctor", "Review destination, orphan, and finalization status safely."))
            }
            RuntimeFailureRecoveryCause.PartialRecovery -> {
                add(action(RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor, "Open Recovery Doctor", "Validate partial data, missing files, and orphaned artifacts before retrying.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup, "Retry from source", "Creates a normal retry only after recovery state is reviewed."))
                add(action(RuntimeFailureRecoveryActionKind.RecheckStorageVisibility, "Re-check storage", "Useful when the file completed but Android has not exposed it yet."))
            }
            RuntimeFailureRecoveryCause.BackendFallback -> {
                if (download.backend == BackendType.Native) {
                    add(action(RuntimeFailureRecoveryActionKind.TryAria2, "Try aria2", "Use segmented retry for large direct files without browser-session requirements.", primary = true))
                } else {
                    add(action(RuntimeFailureRecoveryActionKind.TryNative, "Try XDM Native", "Use Native when SAF, captured session, or finalization handling matters.", primary = true))
                }
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup, "Retry current method", "Retry without changing method when the failure looks temporary."))
            }
            RuntimeFailureRecoveryCause.QueuePolicy -> {
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup, "Start now", "Override the current queue hold for this item.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.CopyRedactedReport, "Copy report", "Copy a redacted summary of the queue hold."))
            }
            RuntimeFailureRecoveryCause.GenericRetry -> {
                add(action(RuntimeFailureRecoveryActionKind.RetryWithCurrentSetup, "Retry", "Retry the same request with the current method.", primary = true))
                add(action(RuntimeFailureRecoveryActionKind.RefreshFromBrowser, "Refresh from browser", "Use when the source depends on sign-in or short-lived links."))
                add(action(RuntimeFailureRecoveryActionKind.OpenRecoveryDoctor, "Open Recovery Doctor", "Use when partial data or finalization status looks suspicious."))
            }
        }
        if (none { it.kind == RuntimeFailureRecoveryActionKind.CopyRedactedReport }) {
            add(action(RuntimeFailureRecoveryActionKind.CopyRedactedReport, "Copy redacted report", "Copy safe support details without full links or private session values."))
        }
    }

    private fun action(kind: RuntimeFailureRecoveryActionKind, label: String, guidance: String, primary: Boolean = false) = RuntimeFailureRecoveryAction(
        kind = kind,
        label = label,
        guidance = guidance,
        primary = primary,
    )

    private fun impactFor(cause: RuntimeFailureRecoveryCause): String = when (cause) {
        RuntimeFailureRecoveryCause.ServerAccess -> "The source refused a direct request; blind retry is unlikely to help."
        RuntimeFailureRecoveryCause.BrowserSessionExpired -> "The saved request may be temporary or tied to an old browser session."
        RuntimeFailureRecoveryCause.MediaResolverNeeded -> "The source may be a page, playlist, or variant set instead of a plain file."
        RuntimeFailureRecoveryCause.StorageVisibility -> "The transfer may have completed, but Android has not exposed the saved file clearly."
        RuntimeFailureRecoveryCause.PartialRecovery -> "Partial data needs validation before reuse, deletion, or restart."
        RuntimeFailureRecoveryCause.BackendFallback -> "Another method may handle this request shape better."
        RuntimeFailureRecoveryCause.QueuePolicy -> "The queue is holding the item until allowed conditions are met."
        RuntimeFailureRecoveryCause.GenericRetry -> "The failure may be temporary; review before retrying."
    }

    private fun guidanceFor(site: String, cause: RuntimeFailureRecoveryCause): String = when (cause) {
        RuntimeFailureRecoveryCause.ServerAccess -> "$site asked for browser access. Refresh the browser capture, then retry with captured context or inspect with yt-dlp for media pages."
        RuntimeFailureRecoveryCause.BrowserSessionExpired -> "$site may have issued a temporary link. Recapture from the browser before creating more failed attempts."
        RuntimeFailureRecoveryCause.MediaResolverNeeded -> "Inspect media first so playlists, variants, subtitles, and extractor-supported pages stay out of the plain download lane."
        RuntimeFailureRecoveryCause.StorageVisibility -> "Check Android shared-storage visibility before treating the destination as missing."
        RuntimeFailureRecoveryCause.PartialRecovery -> "Use Recovery Doctor before changing methods; it keeps partial data decisions explicit."
        RuntimeFailureRecoveryCause.BackendFallback -> "Switch method only after reviewing saved data; fallback happens before a backend owns new destination data."
        RuntimeFailureRecoveryCause.QueuePolicy -> "Start now only when you want to override the queue hold for this item."
        RuntimeFailureRecoveryCause.GenericRetry -> "Retry is available, but refresh browser capture or Recovery Doctor may be safer if the source or partial data changed."
    }

    private fun problemGuidance(cause: RuntimeFailureRecoveryCause): String = when (cause) {
        RuntimeFailureRecoveryCause.ServerAccess -> "Usually caused by missing or expired browser session context."
        RuntimeFailureRecoveryCause.BrowserSessionExpired -> "Signed URLs and protected pages can expire between capture and transfer."
        RuntimeFailureRecoveryCause.MediaResolverNeeded -> "A media page or playlist needs resolver support before transfer."
        RuntimeFailureRecoveryCause.StorageVisibility -> "Android shared storage can need a publish or visibility check."
        RuntimeFailureRecoveryCause.PartialRecovery -> "Existing partial data may be reusable, missing, or unsafe to adopt."
        RuntimeFailureRecoveryCause.BackendFallback -> "The selected transfer method may not be the best fit."
        RuntimeFailureRecoveryCause.QueuePolicy -> "This is a policy hold, not a broken download."
        RuntimeFailureRecoveryCause.GenericRetry -> "No specific failure class was detected."
    }

    private fun methodGuidance(backend: BackendType, cause: RuntimeFailureRecoveryCause): String = when (backend) {
        BackendType.Native -> if (cause == RuntimeFailureRecoveryCause.ServerAccess) "Native can use captured session context when it is fresh." else "Native is safest for Android destination handling."
        BackendType.Aria2 -> "aria2 is useful for large direct files and segmented retry."
        BackendType.Automatic -> "Automatic chooses a method before destination ownership begins."
    }

    private fun savedDataStatus(download: Download): String = when {
        download.state == DownloadState.RecoveryRequired -> "Needs review"
        download.bytesReceived > 0L -> "Partial data present"
        download.state == DownloadState.Completed -> "Completed destination"
        else -> "No reusable partial data"
    }

    private fun savedDataGuidance(download: Download, cause: RuntimeFailureRecoveryCause): String = when {
        cause == RuntimeFailureRecoveryCause.PartialRecovery -> "Open Recovery Doctor before deleting or restarting."
        download.bytesReceived > 0L -> "Do not discard partial data until recovery or retry behavior is clear."
        download.state == DownloadState.Completed -> "Re-check visibility if the file is not visible in storage."
        else -> "A fresh attempt is safe because no partial data was recorded."
    }

    private fun RuntimeFailureRecoveryCause.safeLabel(): String = when (this) {
        RuntimeFailureRecoveryCause.ServerAccess -> "Server requires browser access"
        RuntimeFailureRecoveryCause.BrowserSessionExpired -> "Browser session may be stale"
        RuntimeFailureRecoveryCause.MediaResolverNeeded -> "Media inspection recommended"
        RuntimeFailureRecoveryCause.StorageVisibility -> "Storage visibility needs review"
        RuntimeFailureRecoveryCause.PartialRecovery -> "Recovery state needs review"
        RuntimeFailureRecoveryCause.BackendFallback -> "Try another transfer method"
        RuntimeFailureRecoveryCause.QueuePolicy -> "Queue policy is holding this item"
        RuntimeFailureRecoveryCause.GenericRetry -> "Retry needs review"
    }

    private fun DownloadState.safeLabel(): String = when (this) {
        DownloadState.Created -> "Created"
        DownloadState.Queued -> "Queued"
        DownloadState.Connecting -> "Connecting"
        DownloadState.Downloading -> "Downloading"
        DownloadState.Paused -> "Paused"
        DownloadState.WaitingForNetwork -> "Waiting for network"
        DownloadState.WaitingForPower -> "Waiting for power"
        DownloadState.Verifying -> "Verifying"
        DownloadState.Repairing -> "Repairing"
        DownloadState.Finalizing -> "Finalizing"
        DownloadState.Completed -> "Completed"
        DownloadState.Failed -> "Failed"
        DownloadState.Cancelled -> "Cancelled"
        DownloadState.RecoveryRequired -> "Recovery required"
    }

    private fun BackendType.safeLabel(): String = when (this) {
        BackendType.Automatic -> "Automatic"
        BackendType.Native -> "XDM Native"
        BackendType.Aria2 -> "aria2"
    }

    private fun shouldOfferBackendFallback(download: Download): Boolean {
        val kind = DownloadIntakeClassifier.classify(download.sourceUrl, download.fileName, download.mimeType)
        return (download.backend == BackendType.Native && download.totalBytes != null && download.totalBytes >= LargeDirectFileBytes && kind == DownloadIntakeKind.DirectFile) ||
            (download.backend == BackendType.Aria2 && (download.destinationUri.startsWith("content:") || download.destinationUri.startsWith("xdm:")))
    }

    private fun looksMediaLike(download: Download): Boolean {
        val kind = DownloadIntakeClassifier.classify(download.sourceUrl, download.fileName, download.mimeType)
        return kind == DownloadIntakeKind.AdaptiveMedia || kind == DownloadIntakeKind.DirectMedia || kind == DownloadIntakeKind.PageOrUnknown
    }

    private fun String?.isQueuePolicy(): Boolean = this?.startsWith("Queue policy:") == true

    private fun String?.isStorageLike(): Boolean {
        val lower = this?.lowercase(Locale.US).orEmpty()
        return lower.hasAny("storage", "destination", "finalization", "visible", "mediastore", "saved file", "android blocked access")
    }

    private fun String.hasAny(vararg tokens: String): Boolean = tokens.any { contains(it) }

    private fun String.hasCredentialBearingQuery(): Boolean {
        val query = runCatching { URI(this).rawQuery.orEmpty() }.getOrDefault(substringAfter('?', ""))
        if (query.isBlank()) return false
        return query.split('&').any { part ->
            val key = part.substringBefore('=').lowercase(Locale.US)
            CredentialQueryKeys.any { token -> key == token || key.contains(token) }
        }
    }

    private fun siteLabel(url: String): String = runCatching {
        val host = URI(url).host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        host.ifBlank { "source site" }
    }.getOrDefault("source site")

    private fun redactFailureMessage(raw: String): String {
        val withUrls = UrlPattern.replace(raw) { match -> PrivacyDiagnosticsRedactor.redactUrl(match.value) ?: "<redacted link>" }
        val withoutInlineHeaders = SensitiveHeaderFragmentPattern.replace(withUrls, "<redacted header>")
        return PrivacyDiagnosticsRedactor.redactText(withoutInlineHeaders).orEmpty()
    }

    private const val LargeDirectFileBytes = 256L * 1024L * 1024L
    private val SensitiveHeaderFragmentPattern = Regex("(?i)\\b(?:authorization|cookie|set-cookie|proxy-authorization)\\s*:\\s*[^\\n]+")
    private val UrlPattern = Regex("https?://[^\\s)]+")
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
        "session",
    )
}
