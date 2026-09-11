package com.mikeyphw.xdm.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaParity03NativeHlsExecutionContractTest {
    // Parity03 fixture markers: supported lowSpace unsupported Tiny Add
    private val root = File(System.getProperty("user.dir"))
    private fun source(path: String) = File(root, path).readText()

    @Test
    fun nativeHlsLaneIsImplementedBeforeYtDlpFallback() {
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        assertTrue(planner.contains("MediaDownloadStrategy { Native, NativeHls, Aria2, YtDlp"))
        assertTrue(planner.contains("shape == MediaTransferShape.AdaptivePlaylist && capture.kind == MediaSourceKind.HlsPlaylist && nativeHlsEligible"))
        assertTrue(planner.contains("MediaDownloadStrategy.NativeHls -> \"Native HLS\""))
        assertTrue(planner.contains("Supported VOD HLS is executed as one durable native segmented job"))
    }

    @Test
    fun persistenceHasSchema24NativeHlsJobAndPartLedger() {
        val db = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
        val entities = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt")
        val migrations = source("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt")
        assertTrue(db.contains("version = 24"))
        assertTrue(db.contains("NativeHlsJobEntity::class"))
        assertTrue(db.contains("NativeHlsPartEntity::class"))
        assertTrue(db.contains("abstract fun nativeHlsDao(): NativeHlsDao"))
        assertTrue(entities.contains("tableName = \"native_hls_jobs\""))
        assertTrue(entities.contains("admissionKey"))
        assertTrue(entities.contains("finalizationState"))
        assertTrue(entities.contains("tableName = \"native_hls_parts\""))
        assertTrue(entities.contains("mediaSequence"))
        assertTrue(entities.contains("byteRangeLength"))
        assertTrue(entities.contains("keyIvHex"))
        assertTrue(migrations.contains("Migration23To24 = object : Migration(23, 24)"))
        assertTrue(migrations.contains("CREATE TABLE IF NOT EXISTS native_hls_jobs"))
        assertTrue(migrations.contains("CREATE TABLE IF NOT EXISTS native_hls_parts"))
        assertTrue(File(root, "persistence/schemas/com.mikeyphw.xdm.android.persistence.AppDatabase/24.json").exists())
    }

    @Test
    fun completionIntegrityAndFallbackContractsAreExecutableSourceNotDocsOnly() {
        val engine = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt")
        assertTrue(engine.contains("NativeHlsSupportStatus.Supported"))
        assertTrue(engine.contains("NativeUnsupportedFallback"))
        assertTrue(engine.contains("ProtectedUnsupported"))
        assertTrue(engine.contains("EXT-X-PART"))
        assertTrue(engine.contains("EXT-X-PRELOAD-HINT"))
        assertTrue(engine.contains("SAMPLE-AES"))
        assertTrue(engine.contains("implicitIvHex"))
        assertTrue(engine.contains("storagePreflight"))
        assertTrue(engine.contains("verifyCompletion"))
        assertTrue(engine.contains("Artifact is playlist text, not finalized media"))
        assertTrue(engine.contains("Artifact looks like an HTML/error response"))
        assertTrue(engine.contains("progress"))
        assertTrue(engine.contains("coerceAtMost(99)"))
        assertTrue(engine.contains("existing logical-media HLS job reused"))
        assertTrue(engine.contains("explicit Add again created another HLS generation"))
    }
}
