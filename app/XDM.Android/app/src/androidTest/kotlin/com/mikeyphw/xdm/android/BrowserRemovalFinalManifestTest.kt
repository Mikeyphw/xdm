package com.mikeyphw.xdm.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserRemovalFinalManifestTest {
    @Test
    fun ordinaryHttpsNavigationDoesNotResolveToXdm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/articles/release-notes"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertFalse(matches.any { it.activityInfo.packageName == context.packageName })
    }

    @Test
    fun typedApkDownloadStillResolvesToExternalAddDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("https://example.com/releases/application.apk"),
                "application/vnd.android.package-archive",
            )
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(matches.any {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name.endsWith("ExternalAddDownloadActivity")
        })
    }

    @Test
    fun debugBrowserSchemeResolvesOnlyToExternalAddDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("capture", "add").forEach { host ->
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("${BuildConfig.XDM_BROWSER_SCHEME}://$host?v=1&url=https%3A%2F%2Fexample.test%2Fvideo.mp4"),
            ).addCategory(Intent.CATEGORY_BROWSABLE)
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(matches.any {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name.endsWith("ExternalAddDownloadActivity")
            })
            assertFalse(matches.any {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name.endsWith("MainActivity") &&
                    !it.activityInfo.name.endsWith("ExternalAddDownloadActivity")
            })
        }
    }
}
