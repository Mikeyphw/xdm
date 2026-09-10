package com.mikeyphw.xdm.android.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/** Durable, privacy-safe problem surfaced by XDM's runtime observability layer. */
data class ProblemIncident(
    val id: String,
    val area: DebugArea,
    val severity: DebugSeverity,
    val title: String,
    val summary: String,
    val suggestedAction: String? = null,
    val operationId: String? = null,
    val downloadId: String? = null,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val occurrenceCount: Int = 1,
    val resolved: Boolean = false,
    val lastNotifiedEpochMs: Long? = null,
) {
    val isActionable: Boolean get() = !resolved && severity in setOf(DebugSeverity.Warning, DebugSeverity.Error)

    fun toExportText(): String = buildString {
        appendLine("id=$id")
        appendLine("area=${area.name}")
        appendLine("severity=${severity.name}")
        appendLine("title=${DebugRedactor.redactText(title)}")
        appendLine("summary=${DebugRedactor.redactText(summary)}")
        suggestedAction?.takeIf(String::isNotBlank)?.let { appendLine("suggestedAction=${DebugRedactor.redactText(it)}") }
        operationId?.takeIf(String::isNotBlank)?.let { appendLine("operationId=${DebugRedactor.redactText(it)}") }
        downloadId?.takeIf(String::isNotBlank)?.let { appendLine("downloadId=${DebugRedactor.redactText(it)}") }
        appendLine("firstSeenEpochMs=$firstSeenEpochMs")
        appendLine("lastSeenEpochMs=$lastSeenEpochMs")
        appendLine("occurrenceCount=$occurrenceCount")
        appendLine("resolved=$resolved")
    }.trimEnd()
}

data class ProblemIncidentDraft(
    val area: DebugArea,
    val severity: DebugSeverity = DebugSeverity.Error,
    val title: String,
    val summary: String,
    val suggestedAction: String? = null,
    val operationId: String? = null,
    val downloadId: String? = null,
    val dedupeKey: String? = null,
)

data class ProblemIncidentUpsert(
    val incident: ProblemIncident,
    val shouldNotify: Boolean,
)

/**
 * Small file-backed incident store intentionally independent of Room/download persistence.
 * A diagnostics failure must never be able to block or migrate the transfer database.
 */
class FileProblemIncidentStore(
    private val rootDirectory: File,
    private val retainedIncidents: Int = 80,
    private val notificationCooldownMs: Long = 15L * 60L * 1000L,
) {
    private val lock = Any()
    private val incidentsDirectory: File get() = File(rootDirectory, "incidents")

    fun loadAll(): List<ProblemIncident> = synchronized(lock) { loadAllLocked() }

    fun upsert(draft: ProblemIncidentDraft, nowEpochMs: Long = System.currentTimeMillis()): ProblemIncidentUpsert = synchronized(lock) {
        incidentsDirectory.mkdirs()
        val sanitized = sanitize(draft)
        val id = incidentIdFor(sanitized)
        val existing = loadLocked(id)
        val incident = ProblemIncident(
            id = id,
            area = sanitized.area,
            severity = sanitized.severity,
            title = sanitized.title,
            summary = sanitized.summary,
            suggestedAction = sanitized.suggestedAction,
            operationId = sanitized.operationId,
            downloadId = sanitized.downloadId,
            firstSeenEpochMs = existing?.firstSeenEpochMs ?: nowEpochMs,
            lastSeenEpochMs = nowEpochMs,
            occurrenceCount = (existing?.occurrenceCount ?: 0) + 1,
            resolved = false,
            lastNotifiedEpochMs = existing?.lastNotifiedEpochMs,
        )
        writeLocked(incident)
        pruneLocked()
        val shouldNotify = incident.severity in setOf(DebugSeverity.Warning, DebugSeverity.Error) &&
            (existing?.lastNotifiedEpochMs == null || nowEpochMs - existing.lastNotifiedEpochMs >= notificationCooldownMs)
        ProblemIncidentUpsert(incident, shouldNotify)
    }

    fun markNotified(id: String, nowEpochMs: Long = System.currentTimeMillis()): ProblemIncident? = synchronized(lock) {
        val current = loadLocked(id) ?: return@synchronized null
        current.copy(lastNotifiedEpochMs = nowEpochMs).also(::writeLocked)
    }

    fun resolve(id: String): ProblemIncident? = synchronized(lock) {
        val current = loadLocked(id) ?: return@synchronized null
        current.copy(resolved = true).also(::writeLocked)
    }

    fun reopen(id: String): ProblemIncident? = synchronized(lock) {
        val current = loadLocked(id) ?: return@synchronized null
        current.copy(resolved = false).also(::writeLocked)
    }

    fun clearResolved(): Int = synchronized(lock) {
        val resolved = loadAllLocked().filter(ProblemIncident::resolved)
        resolved.forEach { fileFor(it.id).delete() }
        resolved.size
    }

    fun exportText(): String = synchronized(lock) {
        loadAllLocked().joinToString("\n\n---\n\n") { it.toExportText() }
    }

    private fun sanitize(draft: ProblemIncidentDraft): ProblemIncidentDraft = draft.copy(
        severity = if (draft.severity == DebugSeverity.Trace) DebugSeverity.Info else draft.severity,
        title = DebugRedactor.redactText(draft.title).ifBlank { "XDM problem" }.take(120),
        summary = DebugRedactor.redactText(draft.summary).ifBlank { "No additional details were recorded." }.take(512),
        suggestedAction = draft.suggestedAction?.let(DebugRedactor::redactText)?.takeIf(String::isNotBlank)?.take(320),
        operationId = draft.operationId?.let(DebugRedactor::redactText)?.takeIf(String::isNotBlank)?.take(128),
        downloadId = draft.downloadId?.let(DebugRedactor::redactText)?.takeIf(String::isNotBlank)?.take(128),
        dedupeKey = draft.dedupeKey?.let(DebugRedactor::redactText)?.takeIf(String::isNotBlank)?.take(160),
    )

    private fun incidentIdFor(draft: ProblemIncidentDraft): String {
        val seed = listOf(
            draft.area.name,
            draft.severity.name,
            draft.dedupeKey ?: draft.title.lowercase(),
            draft.downloadId.orEmpty(),
        ).joinToString("|")
        return "problem-${DebugRedactor.fingerprint(seed)}"
    }

    private fun loadAllLocked(): List<ProblemIncident> {
        incidentsDirectory.mkdirs()
        return incidentsDirectory
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".properties") }
            .orEmpty()
            .mapNotNull(::readLocked)
            .sortedWith(compareByDescending<ProblemIncident> { !it.resolved }.thenByDescending { it.lastSeenEpochMs })
            .take(retainedIncidents)
    }

    private fun loadLocked(id: String): ProblemIncident? = readLocked(fileFor(id))

    private fun readLocked(file: File): ProblemIncident? {
        if (!file.isFile) return null
        val properties = Properties()
        return runCatching {
            FileInputStream(file).use(properties::load)
            ProblemIncident(
                id = properties.getProperty("id")?.takeIf(String::isNotBlank) ?: return@runCatching null,
                area = DebugArea.entries.firstOrNull { it.name == properties.getProperty("area") } ?: DebugArea.Validation,
                severity = DebugSeverity.entries.firstOrNull { it.name == properties.getProperty("severity") } ?: DebugSeverity.Warning,
                title = properties.getProperty("title").orEmpty(),
                summary = properties.getProperty("summary").orEmpty(),
                suggestedAction = properties.getProperty("suggestedAction")?.takeIf(String::isNotBlank),
                operationId = properties.getProperty("operationId")?.takeIf(String::isNotBlank),
                downloadId = properties.getProperty("downloadId")?.takeIf(String::isNotBlank),
                firstSeenEpochMs = properties.getProperty("firstSeenEpochMs")?.toLongOrNull() ?: 0L,
                lastSeenEpochMs = properties.getProperty("lastSeenEpochMs")?.toLongOrNull() ?: 0L,
                occurrenceCount = properties.getProperty("occurrenceCount")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                resolved = properties.getProperty("resolved")?.toBooleanStrictOrNull() ?: false,
                lastNotifiedEpochMs = properties.getProperty("lastNotifiedEpochMs")?.toLongOrNull(),
            )
        }.getOrNull()
    }

    private fun writeLocked(incident: ProblemIncident) {
        incidentsDirectory.mkdirs()
        val destination = fileFor(incident.id)
        val temporary = File(incidentsDirectory, ".${incident.id}.tmp")
        val properties = Properties().apply {
            setProperty("id", incident.id)
            setProperty("area", incident.area.name)
            setProperty("severity", incident.severity.name)
            setProperty("title", incident.title)
            setProperty("summary", incident.summary)
            setProperty("suggestedAction", incident.suggestedAction.orEmpty())
            setProperty("operationId", incident.operationId.orEmpty())
            setProperty("downloadId", incident.downloadId.orEmpty())
            setProperty("firstSeenEpochMs", incident.firstSeenEpochMs.toString())
            setProperty("lastSeenEpochMs", incident.lastSeenEpochMs.toString())
            setProperty("occurrenceCount", incident.occurrenceCount.toString())
            setProperty("resolved", incident.resolved.toString())
            setProperty("lastNotifiedEpochMs", incident.lastNotifiedEpochMs?.toString().orEmpty())
        }
        FileOutputStream(temporary).use { properties.store(it, null) }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun pruneLocked() {
        val all = incidentsDirectory
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".properties") }
            .orEmpty()
            .mapNotNull(::readLocked)
            .sortedWith(compareByDescending<ProblemIncident> { !it.resolved }.thenByDescending { it.lastSeenEpochMs })
        all.drop(retainedIncidents).forEach { fileFor(it.id).delete() }
    }

    private fun fileFor(id: String): File = File(incidentsDirectory, "$id.properties")
}
