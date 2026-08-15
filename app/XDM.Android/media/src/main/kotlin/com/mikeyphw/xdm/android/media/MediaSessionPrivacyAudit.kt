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
    val scannedFilesystemRootCount: Int = 0,
    val scannedFilesystemFileCount: Int = 0,
    val filesystemCoverageIssueCount: Int = 0,
    val filesystemCoverageComplete: Boolean = false,
) {
    val empty: Boolean get() = findings.isEmpty()
    val summary: String get() = listOf(
        "blockers=$blockerCount",
        "review=$reviewCount",
        "cleanupDue=$cleanupDueCount",
        "cleanupVerified=$cleanupVerifiedCount",
        "surfaces=$scannedSurfaceCount",
        "filesystemRoots=$scannedFilesystemRootCount",
        "filesystemFiles=$scannedFilesystemFileCount",
        "filesystemCoverageIssues=$filesystemCoverageIssueCount",
        if (filesystemCoverageComplete) "filesystem covered" else "filesystem coverage incomplete",
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
        val uniqueFilesystemRoots = filesystemRoots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val filesystemAudits = uniqueFilesystemRoots.map(::inspectFilesystemRoot)
        filesystemAudits.forEach { audit -> findings += audit.findings }
        val filesystemCoverageIssueCount = filesystemAudits.sumOf { it.coverageIssueCount }
        val filesystemCoverageComplete = uniqueFilesystemRoots.isNotEmpty() &&
            filesystemAudits.size == uniqueFilesystemRoots.size &&
            filesystemAudits.all { it.coverageComplete }
        val scannedFilesystemFileCount = filesystemAudits.sumOf { it.scannedFileCount }
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
            scannedSurfaceCount = findings.map { it.surface }.distinct().size,
            durableSecretSafe = durableFindings.none { it.severity == MediaPrivacySeverity.Blocker } && (uniqueFilesystemRoots.isEmpty() || filesystemCoverageComplete),
            transientCleanupHealthy = findings.none { it.surface == MediaPrivacySurface.TempFiles && (it.cleanupState == MediaCleanupState.Due || it.cleanupState == MediaCleanupState.Failed) },
            scannedFilesystemRootCount = uniqueFilesystemRoots.size,
            scannedFilesystemFileCount = scannedFilesystemFileCount,
            filesystemCoverageIssueCount = filesystemCoverageIssueCount,
            filesystemCoverageComplete = filesystemCoverageComplete,
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
    private data class FilesystemRootAudit(
        val findings: List<MediaPrivacyAuditFinding>,
        val scannedFileCount: Int,
        val coverageIssueCount: Int,
        val coverageComplete: Boolean,
    )

    private fun inspectFilesystemRoot(root: File): FilesystemRootAudit {
        val surface = when (root.name) {
            "secure-request-envelopes-v1" -> MediaPrivacySurface.SecureEnvelopeFile
            "browser-capture-import-journal" -> MediaPrivacySurface.BrowserImportJournal
            else -> MediaPrivacySurface.Sidecar
        }
        if (!root.exists()) {
            return FilesystemRootAudit(
                findings = listOf(
                    MediaPrivacyAuditFinding(
                        surface = surface,
                        severity = MediaPrivacySeverity.Pass,
                        cleanupState = MediaCleanupState.NotRequired,
                        captureId = null,
                        redactedPreview = "${root.name}: surface absent",
                        remediation = "filesystem surface absent; nothing persisted to inspect",
                    ),
                ),
                scannedFileCount = 0,
                coverageIssueCount = 0,
                coverageComplete = true,
            )
        }

        val rootCanonical = runCatching { root.canonicalFile }.getOrNull()
            ?: return FilesystemRootAudit(
                findings = listOf(coverageFinding(surface, root.name, "root canonicalization failed")),
                scannedFileCount = 0,
                coverageIssueCount = 1,
                coverageComplete = false,
            )

        val findings = mutableListOf<MediaPrivacyAuditFinding>()
        var scanned = 0
        var coverageIssues = 0

        fun coverageIssue(label: String) {
            coverageIssues += 1
            findings += coverageFinding(surface, root.name, label)
        }

        fun inspectFile(file: File, relative: String) {
            if (!isInspectableName(file)) return
            if (scanned >= MAX_FILESYSTEM_FILES) {
                coverageIssue("file-count limit reached before ${redactKnownSecrets(relative)}")
                return
            }
            scanned += 1
            if (file.length() > MAX_FILESYSTEM_FILE_SIZE) {
                coverageIssue("${redactKnownSecrets(relative)} exceeds the complete-scan size cap; bounded prefix inspected")
            }
            val textResult = runCatching { readBoundedText(file) }
            if (textResult.isFailure) {
                coverageIssue("${redactKnownSecrets(relative)} could not be read")
                return
            }
            findings += findingForText(
                surface,
                "fs-${root.name}-$scanned",
                "file=${redactKnownSecrets(relative)}\n${textResult.getOrThrow()}",
                "remove raw credentials or signed-request material from durable app-private media/browser files",
            )
        }

        fun walk(dir: File, depth: Int) {
            val children = runCatching { dir.listFiles() }.getOrNull()
            if (children == null) {
                coverageIssue("directory ${redactKnownSecrets(dir.name)} could not be enumerated")
                return
            }
            val sortedChildren = children.sortedBy { it.name }
            for ((index, child) in sortedChildren.withIndex()) {
                if (scanned >= MAX_FILESYSTEM_FILES) {
                    if (index < sortedChildren.size) coverageIssue("file-count limit truncated remaining entries")
                    return
                }
                val canonical = runCatching { child.canonicalFile }.getOrNull()
                if (canonical == null) {
                    coverageIssue("child canonicalization failed for ${redactKnownSecrets(child.name)}")
                    continue
                }
                val inRoot = canonical.path == rootCanonical.path || canonical.path.startsWith(rootCanonical.path + File.separator)
                if (!inRoot) {
                    coverageIssue("symlink/path escaped the audited root: ${redactKnownSecrets(child.name)}")
                    continue
                }
                val relative = runCatching { canonical.relativeTo(rootCanonical).path }.getOrDefault(canonical.name)
                when {
                    canonical.isDirectory && depth >= MAX_FILESYSTEM_DEPTH -> {
                        val nested = runCatching { canonical.listFiles() }.getOrNull()
                        if (nested == null || nested.isNotEmpty()) {
                            coverageIssue("depth limit reached at ${redactKnownSecrets(relative)}")
                        }
                    }
                    canonical.isDirectory -> walk(canonical, depth + 1)
                    canonical.isFile -> inspectFile(canonical, relative)
                    else -> coverageIssue("non-regular filesystem node encountered at ${redactKnownSecrets(relative)}")
                }
            }
        }

        when {
            rootCanonical.isFile -> inspectFile(rootCanonical, rootCanonical.name)
            rootCanonical.isDirectory -> walk(rootCanonical, 0)
            else -> coverageIssue("root is neither a regular file nor a directory")
        }

        if (findings.isEmpty()) {
            findings += MediaPrivacyAuditFinding(
                surface = surface,
                severity = MediaPrivacySeverity.Pass,
                cleanupState = MediaCleanupState.NotRequired,
                captureId = null,
                redactedPreview = "${root.name}: no inspectable durable files",
                remediation = "filesystem surface scanned",
            )
        }
        return FilesystemRootAudit(
            findings = findings,
            scannedFileCount = scanned,
            coverageIssueCount = coverageIssues,
            coverageComplete = coverageIssues == 0,
        )
    }

    private fun coverageFinding(surface: MediaPrivacySurface, rootName: String, detail: String): MediaPrivacyAuditFinding =
        MediaPrivacyAuditFinding(
            surface = surface,
            severity = MediaPrivacySeverity.Review,
            cleanupState = MediaCleanupState.Failed,
            captureId = null,
            redactedPreview = "$rootName: ${redactKnownSecrets(detail)}",
            remediation = "privacy coverage is incomplete; inspect or reduce this persistence surface before release",
        )

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

    private fun isInspectableName(file: File): Boolean {
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
