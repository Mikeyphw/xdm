package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar05ExternalIntakeAdmissionContractTest {
    @Test
    fun explicitIdempotencyKeyCannotAliasDifferentExecutableRequests() {
        val first = AutomationCommandDraft(
            source = AutomationCommandSource.Tasker,
            action = AutomationCommandAction.EnqueueDownload,
            url = "https://cdn.example.test/a.bin?token=one",
            explicitIdempotencyKey = "caller-reused-key",
        )
        val second = first.copy(url = "https://cdn.example.test/b.bin?token=one")

        assertNotEquals(first.stableIdempotencyKey, second.stableIdempotencyKey)
    }

    @Test
    fun redactedUrlIsNotTheDuplicateIdentityForSignedRequests() {
        val first = AutomationCommandDraft(
            source = AutomationCommandSource.BrowserExtension,
            action = AutomationCommandAction.PromptAddDownload,
            url = "https://cdn.example.test/video.mp4?token=one",
        )
        val second = first.copy(url = "https://cdn.example.test/video.mp4?token=two")

        assertEquals(
            "https://cdn.example.test/video.mp4?token=REDACTED",
            ExternalUrlPolicy.persistableUrl(first.url),
        )
        assertEquals(ExternalUrlPolicy.persistableUrl(first.url), ExternalUrlPolicy.persistableUrl(second.url))
        assertNotEquals(first.stableIdempotencyKey, second.stableIdempotencyKey)
    }

    @Test
    fun browserStableMediaIdAndCallerFingerprintAreOnlyEvidenceNotIdentity() {
        val first = AutomationCommandDraft(
            source = AutomationCommandSource.BrowserExtension,
            action = AutomationCommandAction.CaptureMedia,
            url = "https://cdn.example.test/master-a.m3u8",
            stableMediaId = "browser-declared-same-id",
            requestFingerprint = "caller-supplied-same-fingerprint",
            sessionRevision = 5L,
        )
        val second = first.copy(url = "https://cdn.example.test/master-b.m3u8")

        assertNotEquals(first.stableIdempotencyKey, second.stableIdempotencyKey)
    }

    @Test
    fun finalHeadersArePreferredButCrossOriginCredentialHeadersAreNotAllowed() {
        assertTrue(!ExternalUrlPolicy.credentialHeadersAllowedFor(
            pageUrl = "https://accounts.example.test/login",
            targetUrl = "https://cdn.example.test/video.mp4",
        ))
        assertTrue(ExternalUrlPolicy.credentialHeadersAllowedFor(
            pageUrl = "https://cdn.example.test/watch",
            targetUrl = "https://cdn.example.test/video.mp4",
        ))
    }

    @Test
    fun magnetIntakeTruthMatchesClassifier() {
        val draft = DownloadIntakePlanner(idFactory = { "magnet-id" }).fromManual(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=release",
        ) ?: error("Expected magnet review draft")
        assertEquals(DownloadIntakeKind.Torrent, draft.kind)
        assertEquals(AutomationRejectionReason.None, ExternalAdmissionPolicy.validateForDispatch(
            AutomationCommandDraft(
                source = AutomationCommandSource.Tasker,
                action = AutomationCommandAction.PromptAddDownload,
                url = draft.url,
                privateNetworkApproved = true,
            ),
        ))
    }

    @Test
    fun privateNetworkRequiresExplicitAdmissionApproval() {
        val draft = AutomationCommandDraft(
            source = AutomationCommandSource.ShareSheet,
            action = AutomationCommandAction.PromptAddDownload,
            url = "https://192.168.1.20/archive.zip",
        )
        assertEquals(AutomationRejectionReason.PrivateNetworkApprovalRequired, ExternalAdmissionPolicy.validateForDispatch(draft))
        assertEquals(AutomationRejectionReason.None, ExternalAdmissionPolicy.validateForDispatch(draft.copy(privateNetworkApproved = true)))
    }
}
