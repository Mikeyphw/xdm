package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestLocatorMc04Mc05ContractTest {
    private val root = androidRoot()

    @Test
    fun manifestFactsAreStructuredPersistedMigratedAndConsumed() {
        val model = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt")
        val parser = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
        val sniffer = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        val entities = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt")
        val repository = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
        val database = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
        val migrations = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
        val app = source("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
        val schema = source("persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/21.json")

        assertTrue(model.contains("enum class MediaManifestRole"))
        assertTrue(model.contains("manifestRole: MediaManifestRole"))
        assertTrue(model.contains("manifestProtected: Boolean"))
        assertTrue(model.contains("audioGroupId: String?"))
        assertTrue(model.contains("subtitleGroupId: String?"))
        assertTrue(model.contains("inStreamId: String?"))
        assertTrue(parser.contains("MediaManifestRole.HlsMaster"))
        assertTrue(parser.contains("MediaManifestRole.HlsMedia"))
        assertTrue(parser.contains("#EXT-X-SESSION-KEY"))
        assertTrue(parser.contains("getElementsByTagNameNS(\"*\", \"ContentProtection\")"))
        assertTrue(parser.contains("DocumentBuilderFactory.newInstance()"))
        assertTrue(sniffer.contains("decorateRecordWithManifestSummary"))
        val extensionDetector = source("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
        assertTrue(extensionDetector.contains("ORDINARY_MEDIA_CONTAINER_RE"))
        assertTrue(extensionDetector.contains("if (ORDINARY_MEDIA_CONTAINER_RE.test(value)) return false"))
        assertTrue(planner.contains("capture.manifestIsLive"))
        assertTrue(planner.contains("capture.manifestProtected"))
        assertTrue(entities.contains("manifestProtectionScheme"))
        assertTrue(repository.contains("manifestRole = manifestRole.name"))
        assertTrue(repository.contains("audioGroupId = audioGroupId"))
        assertTrue(database.contains("version = 21"))
        assertTrue(migrations.contains("Migration20To21 = object : Migration(20, 21)"))
        assertTrue(app.contains("Migration20To21"))
        assertTrue(schema.contains("\"version\": 21"))
        assertTrue(schema.contains("manifestRole"))
        assertTrue(schema.contains("audioGroupId"))
    }

    @Test
    fun probeDoesNotReplayRangeValidatorsOrCompressedBodies() {
        val sniffer = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")
        assertFalse(sniffer.substringAfter("private val PROBE_HEADER_ALLOWLIST").substringBefore(")").contains("range"))
        assertFalse(sniffer.substringAfter("private val PROBE_HEADER_ALLOWLIST").substringBefore(")").contains("if-none-match"))
        assertFalse(sniffer.substringAfter("private val PROBE_HEADER_ALLOWLIST").substringBefore(")").contains("if-modified-since"))
        assertFalse(sniffer.substringAfter("private val PROBE_HEADER_ALLOWLIST").substringBefore(")").contains("accept-encoding"))
        assertTrue(sniffer.contains("connection.setRequestProperty(\"Accept-Encoding\", \"identity\")"))
    }

    @Test
    fun liveLocatorHooksAtDocumentStartCorrelatesNativeRequestsAndBoundsWork() {
        val locator = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
        val gradle = source("app/build.gradle.kts")
        val versions = source("gradle/libs.versions.toml")

        assertTrue(versions.contains("androidx-webkit"))
        assertTrue(gradle.contains("implementation(libs.androidx.webkit)"))
        assertTrue(locator.contains("WebViewCompat.addDocumentStartJavaScript"))
        assertTrue(locator.contains("WebViewFeature.DOCUMENT_START_SCRIPT"))
        assertTrue(locator.contains("override fun shouldInterceptRequest"))
        assertTrue(locator.contains("recordNativeRequest(request)"))
        assertTrue(locator.contains("WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest"))
        assertTrue(locator.contains("val correlated = nativeEvidenceFor(url)"))
        assertTrue(locator.contains("if (correlated != null) exactHeaders + jsHeaders else emptyMap()"))
        assertTrue(locator.contains("candidateCorrelated != null"))
        assertTrue(locator.indexOf("nativeEvidenceFor(url)") < locator.indexOf("CookieManager.getInstance().getCookie(url)"))
        assertTrue(locator.contains("MAX_NATIVE_REQUESTS = 256"))
        assertTrue(locator.contains("MAX_PENDING_OBSERVATIONS = 48"))
        assertTrue(locator.contains("MAX_LOCATED_CANDIDATES = 128"))
        assertTrue(locator.contains("OBSERVATION_DEDUPE_MS = 750L"))
        assertTrue(locator.contains("new MutationObserver(scheduleScan)"))
        assertTrue(locator.contains("setTimeout(scan, 120)"))
        assertTrue(locator.contains("override fun onRenderProcessGone"))
        assertTrue(locator.contains("webViewDisposed = true"))

        val frameBridge = source("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
        val pageSniffer = source("browser-extension/src/main/extension/xdm-firefox/page-sniffer.js")
        assertTrue(frameBridge.contains("reconcileInstrumentation()"))
        assertTrue(frameBridge.contains("stopPerformanceObserver()"))
        assertTrue(frameBridge.contains("PAGE_SNIFFER_CONTROL_MARKER"))
        assertTrue(pageSniffer.contains("if (!active) return \"\""))
        assertTrue(pageSniffer.contains("if (!active) return;"))
    }

    @Test
    fun locatorReadsUnknownLengthTextInBoundedChunksAndRestoresState() {
        val locator = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
        assertTrue(locator.contains("const MAX_BODY = 262144"))
        assertTrue(locator.contains("body.getReader()"))
        assertTrue(locator.contains("reader.cancel()"))
        assertTrue(locator.contains("boundedText(response.clone())"))
        assertFalse(locator.contains("response.clone().text()"))
        assertFalse(locator.contains("length >= 0 && length <="))
        assertTrue(locator.contains("override fun onSaveInstanceState"))
        assertTrue(locator.contains("restoreLocatorState(savedInstanceState)"))
        assertTrue(locator.contains("STATE_CANDIDATES"))
        assertTrue(locator.contains("MAX_SAVED_VARIANTS_PER_CANDIDATE = 24"))
        assertTrue(locator.contains("put(\"variants\", JSONArray().apply"))
        assertTrue(locator.contains("val restoredVariants = buildList"))
        assertTrue(locator.contains("variants = restoredVariants"))
        assertTrue(locator.contains("audioGroupId = item.optString(\"audioGroupId\")"))
        assertTrue(locator.contains("subtitleGroupId = item.optString(\"subtitleGroupId\")"))
    }

    @Test
    fun firstManifestResolutionCanUseCurrentReviewApprovalsBeforeSidecarCommit() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val resolver = viewModel.substringAfter("private suspend fun resolveCapturedPlaylistIfPossible")
            .substringBefore("private fun")
        assertTrue(resolver.contains("privateNetworkApproved: Boolean? = null"))
        assertTrue(resolver.contains("cleartextCredentialsApproved: Boolean? = null"))
        assertTrue(resolver.contains("privateNetworkApproved ?: (storedHandoff?.privateNetworkApproved == true)"))
        assertTrue(resolver.contains("cleartextCredentialsApproved ?: (storedHandoff?.cleartextCredentialsApproved == true)"))
        // Direct capture execution owns approval flags on AutomationCommandDraft. The generic
        // DownloadIntakeDraft path must instead recover only the reviewed command's exact scope.
        assertTrue(viewModel.contains("privateNetworkApproved = draft.privateNetworkApproved"))
        assertTrue(viewModel.contains("cleartextCredentialsApproved = draft.cleartextCredentialsApproved"))
        assertTrue(viewModel.contains("MediaRequestHandoffStore.forCommand(draft.id)"))
        assertTrue(viewModel.contains("DownloadRequestApprovalScope.forUrl(intake.record.sourceUrl)"))
        assertTrue(viewModel.contains("currentReviewScope in currentReviewHandoff?.privateNetworkApprovalScopes.orEmpty()"))
        assertFalse(viewModel.substringAfter("fun inspectExternalMedia(draft: DownloadIntakeDraft)").substringBefore("fun downloadMediaCapture").contains("draft.privateNetworkApproved"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
