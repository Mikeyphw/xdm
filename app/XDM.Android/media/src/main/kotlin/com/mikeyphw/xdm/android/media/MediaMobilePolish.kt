package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import java.util.Locale

/**
 * Phase 32 Media Mobile Polish.
 *
 * The previous phases built the media engine room. This planner turns that dense stack into a
 * phone-first dashboard plan: one sticky current-job summary, compact action lanes, collapsed
 * diagnostics, empty/offline/error states, and explicit accessibility/foldable rules. It is pure
 * Kotlin on purpose, so the Compose route can preview the layout without new navigation routes,
 * Room migrations, workers, or secret-bearing persistence.
 */
enum class MediaMobileSurfaceMode(val label: String) {
    CompactPhone("Compact phone"),
    ExpandedPhone("Expanded phone"),
    FoldableTwoPane("Foldable two-pane"),
    BrowserFocused("Browser focused"),
}

enum class MediaMobileSectionPriority(val label: String) {
    Sticky("Sticky"),
    Primary("Primary"),
    Secondary("Secondary"),
    Collapsed("Collapsed"),
    HiddenUntilNeeded("Hidden until needed"),
}

enum class MediaMobilePolishSignal(val label: String) {
    StickyCurrentJob("sticky current job"),
    CompactActionRail("compact action rail"),
    CollapsedDiagnostics("collapsed diagnostics"),
    EmptyStateReady("empty/offline/error state"),
    NoTinyScrollIslands("no tiny scroll islands"),
    AccessibilityLabels("accessibility labels"),
    TouchTargetSafe("touch-target safe"),
    FoldableReady("foldable ready"),
    SecretSafe("secret-safe"),
}

data class MediaMobileCurrentJobSummary(
    val captureId: String?,
    val title: String,
    val statusLabel: String,
    val progressLabel: String,
    val primaryActionLabel: String,
    val attentionRequired: Boolean,
    val safeDiagnostic: String,
) {
    val empty: Boolean get() = captureId == null
    val summary: String get() = listOf(
        title,
        statusLabel,
        progressLabel,
        primaryActionLabel,
        if (attentionRequired) "attention" else "steady",
    ).joinToString(" • ")
}

data class MediaMobileSection(
    val key: String,
    val title: String,
    val priority: MediaMobileSectionPriority,
    val summary: String,
    val recommendedMaxRows: Int,
    val collapsedByDefault: Boolean,
    val accessibilityLabel: String,
) {
    val compactSummary: String get() = listOf(title, priority.label, "maxRows=$recommendedMaxRows", summary).joinToString(" • ")
}

data class MediaMobileRecommendation(
    val title: String,
    val detail: String,
    val signal: MediaMobilePolishSignal,
    val blocking: Boolean,
) {
    val summary: String get() = listOf(title, signal.label, if (blocking) "blocking" else "advisory", detail).joinToString(" • ")
}

data class MediaMobilePolishDashboard(
    val mode: MediaMobileSurfaceMode,
    val currentJob: MediaMobileCurrentJobSummary,
    val sections: List<MediaMobileSection>,
    val recommendations: List<MediaMobileRecommendation>,
    val visiblePrimarySectionCount: Int,
    val collapsedDiagnosticsCount: Int,
    val attentionCount: Int,
    val emptyStateLabel: String,
    val noTinyScrollIslands: Boolean,
    val accessibilityReady: Boolean,
    val foldableReady: Boolean,
    val secretSafe: Boolean,
) {
    val empty: Boolean get() = sections.isEmpty()
    val summary: String get() = listOf(
        mode.label,
        "primary=$visiblePrimarySectionCount",
        "collapsedDiagnostics=$collapsedDiagnosticsCount",
        "attention=$attentionCount",
        emptyStateLabel,
        if (noTinyScrollIslands) "no tiny scroll islands" else "scroll review",
        if (accessibilityReady) "accessibility-ready" else "accessibility review",
        if (foldableReady) "foldable-ready" else "foldable review",
        if (secretSafe) "secret-safe" else "redaction review",
    ).joinToString(" • ")
}

class MediaMobilePolishPlanner {
    fun dashboard(
        captures: List<MediaCaptureRecord>,
        queueTelemetry: MediaQueueTelemetryDeck,
        queueActions: MediaQueueActionDashboard,
        library: OfflineLibraryV2Dashboard,
        playerReports: List<MediaPlayerDiagnosticReport>,
        captureQuality: BrowserCaptureQualityDashboard,
        privacyAudit: MediaSessionPrivacyAuditDashboard,
        compactPreferred: Boolean = true,
        browserVisible: Boolean = false,
        widthClassLabel: String = "phone",
    ): MediaMobilePolishDashboard {
        val mode = modeFor(compactPreferred, browserVisible, widthClassLabel)
        val currentJob = currentJobFor(queueTelemetry, queueActions)
        val sections = sectionsFor(
            captures = captures,
            queueTelemetry = queueTelemetry,
            queueActions = queueActions,
            library = library,
            playerReports = playerReports,
            captureQuality = captureQuality,
            privacyAudit = privacyAudit,
            mode = mode,
        )
        val recommendations = recommendationsFor(
            currentJob = currentJob,
            sections = sections,
            queueTelemetry = queueTelemetry,
            library = library,
            playerReports = playerReports,
            captureQuality = captureQuality,
            privacyAudit = privacyAudit,
            captures = captures,
        )
        val collapsed = sections.count { it.collapsedByDefault }
        val primary = sections.count { it.priority == MediaMobileSectionPriority.Sticky || it.priority == MediaMobileSectionPriority.Primary }
        val attention = queueTelemetry.needsAttentionCount + queueActions.attentionCount + privacyAudit.blockerCount + privacyAudit.reviewCount + library.failedCount + library.missingCount
        val safeText = (sections.map { it.compactSummary } + recommendations.map { it.summary } + currentJob.summary + currentJob.safeDiagnostic).joinToString("\n")
        return MediaMobilePolishDashboard(
            mode = mode,
            currentJob = currentJob,
            sections = sections,
            recommendations = recommendations,
            visiblePrimarySectionCount = primary,
            collapsedDiagnosticsCount = collapsed,
            attentionCount = attention,
            emptyStateLabel = emptyStateFor(captures, library, queueTelemetry, privacyAudit),
            noTinyScrollIslands = sections.none { it.recommendedMaxRows in 1..1 && it.priority != MediaMobileSectionPriority.Sticky },
            accessibilityReady = sections.all { it.accessibilityLabel.isNotBlank() } && recommendations.any { it.signal == MediaMobilePolishSignal.AccessibilityLabels },
            foldableReady = recommendations.any { it.signal == MediaMobilePolishSignal.FoldableReady },
            secretSafe = queueTelemetry.secretSafe && queueActions.secretSafe && library.secretSafe && captureQuality.secretSafe && privacyAudit.durableSecretSafe && !containsKnownSecret(safeText),
        )
    }

    private fun modeFor(compactPreferred: Boolean, browserVisible: Boolean, widthClassLabel: String): MediaMobileSurfaceMode {
        if (browserVisible) return MediaMobileSurfaceMode.BrowserFocused
        val normalized = widthClassLabel.lowercase(Locale.US)
        return when {
            normalized.contains("expanded") || normalized.contains("tablet") || normalized.contains("fold") -> MediaMobileSurfaceMode.FoldableTwoPane
            compactPreferred -> MediaMobileSurfaceMode.CompactPhone
            else -> MediaMobileSurfaceMode.ExpandedPhone
        }
    }

    private fun currentJobFor(
        queueTelemetry: MediaQueueTelemetryDeck,
        queueActions: MediaQueueActionDashboard,
    ): MediaMobileCurrentJobSummary {
        val row = queueTelemetry.rows.firstOrNull { it.tone == MediaQueueTelemetryTone.Active }
            ?: queueTelemetry.rows.firstOrNull { it.tone == MediaQueueTelemetryTone.Attention || it.tone == MediaQueueTelemetryTone.Blocked }
            ?: queueTelemetry.rows.firstOrNull { it.nextActionLabel == "Launch queue" }
        if (row == null) {
            return MediaMobileCurrentJobSummary(
                captureId = null,
                title = "No active media job",
                statusLabel = "Idle",
                progressLabel = "0 active",
                primaryActionLabel = if (queueActions.launchableCount > 0) "Launch ready media" else "Browse or share media",
                attentionRequired = queueActions.attentionCount > 0,
                safeDiagnostic = "Sticky current job summary is idle and secret-safe.",
            )
        }
        return MediaMobileCurrentJobSummary(
            captureId = row.captureId,
            title = row.title,
            statusLabel = row.stageLabel,
            progressLabel = row.progressLabel,
            primaryActionLabel = row.nextActionLabel,
            attentionRequired = row.tone == MediaQueueTelemetryTone.Attention || row.tone == MediaQueueTelemetryTone.Blocked || row.stalled,
            safeDiagnostic = row.safeDiagnostic,
        )
    }

    private fun sectionsFor(
        captures: List<MediaCaptureRecord>,
        queueTelemetry: MediaQueueTelemetryDeck,
        queueActions: MediaQueueActionDashboard,
        library: OfflineLibraryV2Dashboard,
        playerReports: List<MediaPlayerDiagnosticReport>,
        captureQuality: BrowserCaptureQualityDashboard,
        privacyAudit: MediaSessionPrivacyAuditDashboard,
        mode: MediaMobileSurfaceMode,
    ): List<MediaMobileSection> {
        val maxPrimaryRows = if (mode == MediaMobileSurfaceMode.FoldableTwoPane) 6 else 3
        return listOf(
            MediaMobileSection(
                key = "sticky-current-job",
                title = "Sticky current job summary",
                priority = MediaMobileSectionPriority.Sticky,
                summary = "active=${queueTelemetry.activeCount} • ready=${queueTelemetry.readyToLaunchCount} • attention=${queueTelemetry.needsAttentionCount}",
                recommendedMaxRows = 1,
                collapsedByDefault = false,
                accessibilityLabel = "Sticky current media job status and next action",
            ),
            MediaMobileSection(
                key = "primary-actions",
                title = "Primary action strip",
                priority = MediaMobileSectionPriority.Primary,
                summary = "launch=${queueActions.launchableCount} • retry=${queueActions.retryableCount} • cleanup=${queueActions.cleanupCount}",
                recommendedMaxRows = maxPrimaryRows,
                collapsedByDefault = false,
                accessibilityLabel = "Primary media queue actions with at least forty eight dp touch targets",
            ),
            MediaMobileSection(
                key = "capture-inbox",
                title = "Capture inbox shelf",
                priority = MediaMobileSectionPriority.Primary,
                summary = "captures=${captures.size} • treasure=${captureQuality.treasureCount} • refresh=${captureQuality.refreshCount}",
                recommendedMaxRows = maxPrimaryRows,
                collapsedByDefault = false,
                accessibilityLabel = "Media captures grouped by quality and source host",
            ),
            MediaMobileSection(
                key = "offline-library",
                title = "Offline library shelf",
                priority = if (library.visibleCount > 0) MediaMobileSectionPriority.Secondary else MediaMobileSectionPriority.HiddenUntilNeeded,
                summary = library.summary,
                recommendedMaxRows = if (library.visibleCount > 0) maxPrimaryRows else 0,
                collapsedByDefault = library.visibleCount == 0,
                accessibilityLabel = "Completed and failed offline media library rows",
            ),
            MediaMobileSection(
                key = "player-diagnostics",
                title = "Collapsed player diagnostics drawer",
                priority = MediaMobileSectionPriority.Collapsed,
                summary = "reports=${playerReports.size} • retry=${playerReports.count { it.retryPrepareAvailable }} • protected=${playerReports.count { it.protectedDiagnosticOnly }}",
                recommendedMaxRows = 2,
                collapsedByDefault = true,
                accessibilityLabel = "Collapsed Media3 player diagnostics and retry prepare guidance",
            ),
            MediaMobileSection(
                key = "privacy-cleanup",
                title = "Collapsed privacy and cleanup drawer",
                priority = if (privacyAudit.blockerCount > 0) MediaMobileSectionPriority.Primary else MediaMobileSectionPriority.Collapsed,
                summary = privacyAudit.summary,
                recommendedMaxRows = if (privacyAudit.blockerCount > 0) 3 else 2,
                collapsedByDefault = privacyAudit.blockerCount == 0,
                accessibilityLabel = "Privacy audit and transient cleanup findings",
            ),
        )
    }

    private fun recommendationsFor(
        currentJob: MediaMobileCurrentJobSummary,
        sections: List<MediaMobileSection>,
        queueTelemetry: MediaQueueTelemetryDeck,
        library: OfflineLibraryV2Dashboard,
        playerReports: List<MediaPlayerDiagnosticReport>,
        captureQuality: BrowserCaptureQualityDashboard,
        privacyAudit: MediaSessionPrivacyAuditDashboard,
        captures: List<MediaCaptureRecord>,
    ): List<MediaMobileRecommendation> {
        val items = mutableListOf<MediaMobileRecommendation>()
        items += MediaMobileRecommendation(
            title = "Sticky current job summary",
            detail = currentJob.summary,
            signal = MediaMobilePolishSignal.StickyCurrentJob,
            blocking = currentJob.attentionRequired,
        )
        items += MediaMobileRecommendation(
            title = "Single vertical LazyColumn",
            detail = "Keep Media cards in one list and avoid nested tiny scroll islands.",
            signal = MediaMobilePolishSignal.NoTinyScrollIslands,
            blocking = false,
        )
        items += MediaMobileRecommendation(
            title = "Compact action rail",
            detail = "Promote launch/retry/cleanup first; diagnostics stay behind expandable rows.",
            signal = MediaMobilePolishSignal.CompactActionRail,
            blocking = false,
        )
        items += MediaMobileRecommendation(
            title = "Collapsed diagnostics",
            detail = "Player, privacy, and capture-quality details are summarized until attention is needed.",
            signal = MediaMobilePolishSignal.CollapsedDiagnostics,
            blocking = privacyAudit.blockerCount > 0 || playerReports.any { it.bucket != MediaPlayerDiagnosticBucket.Ready },
        )
        items += MediaMobileRecommendation(
            title = "Empty/offline/error states",
            detail = emptyStateFor(captures, library, queueTelemetry, privacyAudit),
            signal = MediaMobilePolishSignal.EmptyStateReady,
            blocking = false,
        )
        items += MediaMobileRecommendation(
            title = "Accessibility labels",
            detail = sections.joinToString("; ") { it.accessibilityLabel }.take(220),
            signal = MediaMobilePolishSignal.AccessibilityLabels,
            blocking = sections.any { it.accessibilityLabel.isBlank() },
        )
        items += MediaMobileRecommendation(
            title = "Touch target safety",
            detail = "Primary queue buttons and chips must keep at least 48dp targets with labels, not icons alone.",
            signal = MediaMobilePolishSignal.TouchTargetSafe,
            blocking = false,
        )
        items += MediaMobileRecommendation(
            title = "Foldable behavior",
            detail = "Expanded/foldable width promotes capture queue beside library/player summaries without adding a route.",
            signal = MediaMobilePolishSignal.FoldableReady,
            blocking = false,
        )
        if (queueTelemetry.secretSafe && library.secretSafe && captureQuality.secretSafe && privacyAudit.durableSecretSafe) {
            items += MediaMobileRecommendation(
                title = "Secret-safe summaries",
                detail = "Mobile polish uses redacted summaries only; no cookie, header, credential, or tokenized URL values.",
                signal = MediaMobilePolishSignal.SecretSafe,
                blocking = false,
            )
        }
        return items
    }

    private fun emptyStateFor(
        captures: List<MediaCaptureRecord>,
        library: OfflineLibraryV2Dashboard,
        queueTelemetry: MediaQueueTelemetryDeck,
        privacyAudit: MediaSessionPrivacyAuditDashboard,
    ): String = when {
        privacyAudit.blockerCount > 0 -> "Privacy blocker: review redacted diagnostics before queueing media."
        queueTelemetry.activeCount > 0 -> "Active downloads: keep progress and pause/cancel in the sticky summary."
        captures.isEmpty() && library.visibleCount == 0 -> "Empty: browse, share, or paste a media page to start capture."
        captures.isEmpty() && library.visibleCount > 0 -> "Offline: library remains useful even when capture inbox is empty."
        queueTelemetry.needsAttentionCount > 0 -> "Needs attention: refresh metadata, choose tracks, or open Termux setup."
        else -> "Ready: queue selected media or continue browsing inside Media."
    }

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { pattern -> pattern.containsMatchIn(text) }

    private companion object {
        val secretPatterns = listOf(
            Regex("""Bearer\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})""", RegexOption.IGNORE_CASE),
            Regex("""Authorization\s*[:=](?!\s*<redacted(?:-[A-Za-z]+)?>)\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=](?!\s*<redacted(?:-[A-Za-z]+)?>)\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(?<![-A-Za-z])(token|session|sid|sig|signature|auth|key)=((?!<redacted>|referer=|none\b|available\b|redacted\b)[^\s&#;]+)"""),
            Regex("\\b(?:super-)?secret-(?!(?:safe|bearing|free)\\b)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }

}
