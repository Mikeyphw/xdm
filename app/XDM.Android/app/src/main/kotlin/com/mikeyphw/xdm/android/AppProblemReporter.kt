package com.mikeyphw.xdm.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.FileProblemIncidentStore
import com.mikeyphw.xdm.android.model.ProblemIncident
import com.mikeyphw.xdm.android.model.ProblemIncidentDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

interface ProblemReporterProvider {
    val problemReporter: AppProblemReporter
}

/**
 * Application-owned incident reporter. Problems are redacted and persisted locally before any
 * notification attempt so denied notification permission never loses the diagnostic record.
 */
class AppProblemReporter(
    context: Context,
    private val debugRecorder: DebugEventRecorder,
    rootDirectory: File = File(context.filesDir, "debug-problems"),
) {
    private val store = FileProblemIncidentStore(rootDirectory)
    private val notifications = AppProblemNotifications(context)
    private val _problems = MutableStateFlow(runCatching { store.loadAll() }.getOrDefault(emptyList()))
    val problems: StateFlow<List<ProblemIncident>> = _problems.asStateFlow()

    fun ensureNotificationChannel() {
        runCatching { notifications.ensureChannel() }
    }

    fun report(
        area: DebugArea,
        severity: DebugSeverity = DebugSeverity.Error,
        title: String,
        summary: String,
        suggestedAction: String? = null,
        operationId: String? = null,
        downloadId: String? = null,
        dedupeKey: String? = null,
        notifyUser: Boolean = true,
    ): ProblemIncident? {
        val upsert = runCatching {
            store.upsert(
                ProblemIncidentDraft(
                    area = area,
                    severity = severity,
                    title = title,
                    summary = summary,
                    suggestedAction = suggestedAction,
                    operationId = operationId,
                    downloadId = downloadId,
                    dedupeKey = dedupeKey,
                ),
            )
        }.getOrElse { return null }
        runCatching {
            debugRecorder.record(
                area = area,
                severity = severity,
                action = "problem-reported",
                result = if (upsert.incident.occurrenceCount > 1) "repeated" else "new",
                safeDetails = mapOf(
                    "problemId" to upsert.incident.id,
                    "title" to upsert.incident.title,
                    "occurrences" to upsert.incident.occurrenceCount.toString(),
                ),
                operationId = upsert.incident.operationId,
            )
        }
        if (notifyUser && upsert.shouldNotify && runCatching { notifications.post(upsert.incident) }.getOrDefault(false)) {
            runCatching { store.markNotified(upsert.incident.id) }
        }
        refresh()
        return _problems.value.firstOrNull { it.id == upsert.incident.id } ?: upsert.incident
    }

    fun resolve(problemId: String) {
        val resolved = runCatching { store.resolve(problemId) }.getOrNull()
        if (resolved != null) runCatching { notifications.cancel(problemId) }
        refresh()
    }

    fun reopen(problemId: String) {
        runCatching { store.reopen(problemId) }
        refresh()
    }

    fun clearResolved(): Int {
        val count = runCatching { store.clearResolved() }.getOrDefault(0)
        refresh()
        return count
    }

    fun exportText(): String = runCatching { store.exportText() }.getOrDefault("")

    private fun refresh() {
        runCatching { store.loadAll() }.getOrNull()?.let { _problems.value = it }
    }
}

class AppProblemNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROBLEMS,
                "XDM problems",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Actionable XDM runtime, media, storage, and integration problems"
                setShowBadge(true)
            },
        )
    }

    fun post(problem: ProblemIncident): Boolean {
        if (!problem.isActionable || !canPostNotifications()) return false
        ensureChannel()
        val pendingIntent = reviewPendingIntent(problem)
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("XDM needs attention")
            .setContentText(problem.title)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(problem.title, problem.summary, problem.suggestedAction)
                        .filterNotNull()
                        .filter(String::isNotBlank)
                        .joinToString("\n"),
                ),
            )
            .setSubText(problem.area.name.replace(Regex("([a-z])([A-Z])"), "$1 $2"))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Review", pendingIntent)
            .build()
        manager?.notify(notificationId(problem.id), notification)
        return manager != null
    }

    fun cancel(problemId: String) {
        manager?.cancel(notificationId(problemId))
    }

    private fun reviewPendingIntent(problem: ProblemIncident): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PROBLEM)
            .putExtra(EXTRA_PROBLEM_ID, problem.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            notificationId(problem.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(problemId: String): Int =
        PROBLEM_NOTIFICATION_ID_BASE + ((problemId.hashCode() and Int.MAX_VALUE) % PROBLEM_NOTIFICATION_ID_RANGE)

    companion object {
        const val CHANNEL_PROBLEMS = "xdm_runtime_problems"
        const val ACTION_OPEN_PROBLEM = "com.mikeyphw.xdm.android.action.OPEN_PROBLEM"
        const val EXTRA_PROBLEM_ID = "problem_id"
        // TransferSystemIdRegistry owns 20_000..900_000_000. Keep problem notifications disjoint.
        private const val PROBLEM_NOTIFICATION_ID_BASE = 1_000_000_000
        private const val PROBLEM_NOTIFICATION_ID_RANGE = 900_000_000
    }
}
