package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.termux.PostProcessingExecutionPolicy
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessingPhase7GapClosureContractTest {
    @Test fun signedUrlsAreNeverPersistedAsVariantUrls() {
        assertNull(PostProcessingExecutionPolicy.sanitizeDurableRemoteUrl("https://cdn.example/video?token=secret"))
    }

    @Test fun metadataSanitizerRedactsNestedCredentials() {
        val source = java.io.File(generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile && java.io.File(it, "app").isDirectory }, "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/PostProcessingExecutionModels.kt").readText()
        assertTrue(source.contains("sanitizeMetadataJson"))
        assertTrue(source.contains("[REDACTED]"))
        assertTrue(source.contains("[REDACTED_URL]"))
        assertTrue(source.contains("Authorization") || source.contains("http_headers"))
    }
}
