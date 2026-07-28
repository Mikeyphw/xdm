package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8ECompileHotfixContractTest {
    private val root = androidRoot()

    @Test
    fun scopedWeightExtensionsAreResolvedFromLayoutScopes() {
        val files = listOf(
            "app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt",
            "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
        )
        files.forEach { relative ->
            val source = root.resolve(relative).readText()
            assertFalse(source.contains("import androidx.compose.foundation.layout.weight"))
            assertTrue(source.contains(".weight(1f)"))
        }
    }

    @Test
    fun storageReevaluationAvoidsDeprecatedIntentFields() {
        val source = root.resolve(
            "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueConditionMonitor.kt",
        ).readText()
        assertFalse(source.contains("Intent.ACTION_DEVICE_STORAGE_LOW"))
        assertFalse(source.contains("Intent.ACTION_DEVICE_STORAGE_OK"))
        assertTrue(source.contains("android.intent.action.DEVICE_STORAGE_LOW"))
        assertTrue(source.contains("android.intent.action.DEVICE_STORAGE_OK"))
        assertTrue(source.contains("actual free space before starting a transfer"))
    }


    @Test
    fun operationalActivityTestsUseTheModuleJUnit4Contract() {
        val source = root.resolve(
            "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/OperationalActivityTest.kt",
        ).readText()
        assertTrue(source.contains("import org.junit.Test"))
        assertTrue(source.contains("import org.junit.Assert.assertEquals"))
        assertTrue(source.contains("import org.junit.Assert.assertFalse"))
        assertTrue(source.contains("import org.junit.Assert.assertTrue"))
        assertFalse(source.contains("import kotlin.test"))
    }

    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (current.resolve("settings.gradle.kts").isFile && current.resolve("app").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
