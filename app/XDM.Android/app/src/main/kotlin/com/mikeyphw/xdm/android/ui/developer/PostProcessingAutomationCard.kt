package com.mikeyphw.xdm.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationEventStatus
import com.mikeyphw.xdm.android.termux.PostProcessingAutomationStatus

@Composable
internal fun PostProcessingAutomationCard(
    automation: PostProcessingAutomationStatus,
    onEnabledChanged: ((Boolean) -> Unit)?,
    onRetryFailed: () -> Unit,
    onClearEvents: () -> Unit,
) {
    XdmListCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle("Post-processing automation")
                XdmSupportingText(
                    "Typed conversion, verification, cleanup, and file-management rules run after matching download or media events.",
                    maxLines = 3,
                )
            }
            if (onEnabledChanged != null) {
                Switch(checked = automation.enabled, onCheckedChange = onEnabledChanged)
            } else {
                StatusPill(
                    automation.readinessLabel,
                    when {
                        !automation.enabled -> XdmStatusTone.Neutral
                        automation.failedEvents.isNotEmpty() -> XdmStatusTone.Warning
                        else -> XdmStatusTone.Success
                    },
                )
            }
        }
        XdmMetadataText(automation.lastMessage, maxLines = 3)
        automation.enabledRules.take(4).forEach { rule ->
            XdmMetadataText("${rule.name}: ${rule.summary}", maxLines = 2)
        }
        automation.recentEvents.take(4).forEach { event ->
            XdmMetadataText(
                "${event.status.label}: ${event.ruleName} • ${event.subjectLabel}",
                maxLines = 2,
            )
        }
        XdmActionFlowRow {
            Button(
                onClick = onRetryFailed,
                enabled = automation.events.any { it.status == PostProcessingAutomationEventStatus.Failed },
            ) { Text("Retry failed") }
            TextButton(onClick = onClearEvents, enabled = automation.events.isNotEmpty()) { Text("Clear events") }
        }
    }
}
