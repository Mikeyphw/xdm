package com.mikeyphw.xdm.android.media

import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCaptureServiceTest {
    @Test
    fun directMediaUrlCreatesMetadataRecord() {
        val service = MediaCaptureService(clock = { 42L })
        val records = service.detect("watch https://cdn.example.test/video/finale.mp4 now", pageTitle = "Finale")
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(MediaSourceKind.ProgressiveMedia, record.kind)
        assertEquals(MediaCaptureStatus.MetadataReady, record.status)
        assertEquals("video/mp4", record.mimeType)
        assertEquals("finale.mp4", record.fileName)
    }

    @Test
    fun hlsPlaylistVariantsAreParsedWithoutNetwork() {
        val service = MediaCaptureService(clock = { 42L })
        val variants = service.parseHlsPlaylist(
            captureId = "capture",
            playlistUrl = "https://media.example.test/live/master.m3u8",
            playlistText = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720
                720/prog_index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1920x1080
                1080/prog_index.m3u8
            """.trimIndent(),
        )
        assertEquals(2, variants.size)
        assertEquals(1_280_000L, variants.first().bitrateBitsPerSecond)
        assertTrue(variants.first().url.endsWith("/live/720/prog_index.m3u8"))
    }

    @Test
    fun dashManifestVariantsAreParsedWithoutNetwork() {
        val service = MediaCaptureService(clock = { 100L })
        val variants = service.parseDashManifest(
            captureId = "capture",
            manifestUrl = "https://media.example.test/movie/manifest.mpd",
            manifestText = """
                <MPD><Period><AdaptationSet>
                <Representation bandwidth="900000" width="1280" height="720" codecs="avc1.4d401f" mimeType="video/mp4"><BaseURL>video-720.mp4</BaseURL></Representation>
                <Representation bandwidth="160000" codecs="mp4a.40.2" mimeType="audio/mp4"><BaseURL>audio.m4a</BaseURL></Representation>
                </AdaptationSet></Period></MPD>
            """.trimIndent(),
        )
        assertEquals(2, variants.size)
        assertEquals("720p • 900 kbps • avc1.4d401f", variants.first().qualityLabel)
        assertTrue(variants.first().url.endsWith("/movie/video-720.mp4"))
    }

    @Test
    fun selectedVariantSurvivesRefreshRecord() {
        val service = MediaCaptureService(clock = { 200L })
        val record = service.detect("https://media.example.test/live/master.m3u8").single()
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=854x480
                480/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
                1080/index.m3u8
            """.trimIndent(),
        )
        val selected = service.selectVariant(record, variants, variants.last().id, 200L)
        val refreshed = service.refreshRecordAfterResolution(selected, variants, nowEpochMs = 200L, maxAgeMs = 1_000L)
        assertEquals(variants.last().id, refreshed.selectedVariantId)
        assertEquals(2, refreshed.variantCount)
        assertEquals(1_200L, refreshed.manifestExpiresAtEpochMs)
    }

    @Test
    fun classifierUsesMimeTypesWhenUrlDoesNotExposeExtension() {
        val classifier = MediaCandidateClassifier()
        assertEquals(
            MediaSourceKind.HlsPlaylist,
            classifier.classify(MediaRequestFacts("https://video.example.test/session/tokenized", mimeType = "application/x-mpegurl")),
        )
        assertEquals(
            MediaSourceKind.AudioStream,
            classifier.classify(MediaRequestFacts("https://video.example.test/media?id=1", mimeType = "audio/mpeg")),
        )
    }

    @Test
    fun browserMimeHintCreatesCaptureRecordWithoutExtension() {
        val service = MediaCaptureService(clock = { 300L })
        val candidate = service.candidateFor(
            url = "https://video.example.test/session/tokenized",
            pageTitle = "Session",
            pageUrl = "https://video.example.test/watch",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )
        val record = service.recordFor(requireNotNull(candidate))
        assertEquals(MediaSourceKind.HlsPlaylist, record.kind)
        assertEquals("https://video.example.test/watch", record.pageUrl)
        assertEquals("application/vnd.apple.mpegurl", record.mimeType)
    }

    @Test
    fun plannerRoutesComplexMediaToTermuxAndDirectMediaToExistingEngines() {
        val service = MediaCaptureService(clock = { 400L })
        val hls = service.detect("https://cdn.example.test/master.m3u8").single()
        val mp4 = service.detect("https://cdn.example.test/movie.mp4").single()
        val audio = service.detect("https://cdn.example.test/song.mp3").single()
        val planner = MediaDownloadPlanner()

        assertEquals(MediaDownloadStrategy.YtDlp, planner.plan(hls, emptyList()).strategy)
        assertEquals(MediaDownloadStrategy.Aria2, planner.plan(mp4, emptyList()).strategy)
        assertEquals(MediaDownloadStrategy.Native, planner.plan(audio, emptyList(), MediaDownloadIntent.AudioOnly).strategy)
    }

    @Test
    fun hlsMediaGroupsLiveAndProtectionAreClassified() {
        val service = MediaCaptureService(clock = { 500L })
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.widevine"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English CC",LANGUAGE="en",URI="subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            video/1080.m3u8
        """.trimIndent()
        val variants = service.parseHlsPlaylist("capture", "https://media.example.test/master.m3u8", playlist)
        val summary = service.inspectHlsPlaylist(playlist)

        assertEquals(3, variants.size)
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Audio })
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Subtitle })
        assertTrue(summary.isLive)
        assertTrue(summary.hasDrm)
        assertEquals("com.widevine", summary.protectionScheme)
    }

    @Test
    fun dashAdaptationSetsExposeAudioSubtitlesAndDrm() {
        val service = MediaCaptureService(clock = { 600L })
        val manifest = """
            <MPD type="dynamic"><Period>
              <AdaptationSet contentType="video" mimeType="video/mp4"><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" />
                <Representation bandwidth="1200000" width="1280" height="720" codecs="avc1.4d401f"><BaseURL>v720.m4s</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="pt">
                <Representation bandwidth="128000" codecs="mp4a.40.2"><BaseURL>a-pt.m4s</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet contentType="text" mimeType="application/ttml+xml" lang="en">
                <Representation bandwidth="256" codecs="stpp"><BaseURL>sub-en.ttml</BaseURL></Representation>
              </AdaptationSet>
            </Period></MPD>
        """.trimIndent()
        val variants = service.parseDashManifest("capture", "https://media.example.test/movie/manifest.mpd", manifest)
        val summary = service.inspectDashManifest(manifest)

        assertEquals(3, variants.size)
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Audio })
        assertEquals(1, variants.count { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Subtitle })
        assertTrue(summary.isLive)
        assertTrue(summary.hasDrm)
    }

    @Test
    fun plannerPrefersPageUrlForYtDlpAndSummarizesPlaybackLibrary() {
        val service = MediaCaptureService(clock = { 700L })
        val candidate = service.candidateFor(
            url = "https://cdn.example.test/token/master.m3u8",
            pageTitle = "Episode",
            pageUrl = "https://video.example.test/watch/episode",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )
        val record = service.recordFor(requireNotNull(candidate))
        val planner = MediaDownloadPlanner()
        val plan = planner.plan(record, candidate.variants)
        val library = planner.summarizeOfflineLibrary(listOf(record), candidate.variants)

        assertEquals("https://video.example.test/watch/episode", plan.metadataProbeUrl)
        assertTrue(plan.needsCookieContext)
        assertEquals(1, library.playableCount)
        assertTrue(library.adaptiveCount >= 1)
    }

    @Test
    fun resolverPickerGroupsCreateFormatSelectorAndPreviewMetadata() {
        val service = MediaCaptureService(clock = { 800L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/show/master.m3u8?token=secret",
            pageTitle = "Resolver episode",
            pageUrl = "https://video.example.test/watch/resolver",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English CC",LANGUAGE="en",URI="subs/en.vtt"
                #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720
                video/720.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
                video/1080.m3u8
            """.trimIndent(),
        )
        val planner = MediaDownloadPlanner()
        val video = variants.last { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Video }
        val audio = variants.first { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Audio }
        val subtitle = variants.first { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Subtitle }
        val selection = MediaTrackSelection(videoVariantId = video.id, audioVariantId = audio.id, subtitleVariantId = subtitle.id)
        val plan = planner.plan(record, variants, selection = selection)
        val groups = planner.pickerGroups(record, variants, selection)
        val preview = planner.metadataProbePreview(record, variants)

        val selector = requireNotNull(plan.ytDlpFormatSelector)
        assertEquals(3, groups.size)
        assertTrue(selector.contains("bestvideo"))
        assertTrue(selector.contains("bestaudio"))
        assertEquals("https://video.example.test/watch/resolver", plan.metadataProbeUrl)
        assertEquals("Resolver episode", preview.title)
        assertTrue(preview.formatCount >= 4)
    }

    @Test
    fun sessionHandoffDiagnosticsRedactCookiesTokensAndAuthorization() {
        val service = MediaCaptureService(clock = { 900L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/video.mp4?token=secret-token",
            pageTitle = "Secret clip",
            pageUrl = "https://video.example.test/watch?session=super-secret",
            mimeTypeHint = "video/mp4",
        )))
        val planner = MediaDownloadPlanner()
        val plan = planner.plan(
            capture = record,
            variants = emptyList(),
            sessionHeaders = listOf(
                MediaSessionHeader("Cookie", "SID=secret-cookie"),
                MediaSessionHeader("Authorization", "Bearer secret-auth"),
                MediaSessionHeader("X-CSRF-Token", "secret-csrf"),
            ),
        )
        val diagnostics = (plan.sessionHandoff.diagnosticLines() + plan.sessionHandoff.redactedSummary).joinToString("\n")

        assertFalse(diagnostics.contains("secret-cookie"))
        assertFalse(diagnostics.contains("secret-auth"))
        assertFalse(diagnostics.contains("secret-csrf"))
        assertFalse(diagnostics.contains("secret-token"))
        assertTrue(diagnostics.contains("<redacted"))
        assertTrue(plan.sessionHandoff.ytdlpArguments().contains("--cookies-from-browser=android-webview"))
    }

    @Test
    fun ytDlpMetadataProbeJsonParsesBeforeDownloadPreview() {
        val json = """
            {
              "title": "Metadata title",
              "thumbnail": "https://img.example.test/thumb.jpg",
              "duration": 125.5,
              "extractor_key": "Generic",
              "is_live": false,
              "webpage_url": "https://video.example.test/watch",
              "formats": [{"format_id":"720"},{"format_id":"audio"}]
            }
        """.trimIndent()
        val result = MediaDownloadPlanner().parseYtDlpMetadata(json)

        assertEquals("Metadata title", result.title)
        assertEquals(125_500L, result.durationMs)
        assertEquals("Generic", result.extractor)
        assertEquals(2, result.formatCount)
        assertFalse(result.isLive)
    }

    @Test
    fun mediaExecutionQueueSpecCarriesSelectedTracksAndRedactedSidecar() {
        val service = MediaCaptureService(clock = { 1_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=super-secret-token",
            pageTitle = "Selected episode",
            pageUrl = "https://watch.example.test/show?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Portuguese",LANGUAGE="pt",URI="audio/pt.m3u8"
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.vtt"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val selection = MediaTrackSelection(
            videoVariantId = variants.last().id,
            audioVariantId = variants.first { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Audio }.id,
            subtitleVariantId = variants.first { it.kind == com.mikeyphw.xdm.android.model.MediaVariantKind.Subtitle }.id,
        )
        val spec = MediaExecutionLibraryPlanner().queueSpec(record, variants, selection, "content://downloads")
        val safeText = listOf(spec.safeQueuedJobSummary, spec.safeExplanation, spec.sidecar.toRedactedJson()).joinToString("\n")

        assertTrue(spec.requiresTermuxYtDlp)
        assertEquals(3, spec.selectedTrackIds.size)
        assertTrue(spec.requestHeaders.containsKey("Referer"))
        assertFalse(safeText.contains("super-secret-token"))
        assertFalse(safeText.contains("secret-session"))
        assertTrue(safeText.contains("<redacted>"))
    }

    @Test
    fun offlineLibraryItemsExposeResumeRetryAndDirectPlaybackState() {
        val service = MediaCaptureService(clock = { 2_000L })
        val record = service.detect("https://cdn.example.test/movie.mp4", pageTitle = "Movie").single().copy(downloadId = "download-1")
        val download = com.mikeyphw.xdm.android.model.Download(
            id = "download-1",
            fileName = "movie.mp4",
            sourceUrl = record.sourceUrl,
            destinationUri = "file:///storage/emulated/0/Download/XDM",
            state = com.mikeyphw.xdm.android.model.DownloadState.Completed,
            backend = com.mikeyphw.xdm.android.model.BackendType.Aria2,
            bytesReceived = 42,
            totalBytes = 42,
            speedBytesPerSecond = 0,
            queueId = "default",
            priority = 0,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
        val items = MediaExecutionLibraryPlanner().offlineLibraryItems(listOf(record), listOf(download), emptyList())
        val jobs = MediaExecutionLibraryPlanner().executionJobs(listOf(record), listOf(download), emptyList())

        assertEquals(1, items.size)
        assertTrue(items.single().isCompleted)
        assertTrue(items.single().canPlayDirect)
        assertEquals(MediaExecutionStage.Completed, jobs.single().stage)
        assertTrue(items.single().sidecar.toRedactedJson().contains("cdn.example.test"))
    }

    @Test
    fun mediaEngineHardeningPlansUidtCookieCleanupAndLeakFreeYtDlp() {
        val service = MediaCaptureService(clock = { 3_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Hardening episode",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = record,
            variants = variants,
            selection = MediaTrackSelection(videoVariantId = variants.last().id, audioVariantId = variants.first().id),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(
                MediaSessionHeader("Cookie", "SID=secret-cookie; PREF=secret-pref"),
                MediaSessionHeader("Authorization", "Bearer secret-auth"),
            ),
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val safeText = listOf(engine.safeSummary, engine.tempCookieFile?.redactedPreview.orEmpty(), engine.typedArguments.joinToString(" ")).joinToString("\n")

        assertEquals(MediaExecutionLane.YtDlpAdaptive, engine.lane)
        assertEquals(AndroidMediaWorkKind.TermuxExternalJob, engine.backgroundPolicy.workKind)
        assertEquals("# Netscape HTTP Cookie File", engine.tempCookieFile?.netscapeHeader)
        assertEquals(2, engine.tempCookieFile?.redactedCookieLines)
        assertTrue(engine.cleanupActions.any { it.contains("delete temporary Netscape cookie file") })
        assertTrue(engine.leakReport.safe)
        assertFalse(safeText.contains("secret-cookie"))
        assertFalse(safeText.contains("secret-auth"))
        assertFalse(safeText.contains("secret-token"))
        assertFalse(safeText.contains("secret-session"))
    }

    @Test
    fun mediaEngineHardeningPlansAria2TransientInputAndUidtPolicy() {
        val service = MediaCaptureService(clock = { 4_000L })
        val record = service.detect("https://cdn.example.test/movie.mp4?token=secret-token", pageTitle = "Movie").single()
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = record,
            variants = emptyList(),
            selection = MediaTrackSelection(),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(MediaSessionHeader("Referer", "https://watch.example.test/page?session=secret-session")),
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val safeText = listOf(engine.safeSummary, engine.aria2Input?.redactedPreview.orEmpty(), engine.typedArguments.joinToString(" ")).joinToString("\n")

        assertEquals(MediaExecutionLane.Aria2Segmented, engine.lane)
        assertEquals(AndroidMediaWorkKind.UserInitiatedDataTransfer, engine.backgroundPolicy.workKind)
        assertEquals("dataSync", engine.backgroundPolicy.foregroundServiceType)
        assertTrue(engine.aria2Input?.deleteAfterTerminalState == true)
        assertTrue(engine.cleanupActions.any { it.contains("aria2 transient") })
        assertTrue(engine.leakReport.safe)
        assertFalse(safeText.contains("secret-token"))
        assertFalse(safeText.contains("secret-session"))
    }



    @Test
    fun mediaDispatchRunbookKeepsSecretsOutAndQueuesReadyAdaptiveJobs() {
        val service = MediaCaptureService(clock = { 5_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Dispatch episode",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = record,
            variants = variants,
            selection = MediaTrackSelection(videoVariantId = variants.last().id, audioVariantId = variants.first().id),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(MediaSessionHeader("Cookie", "SID=secret-cookie")),
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 5_100L)

        assertEquals(MediaDispatchReadiness.Ready, dispatch.readiness)
        assertEquals(MediaExecutionLane.YtDlpAdaptive, dispatch.lane)
        assertTrue(dispatch.queueButtonEnabled)
        assertTrue(dispatch.steps.any { it.kind == MediaDispatchStepKind.LaunchTermuxJob })
        assertTrue(dispatch.steps.any { it.terminalCleanup })
        assertTrue(dispatch.progressSignals.any { it.label.contains("extractor") })
        assertFalse(dispatch.safeDiagnostics.contains("secret-cookie"))
        assertFalse(dispatch.safeDiagnostics.contains("secret-token"))
        assertFalse(dispatch.safeDiagnostics.contains("secret-session"))
    }

    @Test
    fun mediaDispatchDashboardCountsRefreshTermuxAndBlockedPlans() {
        val service = MediaCaptureService(clock = { 6_000L })
        val direct = service.detect("https://cdn.example.test/movie.mp4", pageTitle = "Direct").single()
        val expired = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8",
            pageTitle = "Expired",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        ))).copy(manifestExpiresAtEpochMs = 5_900L, resolutionStatus = com.mikeyphw.xdm.android.model.MediaResolutionStatus.RequiresRefresh)
        val planner = MediaExecutionLibraryPlanner()
        val dispatcher = MediaExecutionDispatcher()
        val directSpec = planner.queueSpec(direct, emptyList(), MediaTrackSelection(), "content://downloads")
        val directPlan = dispatcher.dispatchPlan(directSpec, planner.enginePlan(directSpec, 35), direct, termuxReady = true, nowEpochMs = 6_100L)
        val expiredVariants = service.parseHlsPlaylist(
            captureId = expired.id,
            playlistUrl = expired.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val expiredSpec = planner.queueSpec(expired, expiredVariants, MediaTrackSelection(videoVariantId = expiredVariants.first().id), "content://downloads")
        val expiredPlan = dispatcher.dispatchPlan(expiredSpec, planner.enginePlan(expiredSpec, 35), expired, termuxReady = false, nowEpochMs = 6_100L)
        val dashboard = dispatcher.aggregate(listOf(directPlan, expiredPlan))

        assertEquals(1, dashboard.readyCount)
        assertEquals(1, dashboard.refreshCount)
        assertTrue(dashboard.secretSafe)
        assertTrue(dashboard.summary.contains("Direct native"))
        assertTrue(expiredPlan.warnings.any { it.contains("Refresh metadata") })
    }



    @Test
    fun mediaQueueTelemetryDeckShowsReadyCleanupAndSecretSafeRows() {
        val service = MediaCaptureService(clock = { 7_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Telemetry episode",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = record,
            variants = variants,
            selection = MediaTrackSelection(videoVariantId = variants.last().id, audioVariantId = variants.first().id),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(MediaSessionHeader("Cookie", "SID=secret-cookie")),
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 7_100L)
        val deck = MediaQueueTelemetryPlanner().deck(listOf(dispatch), emptyList())
        val row = deck.rows.single()

        assertEquals(1, deck.readyToLaunchCount)
        assertEquals(1, deck.cleanupArmedCount)
        assertTrue(deck.secretSafe)
        assertEquals("Launch queue", row.nextActionLabel)
        assertEquals(MediaQueueTelemetryTone.Stable, row.tone)
        assertFalse(row.safeDiagnostic.contains("secret-cookie"))
        assertFalse(row.safeDiagnostic.contains("secret-token"))
        assertFalse(row.safeDiagnostic.contains("secret-session"))
    }

    @Test
    fun mediaQueueTelemetryRedactsFailedJobDetailsAndRequestsRetry() {
        val service = MediaCaptureService(clock = { 8_000L })
        val record = service.detect("https://cdn.example.test/movie.mp4", pageTitle = "Failed movie").single()
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 8_100L)
        val failedJob = MediaExecutionJob(
            captureId = record.id,
            title = "Failed movie",
            stage = MediaExecutionStage.Failed,
            engine = "aria2",
            detail = "aria2 failed with token=secret-token and Cookie: SID=secret-cookie",
            canRetry = true,
        )
        val deck = MediaQueueTelemetryPlanner().deck(listOf(dispatch), listOf(failedJob))
        val row = deck.rows.single()

        assertEquals(0, deck.readyToLaunchCount)
        assertEquals(1, deck.needsAttentionCount)
        assertEquals(1, deck.terminalCount)
        assertEquals("Retry media", row.nextActionLabel)
        assertEquals(MediaQueueTelemetryTone.Attention, row.tone)
        assertTrue(row.stalled)
        assertFalse(row.safeDiagnostic.contains("secret-cookie"))
        assertFalse(row.safeDiagnostic.contains("secret-token"))
        assertTrue(row.safeDiagnostic.contains("<redacted>"))
    }



    @Test
    fun mediaQueueActionsExposeLaunchRetryCancelAndCleanupWithoutSecrets() {
        val service = MediaCaptureService(clock = { 9_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Actions episode",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = record,
            variants = variants,
            selection = MediaTrackSelection(videoVariantId = variants.last().id, audioVariantId = variants.first().id),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(MediaSessionHeader("Cookie", "SID=secret-cookie")),
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 9_100L)
        val telemetry = MediaQueueTelemetryPlanner().deck(listOf(dispatch), emptyList())
        val dashboard = MediaQueueActionPlanner().dashboard(telemetry, listOf(dispatch), emptyList())
        val launchPlan = dashboard.plans.single()
        val failedJob = MediaExecutionJob(
            captureId = record.id,
            title = "Actions episode",
            stage = MediaExecutionStage.Failed,
            engine = "yt-dlp",
            detail = "extractor failed token=secret-token Cookie: SID=secret-cookie",
            canRetry = true,
        )
        val retryPlan = MediaQueueActionPlanner().actionPlan(dispatch, failedJob)

        assertEquals(1, dashboard.launchableCount)
        assertEquals(MediaQueueActionKind.Launch, launchPlan.primaryAction.kind)
        assertTrue(launchPlan.actions.any { it.kind == MediaQueueActionKind.ViewDiagnostics })
        assertEquals(MediaQueueActionKind.Retry, retryPlan.primaryAction.kind)
        assertTrue(retryPlan.actions.any { it.kind == MediaQueueActionKind.CleanupTerminal && it.requiresConfirmation })
        assertFalse(launchPlan.safeSummary.contains("secret-cookie"))
        assertFalse(retryPlan.safeSummary.contains("secret-token"))
        assertFalse(retryPlan.safeSummary.contains("secret-cookie"))
    }

    @Test
    fun mediaQueueActionsExplainBlockedPreQueueStates() {
        val service = MediaCaptureService(clock = { 10_000L })
        val expired = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8",
            pageTitle = "Expired actions",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        ))).copy(
            manifestExpiresAtEpochMs = 9_900L,
            resolutionStatus = com.mikeyphw.xdm.android.model.MediaResolutionStatus.RequiresRefresh,
        )
        val planner = MediaExecutionLibraryPlanner()
        val dispatcher = MediaExecutionDispatcher()
        val spec = planner.queueSpec(expired, emptyList(), MediaTrackSelection(), "content://downloads")
        val dispatch = dispatcher.dispatchPlan(spec, planner.enginePlan(spec, 35), expired, termuxReady = false, nowEpochMs = 10_100L)
        val plan = MediaQueueActionPlanner().actionPlan(dispatch, null)

        assertEquals(MediaQueueActionKind.RefreshMetadata, plan.primaryAction.kind)
        assertTrue(plan.unavailableReasons.any { it.contains("metadata refresh") })
        assertFalse(plan.actions.any { it.kind == MediaQueueActionKind.Launch && it.enabled })
    }



    @Test
    fun mediaWorkerBridgeBuildsUidtRequestForDirectMediaWithoutSecrets() {
        val service = MediaCaptureService(clock = { 11_000L })
        val record = service.detect("https://cdn.example.test/movie.mp4?token=secret-token", pageTitle = "Bridge movie").single()
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 11_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 11_200L)

        assertEquals(MediaWorkerBridgeKind.AndroidUidt, request.kind)
        assertEquals(MediaWorkerBridgeReadiness.Ready, request.readiness)
        assertTrue(request.launchable)
        assertEquals("dataSync", request.notification.foregroundServiceType)
        assertTrue(request.durableJobId.startsWith("media-"))
        assertFalse(request.summary.contains("secret-token"))
        assertFalse(request.adapter.redactedPreview.contains("secret-token"))
        assertFalse(request.redactedSidecarJson.contains("secret-token"))
    }

    @Test
    fun mediaWorkerBridgeBuildsTypedTermuxYtDlpRequestWithCleanupOwnedSecrets() {
        val service = MediaCaptureService(clock = { 12_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Bridge episode",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(
            capture = record,
            variants = variants,
            selection = MediaTrackSelection(videoVariantId = variants.last().id, audioVariantId = variants.first().id),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(MediaSessionHeader("Cookie", "SID=secret-cookie")),
        )
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 12_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 12_200L)
        val dashboard = MediaWorkerBridgePlanner().dashboard(listOf(request))

        assertEquals(MediaWorkerBridgeKind.TermuxYtDlp, request.kind)
        assertEquals(MediaWorkerBridgeReadiness.Ready, request.readiness)
        assertFalse(request.adapter.rawShellExposed)
        assertTrue(request.adapter.transientInputLabels.any { it.endsWith(".cookies.txt") })
        assertTrue(request.cleanupAfterTerminal.any { it.contains("temporary Netscape cookie") })
        assertEquals(1, dashboard.termuxWorkerCount)
        assertTrue(dashboard.secretSafe)
        assertFalse(request.summary.contains("secret-cookie"))
        assertFalse(request.adapter.redactedPreview.contains("secret-token"))
        assertFalse(request.safeRunbook.joinToString("\n").contains("secret-session"))
    }


    @Test
    fun termuxRuntimeAdapterBuildsTypedYtDlpPlanWithTransientCookieCleanup() {
        val service = MediaCaptureService(clock = { 13_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Runtime episode",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val variants = service.parseHlsPlaylist(
            captureId = record.id,
            playlistUrl = record.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val execution = MediaExecutionLibraryPlanner()
        val spec = execution.queueSpec(
            capture = record,
            variants = variants,
            selection = MediaTrackSelection(videoVariantId = variants.last().id, audioVariantId = variants.first().id),
            destinationUri = "content://downloads",
            sessionHeaders = listOf(MediaSessionHeader("Cookie", "SID=secret-cookie")),
        )
        val engine = execution.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 13_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 13_200L)
        val plan = MediaTermuxRuntimeAdapter().launchPlan(
            request = request,
            availableTools = setOf("yt-dlp", "aria2c", "ffmpeg", "ffprobe"),
        )

        assertEquals(TermuxRuntimeLaunchKind.YtDlpDownload, plan.kind)
        assertTrue(plan.launchable)
        assertTrue(plan.noRawShell)
        assertTrue(plan.transientFiles.any { it.kind == TermuxRuntimeTransientKind.NetscapeCookies })
        assertTrue(plan.cleanupSteps.any { it.label.contains("Netscape cookie file") })
        assertFalse(plan.redactedPreview.contains("secret-cookie"))
        assertFalse(plan.redactedPreview.contains("secret-token"))
        assertFalse(plan.redactedPreview.contains("secret-session"))
    }

    @Test
    fun termuxRuntimeAdapterBlocksMissingToolsWithInstallHelpButNoAutoInstall() {
        val service = MediaCaptureService(clock = { 14_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8",
            pageTitle = "Missing runtime",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 14_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 14_200L)
        val plan = MediaTermuxRuntimeAdapter().launchPlan(request, availableTools = emptySet())

        assertFalse(plan.launchable)
        assertTrue(plan.missingToolHints.any { it.contains("Install yt-dlp") })
        assertFalse(plan.redactedPreview.contains("pkg install"))
        assertFalse(plan.redactedPreview.contains("apt install"))
    }

    @Test
    fun termuxRuntimeAdapterBuildsAria2TransientInputAndSessionCleanup() {
        val service = MediaCaptureService(clock = { 15_000L })
        val record = service.detect("https://cdn.example.test/movie.mp4?token=secret-token", pageTitle = "Runtime movie").single()
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 15_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 15_200L)
        val aria2Request = request.copy(kind = MediaWorkerBridgeKind.Aria2Adapter, lane = MediaExecutionLane.Aria2Segmented)
        val plan = MediaTermuxRuntimeAdapter().launchPlan(aria2Request, availableTools = setOf("aria2c"))

        assertEquals(TermuxRuntimeLaunchKind.Aria2Download, plan.kind)
        assertTrue(plan.transientFiles.any { it.kind == TermuxRuntimeTransientKind.Aria2Input || it.kind == TermuxRuntimeTransientKind.Aria2Session })
        assertTrue(plan.cleanupSteps.any { it.verifierLabel.contains("aria2") })
        assertFalse(plan.redactedPreview.contains("secret-token"))
    }

    @Test
    fun nativeDirectDownloadEnginePlansReadyDirectAudioWithoutPersistingHeaders() {
        val service = MediaCaptureService(clock = { 16_000L })
        val record = service.detect("https://cdn.example.test/song.mp3?token=secret-token", pageTitle = "Native song").single()
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 16_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 16_200L)
        val plan = MediaNativeDirectDownloadPlanner().plan(request, destinationUri = "mediastore://music")

        assertEquals(NativeDirectRequestState.Ready, plan.state)
        assertEquals(NativeDirectDestinationMode.MediaStoreMusic, plan.destinationMode)
        assertTrue(plan.launchable)
        assertFalse(plan.headerPolicy.persistHeaderValues)
        assertFalse(plan.redactedDiagnostics.contains("secret-token"))
        assertTrue(plan.redactedDiagnostics.contains("<redacted>"))
    }

    @Test
    fun nativeDirectDownloadEngineResumesExistingPartialWithRangePreview() {
        val service = MediaCaptureService(clock = { 17_000L })
        val record = service.detect("https://cdn.example.test/movie.mp4", pageTitle = "Native movie").single()
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 17_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 17_200L)
        val plan = MediaNativeDirectDownloadPlanner().plan(request, destinationUri = "mediastore://movies", existingBytes = 4096L)

        assertEquals(NativeDirectRequestState.ResumeCandidate, plan.state)
        assertEquals(NativeDirectRangeSupport.Supported, plan.resumePlan.rangeSupport)
        assertTrue(plan.resumePlan.rangeHeaderPreview?.contains("4096") == true)
        assertTrue(plan.launchable)
    }

    @Test
    fun nativeDirectDownloadEngineRejectsAdaptiveRequestsWithoutRawShellOrSecrets() {
        val service = MediaCaptureService(clock = { 18_000L })
        val record = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/master.m3u8?token=secret-token",
            pageTitle = "Adaptive native block",
            pageUrl = "https://watch.example.test/watch?session=secret-session",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        )))
        val planner = MediaExecutionLibraryPlanner()
        val spec = planner.queueSpec(record, emptyList(), MediaTrackSelection(), "content://downloads")
        val engine = planner.enginePlan(spec, androidSdkInt = 35)
        val dispatch = MediaExecutionDispatcher().dispatchPlan(spec, engine, record, termuxReady = true, nowEpochMs = 18_100L)
        val actions = MediaQueueActionPlanner().actionPlan(dispatch, null)
        val request = MediaWorkerBridgePlanner().request(spec, engine, dispatch, actions, nowEpochMs = 18_200L)
        val plan = MediaNativeDirectDownloadPlanner().plan(request, destinationUri = "content://downloads")

        assertEquals(NativeDirectRequestState.UnsupportedAdaptive, plan.state)
        assertFalse(plan.launchable)
        assertFalse(plan.redactedDiagnostics.contains("secret-token"))
        assertFalse(plan.redactedDiagnostics.contains("secret-session"))
        assertTrue(plan.redactedDiagnostics.contains("rawShell=false"))
    }


    @Test
    fun offlineLibraryV2FiltersSortsAndExportsSafeSidecars() {
        val service = MediaCaptureService(clock = { 19_000L })
        val audio = service.detect("https://cdn.example.test/song.mp3?token=secret-token", pageTitle = "Library song").single()
        val planner = MediaExecutionLibraryPlanner()
        val download = com.mikeyphw.xdm.android.model.Download(
            id = "download-audio",
            fileName = "song.mp3",
            sourceUrl = audio.sourceUrl,
            destinationUri = "content://downloads/song.mp3",
            state = com.mikeyphw.xdm.android.model.DownloadState.Completed,
            backend = com.mikeyphw.xdm.android.model.BackendType.Native,
            bytesReceived = 1024L,
            totalBytes = 1024L,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = 19_000L,
            updatedAtEpochMs = 19_100L,
            mimeType = "audio/mpeg",
        )
        val captured = audio.copy(downloadId = download.id)
        val item = planner.offlineLibraryItems(listOf(captured), listOf(download), emptyList()).single()
        val dashboard = MediaOfflineLibraryV2Planner().dashboard(
            items = listOf(item),
            filterState = OfflineLibraryV2FilterState(filters = setOf(OfflineLibraryV2Filter.Audio), sortKey = OfflineLibraryV2SortKey.Title, descending = false),
            existingFiles = setOf("song.mp3"),
        )

        assertEquals(1, dashboard.visibleCount)
        assertEquals(1, dashboard.audioCount)
        assertTrue(dashboard.rows.single().canPlay)
        assertTrue(dashboard.rows.single().actions.any { it.kind == OfflineLibraryV2ActionKind.RenameSidecar })
        assertFalse(dashboard.rows.single().safeExportJson.contains("secret-token"))
        assertTrue(dashboard.secretSafe)
    }

    @Test
    fun offlineLibraryV2DetectsMissingFilesAndRequiresSidecarConfirmation() {
        val service = MediaCaptureService(clock = { 20_000L })
        val video = service.detect("https://cdn.example.test/movie.mp4", pageTitle = "Library movie").single()
        val download = com.mikeyphw.xdm.android.model.Download(
            id = "download-video",
            fileName = "movie.mp4",
            sourceUrl = video.sourceUrl,
            destinationUri = "content://downloads/movie.mp4",
            state = com.mikeyphw.xdm.android.model.DownloadState.Completed,
            backend = com.mikeyphw.xdm.android.model.BackendType.Aria2,
            bytesReceived = 2048L,
            totalBytes = 2048L,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = 20_000L,
            updatedAtEpochMs = 20_100L,
            mimeType = "video/mp4",
        )
        val item = MediaExecutionLibraryPlanner().offlineLibraryItems(listOf(video.copy(downloadId = download.id)), listOf(download), emptyList()).single()
        val dashboard = MediaOfflineLibraryV2Planner().dashboard(
            items = listOf(item),
            filterState = OfflineLibraryV2FilterState(filters = setOf(OfflineLibraryV2Filter.MissingFile)),
            existingFiles = setOf("different-file.mp4"),
        )
        val row = dashboard.rows.single()

        assertEquals(OfflineLibraryV2Health.MissingFile, row.health)
        assertTrue(row.actions.any { it.kind == OfflineLibraryV2ActionKind.LocateMissingFile && it.enabled })
        assertTrue(row.actions.any { it.kind == OfflineLibraryV2ActionKind.RemoveSidecar && it.requiresConfirmation })
    }

    @Test
    fun playerDiagnosticsClassifyDecoderAndProtectedMediaWithoutBypass() {
        val direct = MediaPlaybackCandidate(
            captureId = "direct",
            title = "Local movie",
            playbackUrl = "content://downloads/movie.mp4",
            isAdaptive = false,
            needsExternalResolver = false,
            subtitleCount = 1,
            audioTrackCount = 2,
        )
        val decoderReport = MediaPlayerDiagnosticsPlanner().report(
            candidate = direct,
            error = MediaPlayerErrorSnapshot(
                errorCodeName = "ERROR_CODE_DECODER_INIT_FAILED",
                message = "decoder failed for codec av01",
                playbackStateLabel = "state=idle",
                playWhenReady = false,
                suppressionReasonLabel = null,
            ),
            positionMs = 4096L,
            durationMs = 90_000L,
        )
        val protectedReport = MediaPlayerDiagnosticsPlanner().report(
            candidate = direct.copy(captureId = "protected", needsExternalResolver = true, playbackUrl = "https://cdn.example.test/manifest.mpd?token=secret-token"),
        )

        assertEquals(MediaPlayerDiagnosticBucket.Decoder, decoderReport.bucket)
        assertTrue(decoderReport.retryPrepareAvailable)
        assertTrue(decoderReport.tracks.any { it.kind == "audio" })
        assertTrue(decoderReport.subtitleRows.isNotEmpty())
        assertTrue(decoderReport.positionMemory.persistAllowed)
        assertEquals(MediaPlayerDiagnosticBucket.ProtectedMedia, protectedReport.bucket)
        assertTrue(protectedReport.protectedDiagnosticOnly)
        assertFalse(protectedReport.summary.contains("secret-token"))
        assertTrue(protectedReport.actions.contains(MediaPlayerDiagnosticAction.ViewProtectedDiagnostics))
    }


    @Test
    fun browserCaptureQualityGroupsDuplicatesAndSuppressesNoise() {
        val service = MediaCaptureService(clock = { 21_000L })
        val first = service.detect("https://video.example.test/live/master.m3u8?token=secret-token", pageTitle = "Live event", pageUrl = "https://video.example.test/watch").single()
        val duplicate = service.detect("https://video.example.test/live/master.m3u8?token=secret-token", pageTitle = "Live event", pageUrl = "https://video.example.test/watch").single().copy(id = "duplicate-capture", createdAtEpochMs = 21_100L)
        val noise = service.detect("https://metrics.example.test/analytics/pixel.mp4", pageTitle = "tracker").single()
        val variants = service.parseHlsPlaylist(
            captureId = first.id,
            playlistUrl = first.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio.m3u8"
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="sub.vtt"
                #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
                video.m3u8
            """.trimIndent(),
        )
        val dashboard = MediaBrowserCaptureQualityPlanner().dashboard(listOf(first, duplicate, noise), variants, nowEpochMs = 21_500L)

        assertEquals(1, dashboard.noiseCount)
        assertEquals(1, dashboard.duplicateCount)
        assertTrue(dashboard.liveCount >= 1)
        assertTrue(dashboard.rows.any { it.disposition == CaptureQualityDisposition.GroupWithExisting })
        assertTrue(dashboard.rows.any { it.signals.contains(CaptureQualitySignal.TrackRichness) })
        assertFalse(dashboard.rows.joinToString("\n") { it.safeDiagnostics }.contains("secret-token"))
        assertTrue(dashboard.secretSafe)
    }

    @Test
    fun browserCaptureQualityFlagsRefreshWithoutLeakingTokenizedUrls() {
        val service = MediaCaptureService(clock = { 22_000L })
        val capture = service.detect("https://cdn.example.test/movie.mpd?sig=secret-session", pageTitle = "Refresh me").single().copy(
            manifestExpiresAtEpochMs = 21_000L,
            resolutionStatus = com.mikeyphw.xdm.android.model.MediaResolutionStatus.RequiresRefresh,
        )
        val dashboard = MediaBrowserCaptureQualityPlanner().dashboard(listOf(capture), emptyList(), nowEpochMs = 22_500L)
        val row = dashboard.rows.single()

        assertEquals(CaptureQualityDisposition.NeedsMetadataRefresh, row.disposition)
        assertTrue(row.refreshMetadataAvailable)
        assertTrue(row.signals.contains(CaptureQualitySignal.ExpiredSession))
        assertFalse(row.safeDiagnostics.contains("secret-session"))
    }

    @Test
    fun sessionPrivacyAuditBlocksDurableSecretLeaks() {
        val service = MediaCaptureService(clock = { 23_000L })
        val capture = service.detect("https://cdn.example.test/movie.mp4?token=secret-token", pageTitle = "Secret movie", pageUrl = "https://page.example.test/watch?sid=secret-session").single()
        val download = com.mikeyphw.xdm.android.model.Download(
            id = "download-secret",
            fileName = "movie.mp4",
            sourceUrl = capture.sourceUrl,
            destinationUri = "content://downloads/movie.mp4",
            state = com.mikeyphw.xdm.android.model.DownloadState.Failed,
            backend = com.mikeyphw.xdm.android.model.BackendType.Aria2,
            bytesReceived = 0L,
            totalBytes = 2048L,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = 23_000L,
            updatedAtEpochMs = 23_100L,
            errorMessage = "Cookie: sid=secret-session",
            mimeType = "video/mp4",
        )
        val planner = MediaExecutionLibraryPlanner()
        val captured = capture.copy(downloadId = download.id)
        val item = planner.offlineLibraryItems(listOf(captured), listOf(download), emptyList()).single()
        val jobs = planner.executionJobs(listOf(captured), listOf(download), emptyList(), emptyList())
        val audit = MediaSessionPrivacyAuditPlanner().audit(
            captures = listOf(captured),
            variants = emptyList(),
            libraryItems = listOf(item),
            executionJobs = jobs,
            diagnostics = listOf("Bearer secret-token", item.detail),
            cleanupLedger = mapOf(captured.id to false),
        )

        assertTrue(audit.reviewCount > 0)
        assertTrue(audit.blockerCount >= 1)
        assertTrue(audit.cleanupDueCount >= 1)
        assertFalse(audit.findings.joinToString("\n") { it.summary }.contains("secret-token"))
        assertFalse(audit.findings.joinToString("\n") { it.summary }.contains("secret-session"))
        assertFalse(audit.transientCleanupHealthy)
    }

    @Test
    fun sessionPrivacyAuditPassesRedactedSidecarsAndVerifiedCleanup() {
        val service = MediaCaptureService(clock = { 24_000L })
        val capture = service.detect("https://cdn.example.test/song.mp3", pageTitle = "Clean song").single()
        val download = com.mikeyphw.xdm.android.model.Download(
            id = "download-clean",
            fileName = "song.mp3",
            sourceUrl = capture.sourceUrl,
            destinationUri = "content://downloads/song.mp3",
            state = com.mikeyphw.xdm.android.model.DownloadState.Completed,
            backend = com.mikeyphw.xdm.android.model.BackendType.Native,
            bytesReceived = 1024L,
            totalBytes = 1024L,
            speedBytesPerSecond = 0L,
            queueId = null,
            priority = 0,
            createdAtEpochMs = 24_000L,
            updatedAtEpochMs = 24_100L,
            mimeType = "audio/mpeg",
        )
        val planner = MediaExecutionLibraryPlanner()
        val captured = capture.copy(downloadId = download.id)
        val item = planner.offlineLibraryItems(listOf(captured), listOf(download), emptyList()).single()
        val audit = MediaSessionPrivacyAuditPlanner().audit(
            captures = listOf(captured),
            variants = emptyList(),
            libraryItems = listOf(item),
            executionJobs = planner.executionJobs(listOf(captured), listOf(download), emptyList(), emptyList()),
            cleanupLedger = mapOf(captured.id to true),
        )

        assertEquals(0, audit.blockerCount)
        assertTrue(audit.cleanupVerifiedCount >= 1)
        assertTrue(audit.durableSecretSafe)
        assertTrue(audit.transientCleanupHealthy)
    }



    @Test
    fun mobilePolishDeckKeepsMediaStackPhoneFriendlyAndSecretSafe() {
        val service = MediaCaptureService(clock = { 3_200L })
        val capture = service.recordFor(requireNotNull(service.candidateFor(
            url = "https://cdn.example.test/mobile/master.m3u8?token=secret",
            pageTitle = "Mobile Episode",
            pageUrl = "https://video.example.test/watch/mobile",
            mimeTypeHint = "application/vnd.apple.mpegurl",
        ))).copy(resolutionStatus = MediaResolutionStatus.RequiresRefresh)
        val variants = service.parseHlsPlaylist(
            captureId = capture.id,
            playlistUrl = capture.sourceUrl,
            playlistText = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English CC",LANGUAGE="en",URI="subs/en.vtt"
                #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
                video/720.m3u8
            """.trimIndent(),
        )
        val queueTelemetry = MediaQueueTelemetryDeck(
            rows = emptyList(),
            readyToLaunchCount = 0,
            activeCount = 0,
            needsAttentionCount = 0,
            cleanupArmedCount = 0,
            terminalCount = 0,
            secretSafe = true,
        )
        val queueActions = MediaQueueActionDashboard(
            plans = emptyList(),
            launchableCount = 1,
            pausableCount = 0,
            retryableCount = 0,
            cancellableCount = 0,
            cleanupCount = 0,
            attentionCount = 0,
            bulkActions = emptyList(),
            secretSafe = true,
        )
        val library = MediaOfflineLibraryV2Planner().dashboard(emptyList())
        val captureQuality = MediaBrowserCaptureQualityPlanner().dashboard(listOf(capture), variants, nowEpochMs = 3_200L)
        val privacy = MediaSessionPrivacyAuditPlanner().audit(
            captures = listOf(capture),
            variants = variants,
            libraryItems = emptyList(),
            executionJobs = emptyList(),
            diagnostics = listOf("safe mobile polish summary"),
            cleanupLedger = emptyMap(),
        )
        val deck = MediaMobilePolishPlanner().dashboard(
            captures = listOf(capture),
            queueTelemetry = queueTelemetry,
            queueActions = queueActions,
            library = library,
            playerReports = emptyList(),
            captureQuality = captureQuality,
            privacyAudit = privacy,
            compactPreferred = true,
        )

        assertEquals(MediaMobileSurfaceMode.CompactPhone, deck.mode)
        assertTrue(deck.sections.any { it.title == "Sticky current job summary" })
        assertTrue(deck.sections.any { it.title == "Collapsed privacy and cleanup drawer" })
        assertTrue(deck.recommendations.any { it.signal == MediaMobilePolishSignal.NoTinyScrollIslands })
        assertTrue(deck.recommendations.any { it.signal == MediaMobilePolishSignal.AccessibilityLabels })
        assertTrue(deck.accessibilityReady)
        assertTrue(deck.noTinyScrollIslands)
        assertTrue(deck.secretSafe)
        assertFalse(deck.summary.contains("token=secret"))
    }

}
