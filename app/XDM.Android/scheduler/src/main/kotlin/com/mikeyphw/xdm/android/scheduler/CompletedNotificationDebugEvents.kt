package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEvent
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.DebugSeverity

object CompletedNotificationDebugEvents {
    fun fallback(
        downloadId: String?,
        reason: String,
        uri: String? = null,
        mimeType: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
    ): DebugEvent = DebugEvent(
        sessionId = "notification-open",
        timestampMillis = timestampMillis,
        area = DebugArea.FileOpen,
        severity = DebugSeverity.Warning,
        action = "completed-notification-open",
        result = "fallback-to-xdm-details",
        safeDetails = mapOf(
            "downloadFingerprint" to downloadId.orEmpty().takeIf(String::isNotBlank)?.let(DebugRedactor::fingerprint).orEmpty(),
            "reason" to reason,
            "uri" to uri.orEmpty(),
            "mimeType" to mimeType.orEmpty(),
        ),
    )
}
