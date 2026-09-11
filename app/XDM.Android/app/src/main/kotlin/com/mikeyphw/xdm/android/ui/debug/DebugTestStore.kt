package com.mikeyphw.xdm.android.ui.debug

import com.mikeyphw.xdm.android.BuildConfig
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.DiagnosticBundleMetadata
import com.mikeyphw.xdm.android.model.DiagnosticBundleScan
import com.mikeyphw.xdm.android.model.DiagnosticExportIntegrity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        problemIncidentsText: String = "",
    ): File {
        exportsDirectory.mkdirs()
        val destination = File(exportsDirectory, "xdm-debug-${safeFileName(run.id)}.zip")
        val debugEvents = listOf(run.toDebugEventJsonl(), debugTimelineJsonl)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .let(DiagnosticExportIntegrity::sanitizeJsonl)
        val entries = linkedMapOf(
            "bundle-readme.txt" to (
                "XDM Diagnostics & support v5 / Media Parity01.\n" +
                    "report.txt contains this Diagnostics run; support-report.txt contains the broader redacted runtime/release summary; " +
                    "debug-events.jsonl is the structured event stream; test-results.json is the structured test result set; " +
                    "diagnostic-manifest.json is generated last and binds the exact entry inventory with SHA-256 hashes.\n" +
                    "The exact final ZIP is rescanned before sharing. No automatic upload is performed.\n"
                ).toByteArray(Charsets.UTF_8),
            "report.txt" to sanitizeText(run.toReportText()).toByteArray(Charsets.UTF_8),
            "test-results.json" to DebugRedactor.redactExportLine(run.toJson()).toByteArray(Charsets.UTF_8),
            "debug-events.jsonl" to debugEvents.toByteArray(Charsets.UTF_8),
            "environment.txt" to buildEnvironmentText(run).toByteArray(Charsets.UTF_8),
            "support-report.txt" to sanitizeText(supportReportText).toByteArray(Charsets.UTF_8),
            "redaction-report.txt" to (
                "XDM diagnostics v5 export. The exact final ZIP is rescanned before it can be shared. " +
                    "Cookie, Authorization, token, signature, session/sess, md5, key-like values, and signed URL credentials are redacted locally. " +
                    "No automatic upload is performed.\n"
                ).toByteArray(Charsets.UTF_8),
        )
        if (problemIncidentsText.isNotBlank()) {
            entries["problem-incidents.txt"] = sanitizeText(problemIncidentsText).toByteArray(Charsets.UTF_8)
        }
        DiagnosticExportIntegrity.writeVerifiedZip(
            destination = destination,
            rawEntries = entries,
            metadata = DiagnosticBundleMetadata(
                diagnosticsSchema = 1,
                diagnosticsVersion = "v5",
                appVersion = BuildConfig.VERSION_NAME,
                buildType = BuildConfig.BUILD_TYPE,
                roomSchemaVersion = 22,
                runId = run.id,
                testSummary = run.summaryLabel,
            ),
        )
        return destination
    }

    fun scanExport(file: File): DiagnosticBundleScan = DiagnosticExportIntegrity.scanZip(file)

    private fun pruneOldRuns() {
        runsDirectory
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(retainedRuns)
            .forEach(File::delete)
    }

    private fun buildEnvironmentText(run: DebugTestRun): String = buildString {
        appendLine("Diagnostics version: v5 / Media Parity01")
        appendLine("Room schema: 23")
        appendLine("Product topology: download manager + Live Locator WebView + external browser extension handoff")
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

    private fun sanitizeText(value: String): String = value
        .lineSequence()
        .joinToString("\n") { line -> DebugRedactor.redactExportLine(line) }
        .let { if (it.isBlank()) "" else "$it\n" }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "debug-run" }
        .take(96)
}
