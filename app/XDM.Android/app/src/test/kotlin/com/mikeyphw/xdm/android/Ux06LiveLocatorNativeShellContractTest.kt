package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Ux06LiveLocatorNativeShellContractTest {
    private val root = androidRoot()
    private val source = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
    private val strings = File(root, "app/src/main/res/values/strings.xml").readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (true) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app").isDirectory) return cursor
            cursor = cursor.parentFile ?: error("Could not locate XDM.Android root from ${System.getProperty("user.dir")}")
        }
    }

    @Test
    fun liveLocatorUsesNativeThemedShellAndSafeRendererRecovery() {
        assertTrue(source.contains("media_locator_title"))
        assertTrue(source.contains("ViewCompat.setOnApplyWindowInsetsListener"))
        assertTrue(source.contains("WebChromeClient"))
        assertTrue(source.contains("media_locator_reload"))
        assertTrue(source.contains("media_locator_stop"))
        assertTrue(source.contains("media_locator_scan"))
        assertTrue(source.contains("media_locator_candidates_header"))
        assertTrue(source.contains("onRenderProcessGone"))
        assertTrue(source.contains("if (webViewDisposed)"))
        assertTrue(source.contains("intent.putExtra(EXTRA_URL, normalized)"))
        assertTrue(source.contains("recreate()"))
        assertFalse(source.contains("setBackgroundColor(Color.WHITE)"))
        assertTrue(strings.contains("<string name=\"media_locator_title\">Live locator</string>"))
        assertTrue(strings.contains("The page renderer stopped. Tap Reload"))
    }
}
