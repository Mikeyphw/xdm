package com.mikeyphw.xdm.android.model

/** Phase 6 database integrity contracts shared by persistence and source-level tests. */
enum class DownloadGraphTable {
    Downloads,
    DownloadSources,
    Mirrors,
    TransferSegments,
    Checkpoints,
    ChecksumExpectations,
    ChecksumResults,
    VerificationRecords,
    TrustedBlockManifests,
    BackendTasks,
    RecoveryRecords,
    FinalizationJournals,
    MediaCaptures,
    MediaVariants,
    AutomationCommands,
    NotificationRecords,
    DownloadTags,
    Aria2SessionMappings,
    BackendMigrations,
    DestinationClaims,
}

enum class DownloadGraphDeletionDisposition { DeleteGraph, RejectActiveRuntime, RejectDanglingReference }

data class DownloadGraphDeletionPlan(
    val downloadId: String,
    val disposition: DownloadGraphDeletionDisposition,
    val tables: Set<DownloadGraphTable>,
    val reason: String,
) {
    val complete: Boolean get() = DownloadGraphTable.values().toSet().all { it in tables }
}

enum class DurableAutomationCommandStatus { Received, Claimed, Executing, Applied, Failed, Rejected, Duplicate }

data class AutomationCommandTransition(
    val id: String,
    val from: Set<DurableAutomationCommandStatus>,
    val to: DurableAutomationCommandStatus,
    val sideEffectId: String,
    val message: String,
)



enum class MalformedEnumDisposition { DefaultToSafeState, PreserveNull, MarkFailed }

data class MalformedEnumPolicy(
    val columnName: String,
    val fallbackValue: String?,
    val disposition: MalformedEnumDisposition,
)

data class AutomationLifecyclePlan(
    val commandId: String,
    val steps: List<DurableAutomationCommandStatus>,
) {
    val usesDurableExecutionPath: Boolean get() = steps.containsAll(listOf(
        DurableAutomationCommandStatus.Received,
        DurableAutomationCommandStatus.Claimed,
        DurableAutomationCommandStatus.Executing,
    ))
}

data class MediaVariantReplacementPlan(
    val captureId: String,
    val replacementVariantIds: Set<String>,
    val selectedVariantId: String?,
) {
    val selectedVariantStillExists: Boolean get() = selectedVariantId == null || selectedVariantId in replacementVariantIds
}

object DatabaseIntegrityPolicy {
    val downloadGraphTables: Set<DownloadGraphTable> = DownloadGraphTable.values().toSet()
    val durableAutomationStatuses: Set<DurableAutomationCommandStatus> = DurableAutomationCommandStatus.values().toSet()


    val malformedEnumPolicies: Set<MalformedEnumPolicy> = setOf(
        MalformedEnumPolicy("downloads.state", "RecoveryRequired", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("downloads.backend", "Automatic", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("downloads.requestedBackend", "Automatic", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("downloads.backendSelectionReason", "DefaultNative", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("destination_permissions.providerType", "SafTree", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("destination_permissions.status", "Unknown", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("saved_searches.state", null, MalformedEnumDisposition.PreserveNull),
        MalformedEnumPolicy("media_captures.status", "Captured", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("media_captures.kind", "Unknown", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("media_captures.resolutionStatus", "Unresolved", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("media_variants.kind", "Primary", MalformedEnumDisposition.DefaultToSafeState),
        MalformedEnumPolicy("automation_commands.status", "Failed", MalformedEnumDisposition.MarkFailed),
    )

    fun automationLifecycle(commandId: String): AutomationLifecyclePlan = AutomationLifecyclePlan(
        commandId = commandId,
        steps = listOf(
            DurableAutomationCommandStatus.Received,
            DurableAutomationCommandStatus.Claimed,
            DurableAutomationCommandStatus.Executing,
            DurableAutomationCommandStatus.Applied,
        ),
    )

    fun deletionPlan(downloadId: String, activeRuntimeOwner: Boolean = false): DownloadGraphDeletionPlan = when {
        activeRuntimeOwner -> DownloadGraphDeletionPlan(
            downloadId = downloadId,
            disposition = DownloadGraphDeletionDisposition.RejectActiveRuntime,
            tables = emptySet(),
            reason = "Download still has an active runtime owner; cancel and reconcile before deleting the graph.",
        )
        else -> DownloadGraphDeletionPlan(
            downloadId = downloadId,
            disposition = DownloadGraphDeletionDisposition.DeleteGraph,
            tables = downloadGraphTables,
            reason = "Delete download and every known dependent row in one transaction.",
        )
    }

    fun durableStatus(raw: String?): DurableAutomationCommandStatus = runCatching {
        DurableAutomationCommandStatus.valueOf(raw.orEmpty())
    }.getOrElse {
        when (raw) {
            "Accepted" -> DurableAutomationCommandStatus.Received
            "Executed" -> DurableAutomationCommandStatus.Applied
            "Rejected" -> DurableAutomationCommandStatus.Rejected
            "Duplicate" -> DurableAutomationCommandStatus.Duplicate
            else -> DurableAutomationCommandStatus.Failed
        }
    }

    fun structuredPortableSetting(entries: Map<String, String>): String = entries.toSortedMap().entries
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }

    fun parsePortableSetting(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return emptyMap()
        return Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .findAll(trimmed)
            .associate { match -> unescapeJson(match.groupValues[1]) to unescapeJson(match.groupValues[2]) }
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun unescapeJson(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
