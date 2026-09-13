package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.OperationalActivityActionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Xar04NavigationSessionOwnershipContractTest {
    @Test
    fun addRouteUsesSessionReturnRouteInsteadOfRestoringAdd() {
        val session = AddDownloadNavigationPolicy.externalSession(
            draftId = "draft-1",
            currentPrimaryRoute = AppRoute.Media,
            idFactory = { "session-1" },
        )
        assertEquals(AppRoute.Media, session.returnRoute)
        assertEquals(AppRoute.Media, AddDownloadNavigationPolicy.visibleRoute(AppRoute.Add, session))
        assertEquals(AppRoute.Downloads, AppRoute.restore("Add"))
    }

    @Test
    fun externalDraftIsOwnedByExactlyOneAddSession() {
        val session = AddDownloadNavigationPolicy.externalSession(
            draftId = "command-1",
            currentPrimaryRoute = AppRoute.Downloads,
            idFactory = { "external-session" },
        )
        assertTrue(session.ownsDraft("command-1"))
        assertFalse(session.ownsDraft("command-2"))
        assertFalse(AddDownloadNavigationPolicy.manualSession(AppRoute.Downloads, idFactory = { "manual" }).ownsDraft("command-1"))
    }

    @Test
    fun duplicateDecisionIsBoundToExactSessionUrl() {
        val session = AddDownloadNavigationPolicy.externalSession(
            draftId = "command-1",
            currentPrimaryRoute = AppRoute.Downloads,
            idFactory = { "external-session" },
        ).recordDuplicateDecision("https://example.com/file.bin")
        assertEquals("https://example.com/file.bin", session.duplicateDecisionUrl)
        assertTrue(session.externalDraftStillMatches("command-1", "https://example.com/file.bin"))
        assertFalse(session.externalDraftStillMatches("command-1", "https://example.com/other.bin"))
    }

    @Test
    fun activityActionsHaveStableIdsIndependentOfDisplayText() {
        assertEquals(OperationalActivityActionId.RetryDownload, OperationalActivityActionId.fromLegacyLabel("Retry now"))
        assertEquals(OperationalActivityActionId.OpenRequestContext, OperationalActivityActionId.fromLegacyLabel("Review intake"))
        assertNull(OperationalActivityActionId.fromLegacyLabel("Translated label"))
    }
}
