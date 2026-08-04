package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.termux.PostProcessingExecutionPolicy
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessingPhase7GapClosureContractTest {
    @Test fun signedUrlsAreNeverPersistedAsVariantUrls() {
        assertNull(PostProcessingExecutionPolicy.sanitizeRemoteUrlForPersistence("https://cdn.example/video?token=secret"))
    }

    @Test fun metadataSanitizerRedactsNestedCredentials() {
        val value = PostProcessingExecutionPolicy.sanitizeMetadataJson("""{"formats":[{"url":"https://x/v?sig=abc"}],"http_headers":{"Authorization":"Bearer x"}}""")
        assertTrue(value.contains("[REDACTED]"))
        assertTrue(!value.contains("Bearer x"))
        assertTrue(!value.contains("sig=abc"))
    }
}
