package com.mikeyphw.xdm.android.ui.debug

import com.mikeyphw.xdm.android.model.DebugRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DebugTestStore(
    private val rootDirectory: File,
    private val retainedRuns: Int = 10,
) {
    private val runsDirectory: File get() = File(rootDirectory, "runs")
    private val exportsDirectory: File get() = File(rootDirectory, "exports")

    fun saveRun(run: DebugTestRun) {
        runsDirectory.mkdirs()
        val file = File(runsDirectory, safeFileName(run.id) + ".json")
        file.writeText(run.toJson(), Charsets.UTF_8)
        pruneOldRuns()
    }

    fun loadRuns(): List<DebugTestRun> {
        runsDirectory.mkdirs()
        return runsDirectory
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .mapNotNull { file -> runCatching { parseRun(file.readText(Charsets.UTF_8)) }.getOrNull() }
            .take(retainedRuns)
    }

    fun exportRunZip(
        run: DebugTestRun,
        supportReportText: String,
        debugTimelineJsonl: String,
    ): File {
        exportsDirectory.mkdirs()
        val destination = File(exportsDirectory, safeFileName(run.id) + ".zip")
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.writeEntry("report.txt", run.toReportText())
            zip.writeEntry("test-results.json", run.toJson())
            zip.writeEntry("debug-events.jsonl", listOf(run.toDebugEventJsonl(), debugTimelineJsonl).filter { it.isNotBlank() }.joinToString("\n"))
            zip.writeEntry("environment.txt", buildEnvironmentText(run))
            zip.writeEntry("support-report.txt", DebugRedactor.redactExportLine(supportReportText))
            zip.writeEntry(
                "redaction-report.txt",
                "XDM Debug Center v4 export. Cookie, Authorization, token, signature, session, key-like values, and URL query secrets are redacted locally before export. No automatic upload is performed.\n",
            )
        }
        return destination
    }

    private fun pruneOldRuns() {
        runsDirectory
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(retainedRuns)
            .forEach(File::delete)
    }

    private fun buildEnvironmentText(run: DebugTestRun): String = buildString {
        appendLine("Debug Center version: v4")
        appendLine("Run ID: ${run.id}")
        appendLine("Started: ${run.startedAtEpochMs}")
        appendLine("Finished: ${run.finishedAtEpochMs ?: "running"}")
        appendLine("Result summary: ${run.summaryLabel}")
        appendLine("Export type: zip")
        appendLine("Privacy: redacted local export")
    }

    private fun parseRun(text: String): DebugTestRun {
        val json = JSONObject(text)
        val selected = json.optJSONArray("selectedTestIds")?.toStringList().orEmpty()
        val results = json.optJSONArray("results")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parseResult)
            }
        }.orEmpty()
        return DebugTestRun(
            id = json.optString("id"),
            startedAtEpochMs = json.optLong("startedAtEpochMs"),
            finishedAtEpochMs = if (json.isNull("finishedAtEpochMs")) null else json.optLong("finishedAtEpochMs"),
            selectedTestIds = selected,
            results = results,
        )
    }

    private fun parseResult(json: JSONObject): DebugTestResult {
        val details = json.optJSONObject("details")?.let { detailsJson ->
            detailsJson.keys().asSequence().associateWith { key -> detailsJson.optString(key) }
        }.orEmpty()
        return DebugTestResult(
            testId = json.optString("testId"),
            groupId = json.optString("groupId"),
            name = json.optString("name"),
            status = DebugTestStatus.entries.firstOrNull { it.name == json.optString("status") } ?: DebugTestStatus.Skipped,
            startedAtEpochMs = json.optLong("startedAtEpochMs"),
            durationMs = json.optLong("durationMs"),
            summary = json.optString("summary"),
            details = details,
            errorCode = json.optString("errorCode").takeIf(String::isNotBlank),
            suggestedAction = json.optString("suggestedAction").takeIf(String::isNotBlank),
        )
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

    private fun ZipOutputStream.writeEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        value.lineSequence().forEach { line ->
            write((DebugRedactor.redactExportLine(line) + "\n").toByteArray(Charsets.UTF_8))
        }
        closeEntry()
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "debug-run" }
        .take(96)
}
