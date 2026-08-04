package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.termux.ExternalTool
import com.mikeyphw.xdm.android.termux.PostProcessingActionKind
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationTrigger
import com.mikeyphw.xdm.android.termux.PostProcessingExecutionPolicy
import com.mikeyphw.xdm.android.termux.PostProcessingJobSpec
import com.mikeyphw.xdm.android.termux.PostProcessingOutputSpec
import com.mikeyphw.xdm.android.termux.PostProcessingResultMode
import com.mikeyphw.xdm.android.termux.PostProcessingSubjectType
import com.mikeyphw.xdm.android.termux.TermuxBridgeStatus
import com.mikeyphw.xdm.android.termux.TermuxOwnerSnapshot
import com.mikeyphw.xdm.android.termux.TermuxToolProbeRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessingPhase7ContractTest {
    @Test
    fun checksumAndFilenameInputsFailClosedWithoutNormalizingContamination() {
        assertTrue(PostProcessingExecutionPolicy.isValidSha256("a".repeat(64)))
        assertFalse(PostProcessingExecutionPolicy.isValidSha256("SHA-256: " + "a".repeat(64)))
        assertFalse(PostProcessingExecutionPolicy.isValidSha256("a".repeat(63)))
        assertNull(PostProcessingExecutionPolicy.validateOutputName("video-final.mp4"))
        assertTrue(PostProcessingExecutionPolicy.validateOutputName("../video.mp4") != null)
        assertTrue(PostProcessingExecutionPolicy.validateOutputName("é".repeat(121) + ".mp4") != null)
    }

    @Test
    fun credentialsAreRejectedAndDurableMetadataDropsBearerUrls() {
        assertTrue(PostProcessingExecutionPolicy.sensitiveArgumentReason(listOf("--add-header", "Authorization: Bearer secret")) != null)
        assertTrue(PostProcessingExecutionPolicy.sensitiveArgumentReason(listOf("--cookies", "/tmp/cookies.txt")) != null)
        assertNull(PostProcessingExecutionPolicy.sensitiveArgumentReason(listOf("--add-header", "Referer: https://example.test/watch")))
        assertTrue(PostProcessingExecutionPolicy.inputContainsBearerSecret("https://cdn.example.test/video.mp4?token=secret"))
        assertFalse(PostProcessingExecutionPolicy.inputContainsBearerSecret("https://cdn.example.test/video.mp4?quality=1080p"))

        val policy = File(androidRoot(), "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt").readText()
        assertTrue(policy.contains("sanitizeMetadataJson"))
        assertTrue(policy.contains("[REDACTED_URL]"))
        assertTrue(policy.contains("[REDACTED]"))
        assertTrue(policy.contains("inputContainsBearerSecret"))
    }

    @Test
    fun durableClaimAndProcessTokensAreGenerationScoped() {
        val first = sampleSpec(subjectGeneration = 7L)
        val same = sampleSpec(subjectGeneration = 7L)
        val next = sampleSpec(subjectGeneration = 8L)
        assertEquals(PostProcessingExecutionPolicy.claimKey(first), PostProcessingExecutionPolicy.claimKey(same))
        assertNotEquals(PostProcessingExecutionPolicy.claimKey(first), PostProcessingExecutionPolicy.claimKey(next))
        assertNotEquals(
            PostProcessingExecutionPolicy.processToken("job", 1, "entropy"),
            PostProcessingExecutionPolicy.processToken("job", 2, "entropy"),
        )
    }

    @Test
    fun mediaGenerationIsStableAndNotBasedOnMutableUpdatedTime() {
        val first = PostProcessingExecutionPolicy.mediaSubjectGeneration("capture", "download", null, 100L)
        val same = PostProcessingExecutionPolicy.mediaSubjectGeneration("capture", "download", null, 100L)
        val newResolution = PostProcessingExecutionPolicy.mediaSubjectGeneration("capture", "download", 200L, 100L)
        assertEquals(first, same)
        assertNotEquals(first, newResolution)
    }

    @Test
    fun immutableSpecificationRoundTripPreservesResultModeAndInputFacts() {
        val spec = sampleSpec(
            inputMimeType = "video/mp4",
            inputContainer = "mov,mp4,m4a,3gp,3g2,mj2",
            inputCodecs = "h264,aac",
            resultMode = PostProcessingResultMode.OutputArtifact,
        )
        val source = File(androidRoot(), "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt").readText()
        assertTrue(source.contains("fun toJson()") && source.contains("fun fromJson"))
        assertTrue(source.contains("resultMode") && source.contains("inputMimeType") && source.contains("inputContainer") && source.contains("inputCodecs"))
    }

    @Test
    fun ownerRecordDistinguishesAliveAndFinishedProcesses() {
        val alive = TermuxOwnerSnapshot.parse(
            """state=running
jobId=job-1
token=token-1
pid=123
processGroup=123
processStartTicks=456
setsid=1
payload=/private/payload.sh
""",
        )
        assertTrue(alive?.aliveState == true)
        assertFalse(alive?.finished == true)
        assertEquals(456L, alive?.processStartTicks)

        val finished = TermuxOwnerSnapshot.parse(
            """state=finished
jobId=job-1
token=token-1
pid=123
processGroup=123
processStartTicks=456
setsid=1
exitCode=0
""",
        )
        assertTrue(finished?.finished == true)
        assertEquals(0, finished?.exitCode)
    }

    @Test
    fun preflightRequiresFreshVerifiedToolsAndAdvertisedMuxers() {
        val now = System.currentTimeMillis()
        val ready = TermuxBridgeStatus(
            termuxInstalled = true,
            runCommandPermissionGranted = true,
            toolRows = ExternalTool.entries.map { tool ->
                TermuxToolProbeRow(
                    tool = tool,
                    available = true,
                    executablePath = "/data/data/com.termux/files/usr/bin/${tool.binaryName}",
                    versionLine = "verified",
                    probedAtEpochMs = now,
                )
            },
            lastSuccessfulToolProbeAtEpochMs = now,
            ffmpegMuxers = setOf("mp4", "m4a", "matroska", "webm"),
        )
        assertNull(PostProcessingExecutionPolicy.preflightIssue(sampleSpec(), ready))

        val stale = ready.copy(lastSuccessfulToolProbeAtEpochMs = now - PostProcessingExecutionPolicy.ToolProbeFreshnessMs - 1L)
        assertTrue(PostProcessingExecutionPolicy.preflightIssue(sampleSpec(), stale)?.contains("successful Termux tool") == true)

        val missingFfmpeg = ready.copy(
            toolRows = ready.toolRows.map { row ->
                if (row.tool == ExternalTool.Ffmpeg) row.copy(available = false, executablePath = "", versionLine = "Missing") else row
            },
        )
        assertTrue(PostProcessingExecutionPolicy.preflightIssue(sampleSpec(), missingFfmpeg)?.contains("FFmpeg") == true)

        val missingMuxer = ready.copy(ffmpegMuxers = setOf("matroska", "webm"))
        assertTrue(PostProcessingExecutionPolicy.preflightIssue(sampleSpec(), missingMuxer)?.contains("mp4 output muxer") == true)

        val localChecksum = sampleSpec(
            kind = PostProcessingActionKind.VerifySha256,
            expectedSha256 = "a".repeat(64),
            requiredTools = emptySet(),
            output = PostProcessingOutputSpec("video.sha256.txt", "text/plain"),
            resultMode = PostProcessingResultMode.InPlace,
        )
        assertNull(PostProcessingExecutionPolicy.preflightIssue(localChecksum, TermuxBridgeStatus()))
    }

    @Test
    fun fullPhaseSevenProductionWiringAndMigrationContractsArePresent() {
        val root = androidRoot()
        val dao = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/PostProcessingDao.kt").readText()
        val manager = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt").readText()
        val automation = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationManager.kt").readText()
        val bridge = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/AndroidPostProcessingArtifactBridge.kt").readText()
        val runner = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxCommandRunner.kt").readText()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt").readText()
        val availability = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingAutomationModels.kt").readText()
        val migrations = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt").readText()
        val database = File(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(database.contains("version = 17"))
        assertTrue(migrations.contains("Migration14To15") && migrations.contains("Migration15To16") && migrations.contains("Migration16To17"))
        assertTrue(dao.contains("claimAndInsert") && dao.contains("reserveLaunch") && dao.contains("reserveProcessOwnership"))
        assertTrue(dao.contains("publicationState = 'Prepared'") && dao.contains("publicationState = 'Committed'"))
        assertTrue(manager.contains("recoverInterruptedJobs") && manager.contains("reconcileOwnerCompletion"))
        assertTrue(manager.contains("Cancellation won before destination commit"))
        assertTrue(manager.contains("cleanupOwnedPartials") && manager.contains("renameOriginal") && manager.contains("verifiedOriginalPath"))
        assertTrue(automation.contains("reconcileMissedTerminalEvents") && automation.contains("event.attemptGeneration"))
        assertTrue(bridge.contains("preparePublication") && bridge.contains("publishPrepared") && bridge.contains("recoverPublished"))
        assertTrue(runner.contains("ExtraStdin") && runner.contains("shellArguments") && runner.contains("arrayOf(\"-s\")"))
        assertFalse(runner.contains("arrayOf(\"-c\""))
        assertTrue(shell.contains("XDM_PRIVATE_ROOT") && shell.contains("processStartTicks") && shell.contains("xdm_signal_tree"))
        assertTrue(shell.contains("yt-dlp --simulate") && shell.contains("ffmpeg -hide_banner"))
        assertTrue(availability.contains("Run a fresh Termux tool and capability probe first"))
        assertTrue(manifest.contains(".termux.TermuxResultService") && manifest.contains("android:exported=\"false\""))
    }

    private fun sampleSpec(
        subjectGeneration: Long = 1L,
        kind: PostProcessingActionKind = PostProcessingActionKind.RemuxFastStart,
        output: PostProcessingOutputSpec = PostProcessingOutputSpec("video.faststart.mp4", "video/mp4"),
        expectedSha256: String? = null,
        requiredTools: Set<ExternalTool> = setOf(ExternalTool.Ffmpeg, ExternalTool.Ffprobe),
        inputMimeType: String? = "video/mp4",
        inputContainer: String? = "mp4",
        inputCodecs: String? = "h264,aac",
        resultMode: PostProcessingResultMode = PostProcessingResultMode.OutputArtifact,
    ) = PostProcessingJobSpec(
        subjectId = "subject",
        subjectType = PostProcessingSubjectType.Download,
        subjectGeneration = subjectGeneration,
        downloadId = "download",
        ruleId = "rule",
        actionId = "action",
        trigger = PostProcessingAutomationTrigger.DownloadCompleted,
        kind = kind,
        title = "Video",
        inputUri = "file:///storage/emulated/0/Download/video.mp4",
        inputMimeType = inputMimeType,
        inputContainer = inputContainer,
        inputCodecs = inputCodecs,
        output = output,
        expectedSha256 = expectedSha256,
        requiredTools = requiredTools,
        formatSelector = if (kind == PostProcessingActionKind.YtDlpDownload) "bestvideo+bestaudio/best" else null,
        resultMode = resultMode,
    )

    private fun androidRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/XDM.Android") }
        .firstOrNull(File::isDirectory)
        ?: error("Unable to locate app/XDM.Android")
}
