package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopParityModelsTest {
    @Test fun settingsSnapshotRoundTripsWithoutSecrets() {
        val snapshot = SettingsExchangeSnapshot(true, "content://downloads/tree/main", FilenameConflictPolicy.Compare, ProxyCredentialSettings(true, "proxy.local", 8080, "mike", "main-proxy"), PostProcessingSettings(true, ConversionPreset.VideoFastStart, "faststart"))
        assertEquals(snapshot, SettingsExchangeCodec.decode(snapshot.toPortableText()))
        assertFalse(snapshot.toPortableText().contains("password", ignoreCase = true))
    }
    @Test fun historyReportMarksOnlyFinishedItemsAsRemovable() {
        val downloads = listOf(sampleDownload("a", DownloadState.Completed), sampleDownload("b", DownloadState.Downloading), sampleDownload("c", DownloadState.Failed))
        val report = HistoryManagementPolicy.summarize(downloads)
        assertEquals(3, report.total)
        assertEquals(1, report.active)
        assertEquals(2, report.removableHistory)
        assertTrue(HistoryManagementPolicy.isSafeToRemoveFromHistory(downloads.first()))
        assertFalse(HistoryManagementPolicy.isSafeToRemoveFromHistory(downloads[1]))
    }
    @Test fun protocolReportShowsAria2OptimizedProtocols() {
        val report = ProtocolExpansionPolish.summarize(listOf(
            BackendCapabilityRow(BackendType.Native, true, setOf("http", "https"), true, false, false, true, true, true, true, true, BackendDiagnosticDetail.Forensic, BackendBatteryImpact.Low, "native"),
            BackendCapabilityRow(BackendType.Aria2, true, setOf("http", "https", "ftp", "sftp", "magnet"), true, true, true, true, true, false, false, false, BackendDiagnosticDetail.Detailed, BackendBatteryImpact.High, "aria2"),
        ))
        assertTrue(report.rows.any { it.protocol == "ftp" && it.aria2 })
        assertTrue(report.rows.any { it.protocol == "hls" && it.native })
    }
    @Test fun organizationPoliciesDetectDuplicatesAndDestinationRules() {
        val download = sampleDownload("a", DownloadState.Completed)
        assertEquals(download, OrganizationPowerTools.duplicateFor("HTTPS://EXAMPLE.TEST:443/a.bin?token=secret", listOf(download)))
        val rules = listOf(DestinationRule("r1", "Videos", DestinationRuleMatch.Extension, "mp4", "content://movies", true, 10))
        assertEquals("content://movies", OrganizationPowerTools.destinationFor("https://cdn.test/video.mp4", "video.mp4", "video/mp4", rules, "xdm://downloads"))
    }
    @Test fun clipboardInboxExtractsNewSafeUrlsOnly() {
        val existing = listOf(ClipboardInboxItem("one", "https://example.test/a", null, "h", "New", 1, 1))
        val items = ClipboardInboxPolicy.itemsFromText("ftp://ignored https://example.test/a https://example.test/b.", existing, 2)
        assertEquals(listOf("https://example.test/b"), items.map { it.url })
    }
    @Test fun backupRestoreRejectsSensitiveExportFields() {
        assertTrue(BackupRestorePolicy.evaluate(SettingsExchangeSnapshot().toPortableText()).safe)
        assertFalse(BackupRestorePolicy.evaluate("xdm.settings.export=v1\nAuthorization=Bearer secret").safe)
    }
    @Test fun parityGateCanDeclareAllSurfacesComplete() { assertTrue(DesktopParityGate.evaluate(true, true, true, true, true, true).complete) }
    private fun sampleDownload(id: String, state: DownloadState) = Download(id, "$id.bin", "https://example.test/$id.bin?token=secret", "xdm://downloads", state, BackendType.Native, 0, null, 0, "default", 0, 1, 2)
}
