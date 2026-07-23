package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaVariant
import java.util.Locale

/**
 * Phase 31 Session Privacy + Cleanup Audit.
 *
 * This planner pressure-tests the whole media stack for secret persistence and orphaned transient
 * handoffs. It emits redacted findings only: the raw secret never appears in summaries, UI cards,
 * sidecars, diagnostics, or final gate logs.
 */
enum class MediaPrivacySurface(val label: String) {
    BrowserProfile("browser profile"),
    ResolverSessionHandoff("resolver session handoff"),
    QueueSpec("queue spec"),
    RoomMetadata("Room metadata"),
    Sidecar("sidecar"),
    Logs("logs"),
    Notification("notification"),
    TempFiles("temp files"),
    TermuxCommandPreview("Termux command preview"),
}

enum class MediaPrivacySeverity(val label: String) {
    Pass("Pass"),
    Review("Review"),
    Blocker("Blocker"),
}

enum class MediaCleanupState(val label: String) {
    NotRequired("Not required"),
    Armed("Armed"),
    Due("Due"),
    Verified("Verified"),
    Failed("Failed"),
}

data class MediaPrivacyAuditFinding(
    val surface: MediaPrivacySurface,
    val severity: MediaPrivacySeverity,
    val cleanupState: MediaCleanupState,
    val captureId: String?,
    val redactedPreview: String,
    val remediation: String,
) {
    val summary: String get() = listOf(surface.label, severity.label, cleanupState.label, remediation, redactedPreview).joinToString(" • ")
}

data class MediaSessionPrivacyAuditDashboard(
    val findings: List<MediaPrivacyAuditFinding>,
    val blockerCount: Int,
    val reviewCount: Int,
    val cleanupDueCount: Int,
    val cleanupVerifiedCount: Int,
    val scannedSurfaceCount: Int,
    val durableSecretSafe: Boolean,
    val transientCleanupHealthy: Boolean,
) {
    val empty: Boolean get() = findings.isEmpty()
    val summary: String get() = listOf(
        "blockers=$blockerCount",
        "review=$reviewCount",
        "cleanupDue=$cleanupDueCount",
        "cleanupVerified=$cleanupVerifiedCount",
        "surfaces=$scannedSurfaceCount",
        if (durableSecretSafe) "durable secret-safe" else "durable leak blocked",
        if (transientCleanupHealthy) "cleanup healthy" else "cleanup review",
    ).joinToString(" • ")
}

class MediaSessionPrivacyAuditPlanner {
    fun audit(
        captures: List<MediaCaptureRecord>,
        variants: List<MediaVariant>,
        libraryItems: List<OfflineMediaLibraryItem>,
        executionJobs: List<MediaExecutionJob>,
        diagnostics: List<String> = emptyList(),
        cleanupLedger: Map<String, Boolean> = emptyMap(),
    ): MediaSessionPrivacyAuditDashboard {
        val findings = mutableListOf<MediaPrivacyAuditFinding>()
        captures.forEach { capture ->
            findings += inspectCapture(capture)
        }
        variants.forEach { variant ->
            findings += inspectVariant(variant)
        }
        libraryItems.forEach { item ->
            findings += inspectLibraryItem(item)
        }
        executionJobs.forEach { job ->
            findings += inspectExecutionJob(job)
        }
        diagnostics.forEachIndexed { index, value ->
            findings += inspectDiagnostic(index, value)
        }
        cleanupLedger.forEach { entry ->
            val captureId = entry.key
            val verified = entry.value
            findings += MediaPrivacyAuditFinding(
                surface = MediaPrivacySurface.TempFiles,
                severity = if (verified) MediaPrivacySeverity.Pass else MediaPrivacySeverity.Review,
                cleanupState = if (verified) MediaCleanupState.Verified else MediaCleanupState.Due,
                captureId = captureId,
                redactedPreview = "transient handoff cleanup ${if (verified) "verified" else "pending"}",
                remediation = if (verified) "cleanup ledger verified" else "delete temp cookie/input/session files after terminal state",
            )
        }
        val durableFindings = findings.filter { it.surface == MediaPrivacySurface.QueueSpec || it.surface == MediaPrivacySurface.RoomMetadata || it.surface == MediaPrivacySurface.Sidecar || it.surface == MediaPrivacySurface.Logs || it.surface == MediaPrivacySurface.Notification }
        return MediaSessionPrivacyAuditDashboard(
            findings = findings.sortedWith(compareByDescending<MediaPrivacyAuditFinding> { severityRank(it.severity) }.thenBy { it.surface.label }.thenBy { it.captureId.orEmpty() }),
            blockerCount = findings.count { it.severity == MediaPrivacySeverity.Blocker },
            reviewCount = findings.count { it.severity == MediaPrivacySeverity.Review },
            cleanupDueCount = findings.count { it.cleanupState == MediaCleanupState.Due || it.cleanupState == MediaCleanupState.Failed },
            cleanupVerifiedCount = findings.count { it.cleanupState == MediaCleanupState.Verified },
            scannedSurfaceCount = MediaPrivacySurface.entries.size,
            durableSecretSafe = durableFindings.none { it.severity == MediaPrivacySeverity.Blocker },
            transientCleanupHealthy = findings.none { it.surface == MediaPrivacySurface.TempFiles && (it.cleanupState == MediaCleanupState.Due || it.cleanupState == MediaCleanupState.Failed) },
        )
    }

    private fun inspectCapture(capture: MediaCaptureRecord): List<MediaPrivacyAuditFinding> {
        val rows = mutableListOf<MediaPrivacyAuditFinding>()
        rows += findingForText(MediaPrivacySurface.BrowserProfile, capture.id, capture.pageUrl.orEmpty(), "keep private profile/page context process-local")
        rows += findingForText(MediaPrivacySurface.ResolverSessionHandoff, capture.id, capture.sourceUrl, "use short-lived resolver handoff and redact diagnostics")
        val statusPreview = listOf(capture.status.name, capture.resolutionStatus.name, capture.kind.name).joinToString("/")
        rows += MediaPrivacyAuditFinding(MediaPrivacySurface.RoomMetadata, MediaPrivacySeverity.Pass, MediaCleanupState.NotRequired, capture.id, statusPreview, "stable capture metadata scanned")
        return rows
    }

    private fun inspectVariant(variant: MediaVariant): List<MediaPrivacyAuditFinding> = listOf(
        findingForText(MediaPrivacySurface.ResolverSessionHandoff, variant.captureId, variant.url, "refresh variant URL before enqueue if tokenized or expired"),
        MediaPrivacyAuditFinding(MediaPrivacySurface.RoomMetadata, MediaPrivacySeverity.Pass, MediaCleanupState.NotRequired, variant.captureId, "variant=${variant.kind.name}/${variant.mimeType.orEmpty()}", "variant metadata scanned"),
    )

    private fun inspectLibraryItem(item: OfflineMediaLibraryItem): List<MediaPrivacyAuditFinding> = listOf(
        findingForText(MediaPrivacySurface.Sidecar, item.captureId, item.sidecar.toRedactedJson(), "sidecar must remain redacted metadata only"),
        findingForText(MediaPrivacySurface.Notification, item.captureId, item.detail, "notification/detail text must remain redacted"),
        MediaPrivacyAuditFinding(MediaPrivacySurface.RoomMetadata, MediaPrivacySeverity.Pass, MediaCleanupState.NotRequired, item.captureId, "library=${item.state?.name.orEmpty()}/${item.fileName.take(80)}", "offline library row scanned"),
    )

    private fun inspectExecutionJob(job: MediaExecutionJob): List<MediaPrivacyAuditFinding> = listOf(
        findingForText(MediaPrivacySurface.QueueSpec, job.captureId, job.detail, "queue diagnostics must not contain cookies, headers, tokens, or raw command secrets"),
        MediaPrivacyAuditFinding(
            surface = MediaPrivacySurface.TempFiles,
            severity = if (job.stage == MediaExecutionStage.Completed || job.stage == MediaExecutionStage.Failed || job.stage == MediaExecutionStage.Blocked) MediaPrivacySeverity.Review else MediaPrivacySeverity.Pass,
            cleanupState = if (job.stage == MediaExecutionStage.Completed || job.stage == MediaExecutionStage.Failed || job.stage == MediaExecutionStage.Blocked) MediaCleanupState.Armed else MediaCleanupState.NotRequired,
            captureId = job.captureId,
            redactedPreview = job.stage.label,
            remediation = if (job.stage == MediaExecutionStage.Completed || job.stage == MediaExecutionStage.Failed || job.stage == MediaExecutionStage.Blocked) "verify cleanup after terminal state" else "no transient cleanup required",
        ),
    )

    private fun inspectDiagnostic(index: Int, value: String): List<MediaPrivacyAuditFinding> = listOf(
        findingForText(MediaPrivacySurface.Logs, "diagnostic-$index", value, "logs and dashboards must use central redaction"),
        findingForText(MediaPrivacySurface.TermuxCommandPreview, "diagnostic-$index", value, "typed Termux previews must not expose raw cookies or bearer tokens"),
    )

    private fun findingForText(
        surface: MediaPrivacySurface,
        captureId: String?,
        value: String,
        remediation: String,
    ): MediaPrivacyAuditFinding {
        val containsSecret = containsKnownSecret(value)
        val redacted = redactKnownSecrets(value).ifBlank { "empty" }.take(180)
        val severity = if (containsSecret && isDurableSurface(surface)) MediaPrivacySeverity.Blocker else if (containsSecret) MediaPrivacySeverity.Review else MediaPrivacySeverity.Pass
        val cleanup = if (containsSecret && !isDurableSurface(surface)) MediaCleanupState.Armed else MediaCleanupState.NotRequired
        return MediaPrivacyAuditFinding(
            surface = surface,
            severity = severity,
            cleanupState = cleanup,
            captureId = captureId,
            redactedPreview = redacted,
            remediation = if (containsSecret) remediation else "no secret marker found",
        )
    }

    private fun isDurableSurface(surface: MediaPrivacySurface): Boolean = when (surface) {
        MediaPrivacySurface.QueueSpec,
        MediaPrivacySurface.RoomMetadata,
        MediaPrivacySurface.Sidecar,
        MediaPrivacySurface.Logs,
        MediaPrivacySurface.Notification -> true
        MediaPrivacySurface.BrowserProfile,
        MediaPrivacySurface.ResolverSessionHandoff,
        MediaPrivacySurface.TempFiles,
        MediaPrivacySurface.TermuxCommandPreview -> false
    }

    private fun severityRank(severity: MediaPrivacySeverity): Int = when (severity) {
        MediaPrivacySeverity.Blocker -> 3
        MediaPrivacySeverity.Review -> 2
        MediaPrivacySeverity.Pass -> 1
    }

    private fun containsKnownSecret(text: String): Boolean = secretPatterns.any { it.containsMatchIn(text) }

    private fun redactKnownSecrets(text: String): String {
        var redacted = text
        secretPatterns.forEach { pattern -> redacted = pattern.replace(redacted, "<redacted>") }
        return redacted
    }

    private companion object {
        val secretPatterns = listOf(
            Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=]\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(token|session|sid|sig|signature|auth|key)=((?!<redacted>)[^\s&#;]+)"""),
            Regex("secret-[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
