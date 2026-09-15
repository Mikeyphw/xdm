package com.mikeyphw.xdm.android

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar13DownloadsTruthfulActionsContractTest {
    private val root: Path = generateSequence(Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { it.resolve("settings.gradle.kts").exists() && it.resolve("PROJECT_MANIFEST.json").exists() }
        ?: error("XDM Android root not found from ${System.getProperty("user.dir")}")

    private fun readUtf8(relativePath: String): String =
        String(Files.readAllBytes(root.resolve(relativePath)), StandardCharsets.UTF_8)

    @Test
    fun downloadsActionsReloadCurrentRowsAndDoNotHideNativeHlsWork() {
        val vm = readUtf8("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        assertTrue(vm.contains("repository.findDownload(download.id) ?: return@launch"))
        assertTrue(vm.contains("DownloadActionExecutionTruth.policyOverrideFromCurrent(current)"))
        assertTrue(vm.contains("nativeHlsMediaManager.cancel(current.id) else transferRuntime.cancel(current.id)"))
        assertTrue(vm.contains("Archive retained"))
        assertTrue(vm.contains("setArchivedTruthfully"))
    }

    @Test
    fun organizeUsesOneBatchPlannerAndSupportsTagUnassign() {
        val sheet = readUtf8("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/OrganizeDownloadsSheet.kt")
        assertTrue(sheet.contains("batchEnabled(DownloadActionKind.Archive)"))
        assertTrue(sheet.contains("Remove ${'$'}{tag.name}"))
        assertTrue(sheet.contains("onSetTagAssignment(tag, !allSelectedHaveTag)"))
        assertFalse(sheet.contains("onAssignTag(tag)"))
    }
}
