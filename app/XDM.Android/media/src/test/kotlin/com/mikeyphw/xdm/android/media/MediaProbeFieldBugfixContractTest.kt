package com.mikeyphw.xdm.android.media

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProbeFieldBugfixContractTest {
    private val root = androidRoot()

    @Test
    fun pageProbeUsesBrowserLikeHeadersAndExplainsHttp403() {
        val source = File(root, "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt").readText()
        assertTrue(source.contains("applyDefaultProbeHeaders(connection, currentUrl, hopHeaders)"))
        assertTrue(source.contains("User-Agent"))
        assertTrue(source.contains("Accept-Language"))
        assertTrue(source.contains("Accept-Encoding"))
        assertTrue(source.indexOf("connection.responseCode") < source.indexOf("connection.inputStream"))
        assertTrue(source.contains("page-probe blocked by the site (HTTP"))
        assertTrue(source.contains("browser extension capture so cookies, referer, and the active session stay in the browser"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "media/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
