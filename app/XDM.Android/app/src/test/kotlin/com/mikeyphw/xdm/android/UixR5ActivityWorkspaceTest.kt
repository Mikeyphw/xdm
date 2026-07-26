package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.OperationalActivityCategory
import com.mikeyphw.xdm.android.model.OperationalActivityEvent
import com.mikeyphw.xdm.android.model.OperationalActivitySeverity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR5ActivityWorkspaceTest {
    @Test
    fun metricsCountAttentionDecisionsAndOnlyEventsFromToday() {
        val now = 1_775_000_000_000L
        val zone = ZoneId.of("UTC")
        val today = now - 60_000L
        val yesterday = now - 24L * 60L * 60L * 1_000L
        val events = listOf(
            event("policy", today, unresolved = true, source = "queue-policy"),
            event("network", today, unresolved = true, category = OperationalActivityCategory.Network),
            event("done", today, severity = OperationalActivitySeverity.Success),
            event("old", yesterday, unresolved = true),
        )

        val metrics = ActivityWorkspacePlanner.metrics(events, nowEpochMs = now, zoneId = zone)

        assertEquals(3, metrics.needsAttention)
        assertEquals(1, metrics.decisionsWaiting)
        assertEquals(3, metrics.eventsToday)
    }

    @Test
    fun primaryPanelsStayFocusedWhileLegacyPanelsNormalizeSafely() {
        assertEquals(ActivityPanel.Attention, ActivityPanel.Overview.normalized(false))
        assertEquals(ActivityPanel.Attention, ActivityPanel.Diagnostics.normalized(false))
        assertEquals(ActivityPanel.Diagnostics, ActivityPanel.Diagnostics.normalized(true))
        assertEquals(listOf(ActivityPanel.Attention, ActivityPanel.Timeline), ActivityPanel.primaryPanels)
        assertFalse(ActivityPanel.Queues.isPrimary)
        assertTrue(ActivityPanel.Queues.isManage)
    }

    @Test
    fun attentionFiltersUnresolvedItemsAndConsequencesUsePlainLanguage() {
        val waiting = event("waiting", 10, unresolved = true, category = OperationalActivityCategory.Storage)
        val completed = event("completed", 20, unresolved = false, severity = OperationalActivitySeverity.Success)

        assertEquals(listOf("waiting"), ActivityWorkspacePlanner.forPanel(listOf(waiting, completed), ActivityPanel.Attention).map { it.id })
        assertEquals(listOf("waiting", "completed"), ActivityWorkspacePlanner.forPanel(listOf(waiting, completed), ActivityPanel.Timeline).map { it.id })
        val consequence = ActivityWorkspacePlanner.consequence(waiting)
        assertTrue(consequence.contains("cannot continue safely", ignoreCase = true))
        assertFalse(consequence.contains("ledger", ignoreCase = true))
        assertFalse(consequence.contains("telemetry", ignoreCase = true))
    }

    private fun event(
        id: String,
        createdAt: Long,
        unresolved: Boolean = false,
        source: String = "runtime",
        category: OperationalActivityCategory = OperationalActivityCategory.Transfer,
        severity: OperationalActivitySeverity = OperationalActivitySeverity.Warning,
    ) = OperationalActivityEvent(
        id = id,
        category = category,
        severity = severity,
        title = "Event $id",
        detail = "Plain-language detail",
        createdAtEpochMs = createdAt,
        unresolved = unresolved,
        source = source,
    )
}
