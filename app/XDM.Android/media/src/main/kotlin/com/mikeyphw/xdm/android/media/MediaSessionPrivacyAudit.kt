package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaVariant
import java.io.File
import java.util.Locale

/**
 * Phase 31 Session Privacy + Cleanup Audit.
 *
 * This planner pressure-tests the whole media stack for secret persistence and orphaned transient
 * handoffs. It emits redacted findings only: the raw secret never appears in summaries, UI cards,
 * sidecars, diagnostics, or final gate logs.
 */
enum class MediaPrivacySurface(val label: String) {
    ExternalPageContext("external page context"),
    ResolverSessionHandoff("resolver session handoff"),
    QueueSpec("queue spec"),
    RoomMetadata("Room metadata"),
    Sidecar("sidecar"),
    Logs("logs"),
    Notification("notification"),
    TempFiles("temp files"),
    SecureEnvelopeFile("secure request envelope files"),
    BrowserImportJournal("browser capture import journal"),
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
        filesystemRoots: List<File> = emptyList(),
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
        filesystemRoots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.forEach { root ->
            findings += inspectFilesystemRoot(root)
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
        val durableFindings = findings.filter { isDurableSurface(it.surface) }
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
        rows += findingForText(MediaPrivacySurface.ExternalPageContext, capture.id, capture.pageUrl.orEmpty(), "keep external page context transient and secret-safe")
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

    /** Bounded inspection of real app-private media/browser persistence surfaces. */
    private fun inspectFilesystemRoot(root: File): List<MediaPrivacyAuditFinding> {
        val surface = when (root.name) {
            "secure-request-envelopes-v1" -> MediaPrivacySurface.SecureEnvelopeFile
            "browser-capture-import-journal" -> MediaPrivacySurface.BrowserImportJournal
            else -> MediaPrivacySurface.Sidecar
        }
        if (!root.exists()) return listOf(
            MediaPrivacyAuditFinding(
                surface = surface,
                severity = MediaPrivacySeverity.Pass,
                cleanupState = MediaCleanupState.NotRequired,
                captureId = null,
                redactedPreview = "${root.name}: surface absent",
                remediation = "filesystem surface absent; nothing persisted to inspect",
            ),
        )
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        val findings = mutableListOf<MediaPrivacyAuditFinding>()
        var scanned = 0
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_FILESYSTEM_DEPTH || scanned >= MAX_FILESYSTEM_FILES) return
            val children = runCatching { dir.listFiles()?.sortedBy { it.name }.orEmpty() }.getOrDefault(emptyList())
            children.forEach { child ->
                if (scanned >= MAX_FILESYSTEM_FILES) return
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (!canonical.path.startsWith(rootCanonical.path + File.separator)) return@forEach
                if (canonical.isDirectory) {
                    walk(canonical, depth + 1)
                } else if (canonical.isFile && shouldInspectFile(canonical)) {
                    scanned += 1
                    val text = runCatching { readBoundedText(canonical) }.getOrElse { "<unreadable>" }
                    val relative = runCatching { canonical.relativeTo(rootCanonical).path }.getOrDefault(canonical.name)
                    findings += findingForText(
                        surface,
                        "fs-${root.name}-$scanned",
                        "file=${redactKnownSecrets(relative)}\n$text",
                        "remove raw credentials or signed-request material from durable app-private media/browser files",
                    )
                }
            }
        }
        if (rootCanonical.isFile && shouldInspectFile(rootCanonical)) {
            val text = runCatching { readBoundedText(rootCanonical) }.getOrElse { "<unreadable>" }
            findings += findingForText(surface, "fs-${root.name}", text, "remove raw credentials from durable app-private media/browser files")
        } else if (rootCanonical.isDirectory) {
            walk(rootCanonical, 0)
        }
        if (findings.isEmpty()) findings += MediaPrivacyAuditFinding(
            surface, MediaPrivacySeverity.Pass, MediaCleanupState.NotRequired, null,
            "${root.name}: no inspectable durable files", "filesystem surface scanned",
        )
        return findings
    }

    private fun readBoundedText(file: File): String = file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8 * 1024)
        val output = StringBuilder()
        var remaining = MAX_FILESYSTEM_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            output.append(String(buffer, 0, read, Charsets.UTF_8))
            remaining -= read
        }
        output.toString()
    }

    private fun shouldInspectFile(file: File): Boolean {
        if (file.length() > MAX_FILESYSTEM_FILE_SIZE) return false
        val name = file.name.lowercase(Locale.ROOT)
        val durableName = when {
            name.endsWith(".bak") -> name.removeSuffix(".bak")
            name.endsWith(".new") -> name.removeSuffix(".new")
            name.endsWith(".tmp") -> name.removeSuffix(".tmp")
            ".tmp-" in name -> name.substringBeforeLast(".tmp-")
            else -> name
        }
        return durableName.endsWith(".json") || durableName.endsWith(".properties") || durableName.endsWith(".xdm-secure") ||
            durableName.endsWith(".txt") || durableName.endsWith(".log") || durableName.endsWith(".sidecar") || durableName.endsWith(".journal")
    }

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
        MediaPrivacySurface.Notification,
        MediaPrivacySurface.SecureEnvelopeFile,
        MediaPrivacySurface.BrowserImportJournal -> true
        MediaPrivacySurface.ExternalPageContext,
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
        const val MAX_FILESYSTEM_FILES = 256
        const val MAX_FILESYSTEM_DEPTH = 5
        const val MAX_FILESYSTEM_BYTES = 128 * 1024
        const val MAX_FILESYSTEM_FILE_SIZE = 256L * 1024L
        val secretPatterns = listOf(
            Regex("""(?:Authorization|Proxy-Authorization)\s*[:=]\s*(?:Bearer|Basic)\s+(?!<redacted>)[A-Za-z0-9._~+/=-]{8,}""", RegexOption.IGNORE_CASE),
            Regex("""Bearer\s+(?!<redacted(?:-[A-Za-z]+)?>)(?:secret-[A-Za-z0-9._-]+|[A-Za-z0-9._~+/=-]{16,})""", RegexOption.IGNORE_CASE),
            Regex("""Cookie\s*[:=](?!\s*<redacted(?:-[A-Za-z]+)?>)\s*[^\n;]+""", RegexOption.IGNORE_CASE),
            Regex("""(?i)(?<![-A-Za-z])(token|access_token|refresh_token|session|sid|sig|signature|x-amz-signature|x-goog-signature|auth|api_key|apikey|key|password|passwd)=((?!<redacted>|referer=|none\b|available\b|redacted\b)[^\s&#;]+)"""),
            Regex("""https?://[^/@\s:]+:[^/@\s]+@""", RegexOption.IGNORE_CASE),
            Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"""),
            Regex("\\b(?:super-)?secret-(?!(?:safe|bearing|free)\\b)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE),
        )
    }
}
