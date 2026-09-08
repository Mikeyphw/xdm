package com.mikeyphw.xdm.android.ui.debug

import com.mikeyphw.xdm.android.model.DebugRedactor
import java.util.Locale

/** Design-plan Debug Center status model. Pending and Running are first-class live states. */
enum class DebugTestStatus(val label: String) {
    Pending("Pending"),
    Running("Running"),
    Passed("Passed"),
    Warning("Warning"),
    Failed("Failed"),
    Skipped("Skipped"),
}

enum class DebugTestGroup(val label: String) {
    BasicHealth("Basic Health"),
    Downloads("Downloads"),
    Browser("Browser"),
    Media("Media"),
    Backends("Backends"),
    Storage("Storage"),
    Privacy("Privacy"),
}

data class DebugTestDefinition(
    val id: String,
    val group: DebugTestGroup,
    val name: String,
    val description: String,
)

val DebugTestDefinition.displayTitle: String
    get() = name

val DebugTestResult.displayTitle: String
    get() = name

data class DebugTestResult(
    val testId: String,
    val groupId: String,
    val name: String,
    val status: DebugTestStatus,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val summary: String,
    val details: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val suggestedAction: String? = null,
) {
    val statusRank: Int
        get() = when (status) {
            DebugTestStatus.Failed -> 0
            DebugTestStatus.Warning -> 1
            DebugTestStatus.Running -> 2
            DebugTestStatus.Pending -> 3
            DebugTestStatus.Skipped -> 4
            DebugTestStatus.Passed -> 5
        }

    fun toReportText(): String = buildString {
        appendLine("[$groupId] $name")
        appendLine("Status: ${status.label}")
        appendLine("Duration: ${durationMs}ms")
        appendLine("Summary: ${DebugRedactor.redactText(summary)}")
        errorCode?.takeIf(String::isNotBlank)?.let { appendLine("Error: ${DebugRedactor.redactText(it)}") }
        suggestedAction?.takeIf(String::isNotBlank)?.let { appendLine("Next: ${DebugRedactor.redactText(it)}") }
        if (details.isNotEmpty()) {
            appendLine("Details:")
            DebugRedactor.redactDetails(details).forEach { (key, value) ->
                appendLine("- $key: $value")
            }
        }
    }.trimEnd()

    fun toJson(): String = buildString {
        val redactedDetails = DebugRedactor.redactDetails(details)
        append('{')
        appendJson("testId", testId)
        append(',')
        appendJson("groupId", groupId)
        append(',')
        appendJson("name", name)
        append(',')
        appendJson("status", status.name)
        append(',')
        append("\"startedAtEpochMs\":").append(startedAtEpochMs)
        append(',')
        append("\"durationMs\":").append(durationMs)
        append(',')
        appendJson("summary", DebugRedactor.redactText(summary))
        append(',')
        appendJson("errorCode", errorCode?.let(DebugRedactor::redactText))
        append(',')
        appendJson("suggestedAction", suggestedAction?.let(DebugRedactor::redactText))
        append(',')
        append("\"details\":{")
        redactedDetails.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendJson(key, value)
        }
        append("}}")
    }
}

data class DebugTestRun(
    val id: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val selectedTestIds: List<String>,
    val results: List<DebugTestResult>,
) {
    val total: Int get() = results.size
    val passed: Int get() = results.count { it.status == DebugTestStatus.Passed }
    val warnings: Int get() = results.count { it.status == DebugTestStatus.Warning }
    val failed: Int get() = results.count { it.status == DebugTestStatus.Failed }
    val skipped: Int get() = results.count { it.status == DebugTestStatus.Skipped }
    val running: Int get() = results.count { it.status == DebugTestStatus.Running }
    val pending: Int get() = results.count { it.status == DebugTestStatus.Pending }
    val complete: Boolean get() = finishedAtEpochMs != null && running == 0 && pending == 0

    val summaryLabel: String
        get() = if (results.isEmpty()) {
            "No test run yet"
        } else {
            "$total tests • $passed passed • $warnings warnings • $failed failed • $skipped skipped"
        }

    val progressFraction: Float
        get() = if (total == 0) 0f else ((total - pending - running).coerceAtLeast(0).toFloat() / total.toFloat()).coerceIn(0f, 1f)

    fun failureIds(): Set<String> = results
        .filter { it.status == DebugTestStatus.Failed || it.status == DebugTestStatus.Warning }
        .mapTo(linkedSetOf()) { it.testId }

    fun sortedResults(): List<DebugTestResult> = results.sortedWith(
        compareBy<DebugTestResult> { it.statusRank }
            .thenBy { it.groupId }
            .thenBy { it.name },
    )

    fun toReportText(): String = buildString {
        appendLine("XDM Debug Center Report")
        appendLine("Run ID: $id")
        appendLine("Started: $startedAtEpochMs")
        appendLine("Finished: ${finishedAtEpochMs ?: "running"}")
        appendLine("Summary: $summaryLabel")
        appendLine("Selected tests: ${selectedTestIds.joinToString(", ")}")
        appendLine()
        appendLine("Failures and warnings")
        val important = sortedResults().filter { it.status == DebugTestStatus.Failed || it.status == DebugTestStatus.Warning }
        if (important.isEmpty()) {
            appendLine("None")
        } else {
            important.forEach { result ->
                appendLine()
                appendLine(result.toReportText())
            }
        }
        appendLine()
        appendLine("All results")
        sortedResults().forEach { result ->
            appendLine()
            appendLine(result.toReportText())
        }
        appendLine()
        appendLine("Privacy: Debug Center exports are redacted locally. No automatic upload is performed.")
    }.trimEnd()

    fun toJson(): String = buildString {
        append('{')
        appendJson("id", id)
        append(',')
        append("\"startedAtEpochMs\":").append(startedAtEpochMs)
        append(',')
        append("\"finishedAtEpochMs\":").append(finishedAtEpochMs ?: "null")
        append(',')
        append("\"selectedTestIds\":[")
        selectedTestIds.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendJsonValue(value)
        }
        append("],")
        append("\"results\":[")
        results.forEachIndexed { index, result ->
            if (index > 0) append(',')
            append(result.toJson())
        }
        append("]}")
    }

    fun toDebugEventJsonl(): String = results.joinToString("\n") { result ->
        com.mikeyphw.xdm.android.model.DebugEvent(
            sessionId = id,
            operationId = result.testId,
            parentOperationId = id,
            timestampMillis = result.startedAtEpochMs,
            area = com.mikeyphw.xdm.android.model.DebugArea.Validation,
            severity = when (result.status) {
                DebugTestStatus.Failed -> com.mikeyphw.xdm.android.model.DebugSeverity.Error
                DebugTestStatus.Warning -> com.mikeyphw.xdm.android.model.DebugSeverity.Warning
                else -> com.mikeyphw.xdm.android.model.DebugSeverity.Info
            },
            action = "debug-test:${result.testId}",
            result = result.status.name.lowercase(Locale.US),
            safeDetails = buildMap {
                put("name", result.name)
                put("group", result.groupId)
                put("summary", result.summary)
                result.errorCode?.let { put("errorCode", it) }
            },
        ).toJsonLine()
    }.trimEnd()
}

internal fun StringBuilder.appendJson(key: String, value: String?) {
    append('"').append(DebugRedactor.jsonEscape(key)).append("\":")
    if (value == null) {
        append("null")
    } else {
        appendJsonValue(value)
    }
}

internal fun StringBuilder.appendJsonValue(value: String) {
    append('"').append(DebugRedactor.jsonEscape(value)).append('"')
}
