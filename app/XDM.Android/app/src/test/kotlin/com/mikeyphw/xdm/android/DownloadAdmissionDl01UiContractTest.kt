package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdmissionDl01UiContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".")

    @Test
    fun checksumIsValidatedBeforeDurableAdmissionAndUiIsSingleFlight() {
        val viewModel = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val surface = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt").readText()
        val app = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()

        val addStart = viewModel.indexOf("fun addDownload(")
        val checksumParse = viewModel.indexOf("parseExpectedChecksum", addStart)
        val durableAdmission = viewModel.indexOf("repository.admitDownload", addStart)
        assertTrue(addStart >= 0 && checksumParse > addStart && durableAdmission > checksumParse)
        assertTrue(viewModel.contains("_downloadAdmissionState.value.inFlight"))
        assertTrue(viewModel.contains("awaitingDuplicateDecision"))
        assertFalse(viewModel.substring(addStart, viewModel.indexOf("fun backendRecommendation", addStart)).contains("repository.save(download)"))

        assertTrue(surface.contains("admissionState.inFlight"))
        assertTrue(surface.contains("Adding…"))
        assertTrue(surface.contains("Open existing"))
        assertTrue(surface.contains("Add anyway"))
        assertTrue(surface.contains("DuplicateUrlAction.Skip"))
        assertTrue(surface.contains("onDismissAdmission()"))
        assertTrue(viewModel.contains("if (_downloadAdmissionState.value.inFlight) return"))
        assertTrue(viewModel.contains("AutomationCommandStatus.Duplicate"))
        assertTrue(viewModel.contains("AutomationRejectionReason.Duplicate"))
        assertTrue(viewModel.contains("duplicateLookupUrl = pending.download.sourceUrl"))
        assertTrue(app.contains("onDismissAdmission = viewModel::dismissDuplicateAddPrompt"))
        assertTrue(app.contains("onDuplicateDecision = viewModel::resolveDuplicateDownload"))
        assertTrue(app.contains("if (!downloadAdmission.inFlight)"))
    }
}
