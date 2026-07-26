package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.OperationalActivityEvent
import com.mikeyphw.xdm.android.model.OperationalActivityCategory
import java.time.Instant
import java.time.ZoneId

internal data class ActivityWorkspaceMetrics(
    val needsAttention: Int,
    val decisionsWaiting: Int,
    val eventsToday: Int,
)

internal object ActivityWorkspacePlanner {
    fun metrics(
        events: List<OperationalActivityEvent>,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ActivityWorkspaceMetrics {
        val todayStart = Instant.ofEpochMilli(nowEpochMs)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        return ActivityWorkspaceMetrics(
            needsAttention = events.count { it.unresolved },
            decisionsWaiting = events.count { it.source == "queue-policy" && it.unresolved },
            eventsToday = events.count { it.createdAtEpochMs >= todayStart && it.createdAtEpochMs <= nowEpochMs },
        )
    }

    fun forPanel(events: List<OperationalActivityEvent>, panel: ActivityPanel): List<OperationalActivityEvent> = when (panel.normalized(false)) {
        ActivityPanel.Attention -> events.filter { it.unresolved }
        ActivityPanel.Timeline -> events
        else -> events
    }

    fun consequence(event: OperationalActivityEvent): String = when (event.category) {
        OperationalActivityCategory.Policy -> "This download is waiting for the required queue conditions."
        OperationalActivityCategory.Network -> "The transfer will continue when the required connection is available."
        OperationalActivityCategory.Storage -> "The transfer cannot continue safely at the current destination."
        OperationalActivityCategory.Recovery -> "The file may remain incomplete until recovery is resolved."
        OperationalActivityCategory.Verification -> "XDM cannot confirm that the file is intact yet."
        OperationalActivityCategory.Handoff -> "The external link was not added automatically."
        OperationalActivityCategory.Engine -> "The selected download method needs attention before work can continue."
        OperationalActivityCategory.Media -> "The media item needs review before it can be downloaded or played."
        OperationalActivityCategory.Transfer -> if (event.unresolved) "The transfer is paused until this issue is resolved." else "The transfer state changed."
        OperationalActivityCategory.System -> "XDM recorded an app-level event."
    }
}
