package com.mikeyphw.xdm.android.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRequestEnvelopeStoreTest {
    @Test
    fun envelopeExpiresAndCanBeDeletedExplicitly() {
        val store = InMemorySecureRequestEnvelopeStore()
        val envelope = SecureRequestEnvelope(
            subjectId = "download:one",
            exactUrl = "https://cdn.example/file?token=secret",
            boundHost = "cdn.example",
            headers = mapOf("Authorization" to "Bearer secret"),
            expiresAtEpochMs = 2_000L,
            attemptGeneration = 7L,
        )
        store.put(envelope)
        assertEquals(7L, store.get("download:one", 1_000L)?.attemptGeneration)
        assertNull(store.get("download:one", 2_000L))
        store.put(envelope.copy(expiresAtEpochMs = 4_000L))
        store.delete("download:one")
        assertNull(store.get("download:one", 3_000L))
    }

    @Test
    fun handoffFiltersHeaderInjectionAndSurvivesCacheEviction() {
        val store = InMemorySecureRequestEnvelopeStore()
        MediaRequestHandoffStore.initialize(store)
        MediaRequestHandoffStore.remember(
            downloadId = "one",
            exactUrl = "https://cdn.example/file?token=secret",
            headers = mapOf(
                "Cookie" to "session=secret",
                "Injected\nHeader" to "bad",
                "X-Test" to "bad\r\nInjected: yes",
            ),
            redactedSummary = "Cookie: <redacted>",
            isExpiringUrl = true,
            attemptGeneration = 3L,
        )
        val restored = MediaRequestHandoffStore.forDownload("one")
        assertEquals("cdn.example", restored?.boundHost)
        assertEquals("session=secret", restored?.headers?.get("Cookie"))
        assertFalse(restored?.headers.orEmpty().containsKey("Injected\nHeader"))
        assertFalse(restored?.headers.orEmpty().containsKey("X-Test"))
        MediaRequestHandoffStore.forget("one")
        assertTrue(MediaRequestHandoffStore.verifyForgotten("one"))
    }
}
