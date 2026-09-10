package com.mikeyphw.xdm.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ux06LiveLocatorNativeShellContractTest {
    private val source = File("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
    private val strings = File("app/src/main/res/values/strings.xml").readText()

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
