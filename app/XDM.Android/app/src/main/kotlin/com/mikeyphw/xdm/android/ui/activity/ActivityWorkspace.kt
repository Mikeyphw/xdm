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

internal data class ActivityEventGroup(
    val key: String,
    val events: List<OperationalActivityEvent>,
) {
    val representative: OperationalActivityEvent get() = events.maxByOrNull(OperationalActivityEvent::createdAtEpochMs)!!
    val latestAtEpochMs: Long get() = representative.createdAtEpochMs
    val affectedDownloads: Int get() = events.mapNotNull(OperationalActivityEvent::downloadId).distinct().size
    val fileNames: List<String> get() = events.mapNotNull(OperationalActivityEvent::fileName).filter(String::isNotBlank).distinct()
    val summary: String?
        get() = when {
            affectedDownloads > 1 -> "$affectedDownloads downloads affected"
            events.size > 1 -> "${events.size} similar events"
            else -> null
        }

    val fileSummary: String?
        get() = when {
            fileNames.isEmpty() -> null
            fileNames.size == 1 -> fileNames.first()
            else -> "${fileNames.first()} +${fileNames.size - 1} more"
        }
}

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

    fun groupsForPanel(events: List<OperationalActivityEvent>, panel: ActivityPanel): List<ActivityEventGroup> =
        forPanel(events, panel)
            .groupBy { event ->
                listOf(
                    event.category.name,
                    event.severity.name,
                    event.title.trim(),
                    event.detail.trim(),
                    event.actionLabel.orEmpty(),
                    event.unresolved.toString(),
                    event.source,
                ).joinToString("|")
            }
            .map { (key, grouped) -> ActivityEventGroup(key, grouped.sortedByDescending(OperationalActivityEvent::createdAtEpochMs)) }
            .sortedByDescending(ActivityEventGroup::latestAtEpochMs)

    fun consequence(event: OperationalActivityEvent): String = when (event.category) {
        OperationalActivityCategory.Policy -> "This download is waiting for the required queue conditions."
        OperationalActivityCategory.Network -> "The transfer will continue when the required connection is available."
        OperationalActivityCategory.Storage -> "The transfer cannot continue safely at the current destination."
        OperationalActivityCategory.Recovery -> "The file may remain incomplete until recovery is resolved."
        OperationalActivityCategory.Verification -> "XDM cannot confirm that the file is intact yet."
        OperationalActivityCategory.Handoff -> "The external link was not added automatically."
        OperationalActivityCategory.Engine -> "The selected download method needs action before work can continue."
        OperationalActivityCategory.Media -> "The media item needs review before it can be downloaded or played."
        OperationalActivityCategory.Transfer -> if (event.unresolved) "The transfer is paused until this issue is resolved." else "The transfer state changed."
        OperationalActivityCategory.System -> "XDM recorded an app-level event."
    }
}
