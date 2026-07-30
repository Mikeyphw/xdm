package com.mikeyphw.xdm.android.model

data class RecoveryStorageDoctorSummary(
    val totalRecords: Int,
    val unresolvedRecords: Int,
    val safeResumeRecords: Int,
    val missingPartialRecords: Int,
    val orphanedArtifactRecords: Int,
    val completedVisibilityRecords: Int,
    val finalizationInterruptedRecords: Int,
    val actionItems: List<RecoveryStorageDoctorAction>,
) {
    val hasUnresolvedWork: Boolean get() = unresolvedRecords > 0
    val headline: String get() = when {
        totalRecords == 0 -> "Recovery is clear"
        completedVisibilityRecords > 0 -> "Storage visibility needs review"
        missingPartialRecords > 0 -> "Some partial data is missing"
        orphanedArtifactRecords > 0 -> "Untracked app-private artifacts found"
        finalizationInterruptedRecords > 0 -> "Finishing was interrupted"
        safeResumeRecords > 0 -> "Some downloads can resume after validation"
        else -> "Recovery review is needed"
    }

    val explanation: String get() = when {
        totalRecords == 0 -> "No orphaned, interrupted, or hidden storage items are waiting for action."
        completedVisibilityRecords > 0 -> "Completed items may need a storage visibility check before the warning can be closed."
        missingPartialRecords > 0 -> "XDM cannot safely resume a missing partial file; retry or forget the stale record after review."
        orphanedArtifactRecords > 0 -> "Untracked artifacts stay untouched until you validate or deliberately forget their recovery record."
        finalizationInterruptedRecords > 0 -> "XDM stopped while promoting data into its destination; validate before retrying or closing the record."
        safeResumeRecords > 0 -> "Reusable partial data was detected; validation runs before any resumed transfer."
        else -> "Review each item and choose a safe action."
    }

    fun exportReport(): String = buildString {
        appendLine("XDM Recovery + Storage Doctor")
        appendLine("Records: $totalRecords")
        appendLine("Unresolved: $unresolvedRecords")
        appendLine("Safe to resume: $safeResumeRecords")
        appendLine("Missing partial files: $missingPartialRecords")
        appendLine("Orphaned artifacts: $orphanedArtifactRecords")
        appendLine("Completed visibility checks: $completedVisibilityRecords")
        appendLine("Interrupted finalization: $finalizationInterruptedRecords")
        appendLine("Actions:")
        actionItems.forEach { action -> appendLine("- ${action.label}: ${action.description}") }
        appendLine("Privacy: raw paths, source URLs, tokens, cookies, and authorization values are not included.")
    }.trimEnd()
}

data class RecoveryStorageDoctorAction(
    val label: String,
    val description: String,
    val destructive: Boolean = false,
)

object RecoveryStorageDoctor {
    fun summarize(records: List<RecoveryRecord>): RecoveryStorageDoctorSummary {
        val safeResume = records.count { it.safeToResume || it.classification == RecoveryClassification.ReadyToResume }
        val missing = records.count { it.classification == RecoveryClassification.MissingPartialFile }
        val orphaned = records.count { it.classification == RecoveryClassification.OrphanedArtifact }
        val completed = records.count { it.classification == RecoveryClassification.CompletionRecovered }
        val finalization = records.count { it.classification == RecoveryClassification.FinalizationInterrupted }
        val actions = buildList {
            if (safeResume > 0 || finalization > 0 || completed > 0) {
                add(RecoveryStorageDoctorAction("Validate all safely", "Checks reusable data and storage visibility before retrying anything."))
            }
            if (missing > 0) {
                add(RecoveryStorageDoctorAction("Retry from source", "Use a fresh request when the partial file is gone."))
                add(RecoveryStorageDoctorAction("Forget stale record", "Clears only the recovery warning after you confirm the file is not recoverable."))
            }
            if (orphaned > 0) {
                add(RecoveryStorageDoctorAction("Validate untracked artifact", "Keeps the artifact untouched until it can be linked or safely ignored."))
            }
            if (completed > 0) {
                add(RecoveryStorageDoctorAction("Re-check storage visibility", "Confirms the completed item was published to shared storage."))
            }
        }.ifEmpty {
            if (records.isEmpty()) emptyList() else listOf(RecoveryStorageDoctorAction("Review records", "Open each item and choose the safest available action."))
        }
        return RecoveryStorageDoctorSummary(
            totalRecords = records.size,
            unresolvedRecords = records.size,
            safeResumeRecords = safeResume,
            missingPartialRecords = missing,
            orphanedArtifactRecords = orphaned,
            completedVisibilityRecords = completed,
            finalizationInterruptedRecords = finalization,
            actionItems = actions,
        )
    }

    fun itemGuidance(record: RecoveryRecord): RecoveryStorageDoctorAction = when (record.classification) {
        RecoveryClassification.ReadyToResume -> RecoveryStorageDoctorAction("Resume after validation", "XDM will validate partial data before continuing the transfer.")
        RecoveryClassification.NeedsRemoteValidation -> RecoveryStorageDoctorAction("Refresh and retry", "The source must be checked again before the transfer can continue.")
        RecoveryClassification.NeedsRepair -> RecoveryStorageDoctorAction("Verify and repair", "Only trusted blocks should be reused; damaged ranges need repair.")
        RecoveryClassification.MissingPartialFile -> RecoveryStorageDoctorAction("Retry from source", "The partial file is missing, so resume is not safe.")
        RecoveryClassification.RemoteFileChanged -> RecoveryStorageDoctorAction("Start fresh", "The source changed; reuse assumptions are unsafe.")
        RecoveryClassification.CompletionRecovered -> RecoveryStorageDoctorAction("Re-check storage visibility", "Confirm the completed file is visible before closing the warning.")
        RecoveryClassification.FinalizationInterrupted -> RecoveryStorageDoctorAction("Validate finalization", "Check the staged data before trying to publish it again.")
        RecoveryClassification.BackendTaskOrphaned -> RecoveryStorageDoctorAction("Validate backend task", "The backend task needs a verified owner before reuse.")
        RecoveryClassification.OrphanedArtifact -> RecoveryStorageDoctorAction("Validate untracked artifact", "XDM will not delete or adopt this artifact without review.")
    }

    fun safeArtifactLabel(record: RecoveryRecord): String = when (record.classification) {
        RecoveryClassification.CompletionRecovered -> "Completed destination candidate"
        RecoveryClassification.FinalizationInterrupted -> "Interrupted destination staging area"
        RecoveryClassification.MissingPartialFile -> "Missing partial artifact"
        RecoveryClassification.OrphanedArtifact -> "Untracked app-private artifact"
        RecoveryClassification.BackendTaskOrphaned -> "Unowned backend task artifact"
        RecoveryClassification.ReadyToResume -> "Reusable partial artifact"
        RecoveryClassification.NeedsRemoteValidation -> "Paused transfer artifact"
        RecoveryClassification.NeedsRepair -> "Repair candidate artifact"
        RecoveryClassification.RemoteFileChanged -> "Stale source artifact"
    }

    fun reportLine(record: RecoveryRecord): String {
        val action = itemGuidance(record)
        val linked = if (record.downloadId == null) "No linked download" else "Linked download present"
        return "${record.classification.safeLabel()} • ${safeArtifactLabel(record)} • ${action.label} • $linked"
    }
}

fun RecoveryClassification.safeLabel(): String = when (this) {
    RecoveryClassification.ReadyToResume -> "Ready to resume"
    RecoveryClassification.NeedsRemoteValidation -> "Needs validation"
    RecoveryClassification.NeedsRepair -> "Needs repair"
    RecoveryClassification.MissingPartialFile -> "Partial file missing"
    RecoveryClassification.RemoteFileChanged -> "Remote file changed"
    RecoveryClassification.CompletionRecovered -> "Completion recovered"
    RecoveryClassification.FinalizationInterrupted -> "Finishing interrupted"
    RecoveryClassification.BackendTaskOrphaned -> "Backend task needs owner"
    RecoveryClassification.OrphanedArtifact -> "Untracked artifact"
}
