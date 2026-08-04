package com.mikeyphw.xdm.android.scheduler

import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.DurableQueueCommandResult
import com.mikeyphw.xdm.android.model.ImmediateReevaluationEvent
import com.mikeyphw.xdm.android.model.NotificationActionModel
import com.mikeyphw.xdm.android.model.QueueDeletionDisposition
import com.mikeyphw.xdm.android.model.QueueDeletionPlan
import com.mikeyphw.xdm.android.model.QueueSlotReservation
import com.mikeyphw.xdm.android.model.QueueStateMachinePlanner
import com.mikeyphw.xdm.android.model.RecoveryArtifactIdentity
import com.mikeyphw.xdm.android.model.RecoveryExecutionPlan
import com.mikeyphw.xdm.android.model.RecoveryOperation
import com.mikeyphw.xdm.android.model.RecoveryOperationOutcome
import com.mikeyphw.xdm.android.model.SystemExecutionOwner
import com.mikeyphw.xdm.android.model.SystemStopReasonRecord
import com.mikeyphw.xdm.android.model.TerminalNotificationRecord
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Exposes the Phase 4 durable coordinator to services, receivers, workers, and notifications. */
interface QueueSchedulingRecoveryProvider {
    val queueSchedulingRecoveryCoordinator: QueueSchedulingRecoveryCoordinator
}

/**
 * Phase 4 durable contracts for queue/scheduler/recovery/notification state.
 *
 * The app wires this interface to FileBackedQueueSchedulingRecoveryStore, not the in-memory test
 * store. All production control paths write evidence before they call transfer runtime actions so
 * a killed process can explain what command, stop reason, recovery plan, or terminal notification
 * had already been observed.
 */
interface QueueSchedulingRecoveryStore {
    fun saveQueueCommand(result: DurableQueueCommandResult)
    fun queueCommands(): List<DurableQueueCommandResult>
    fun saveQueueReservation(reservation: QueueSlotReservation)
    fun queueReservations(): List<QueueSlotReservation>
    fun saveSystemStopReason(record: SystemStopReasonRecord)
    fun systemStopReasons(downloadId: String): List<SystemStopReasonRecord>
    fun saveImmediateReevaluation(event: ImmediateReevaluationEvent)
    fun pendingImmediateReevaluations(): List<ImmediateReevaluationEvent>
    fun saveRecoveryPlan(plan: RecoveryExecutionPlan)
    fun recoveryPlan(downloadId: String, attemptGeneration: Long): RecoveryExecutionPlan?
    fun saveTerminalNotification(record: TerminalNotificationRecord): Boolean
    fun terminalNotifications(): List<TerminalNotificationRecord>
    fun saveQueueDeletion(plan: QueueDeletionPlan)
    fun queueDeletionPlans(): List<QueueDeletionPlan>
}

class InMemoryQueueSchedulingRecoveryStore : QueueSchedulingRecoveryStore {
    private val commands = mutableListOf<DurableQueueCommandResult>()
    private val reservations = LinkedHashMap<String, QueueSlotReservation>()
    private val stopReasons = mutableListOf<SystemStopReasonRecord>()
    private val reevaluations = LinkedHashMap<String, ImmediateReevaluationEvent>()
    private val recoveryPlans = ConcurrentHashMap<String, RecoveryExecutionPlan>()
    private val terminalNotifications = LinkedHashMap<String, TerminalNotificationRecord>()
    private val queueDeletionPlans = mutableListOf<QueueDeletionPlan>()

    override fun saveQueueCommand(result: DurableQueueCommandResult) { commands += result }
    override fun queueCommands(): List<DurableQueueCommandResult> = commands.toList()
    override fun saveQueueReservation(reservation: QueueSlotReservation) { reservations[reservation.claimKey] = reservation }
    override fun queueReservations(): List<QueueSlotReservation> = reservations.values.toList()
    override fun saveSystemStopReason(record: SystemStopReasonRecord) { stopReasons += record }
    override fun systemStopReasons(downloadId: String): List<SystemStopReasonRecord> = stopReasons.filter { it.downloadId == downloadId }
    override fun saveImmediateReevaluation(event: ImmediateReevaluationEvent) { reevaluations[event.coalesceKey] = event }
    override fun pendingImmediateReevaluations(): List<ImmediateReevaluationEvent> = reevaluations.values.toList()
    override fun saveRecoveryPlan(plan: RecoveryExecutionPlan) { recoveryPlans[planKey(plan.downloadId, plan.attemptGeneration)] = plan }
    override fun recoveryPlan(downloadId: String, attemptGeneration: Long): RecoveryExecutionPlan? = recoveryPlans[planKey(downloadId, attemptGeneration)]
    override fun saveTerminalNotification(record: TerminalNotificationRecord): Boolean = terminalNotifications.putIfAbsent(record.idempotencyKey, record) == null
    override fun terminalNotifications(): List<TerminalNotificationRecord> = terminalNotifications.values.toList()
    override fun saveQueueDeletion(plan: QueueDeletionPlan) { queueDeletionPlans += plan }
    override fun queueDeletionPlans(): List<QueueDeletionPlan> = queueDeletionPlans.toList()
    private fun planKey(downloadId: String, generation: Long): String = "$downloadId:$generation"
}

/** App-private, append-only, fsynced Phase 4 evidence store. */
class FileBackedQueueSchedulingRecoveryStore(private val root: File) : QueueSchedulingRecoveryStore {
    private val lock = Any()

    init { root.mkdirs() }

    override fun saveQueueCommand(result: DurableQueueCommandResult) = append(
        "queue-commands.log",
        listOf(
            "command",
            result.command.name,
            result.generation.toString(),
            result.outcome.name,
            result.affectedDownloadIds.joinToString(","),
            result.failedDownloadIds.joinToString(","),
            result.durableHold?.id.orEmpty(),
            (result.durableHold?.blocksNewStarts == true).toString(),
            result.durableHold?.coveredStates.orEmpty().joinToString(",") { it.name },
            result.message,
        ),
    )

    override fun queueCommands(): List<DurableQueueCommandResult> = readLines("queue-commands.log").mapNotNull { fields ->
        runCatching {
            DurableQueueCommandResult(
                command = com.mikeyphw.xdm.android.model.QueueControlCommand.valueOf(fields[1]),
                generation = fields[2].toLong(),
                outcome = com.mikeyphw.xdm.android.model.QueueControlOutcome.valueOf(fields[3]),
                affectedDownloadIds = csv(fields[4]),
                failedDownloadIds = csv(fields[5]),
                durableHold = fields[6].takeIf(String::isNotBlank)?.let { id ->
                    com.mikeyphw.xdm.android.model.DurableQueueHold(
                        id = id,
                        queueId = null,
                        command = com.mikeyphw.xdm.android.model.QueueControlCommand.valueOf(fields[1]),
                        generation = fields[2].toLong(),
                        createdAtEpochMs = 0L,
                        blocksNewStarts = fields[7].toBoolean(),
                        coveredStates = csv(fields[8]).mapTo(mutableSetOf()) { DownloadState.valueOf(it) },
                        message = fields[9],
                    )
                },
                message = fields[9],
            )
        }.getOrNull()
    }

    override fun saveQueueReservation(reservation: QueueSlotReservation) = append(
        "queue-reservations.log",
        listOf(
            "reservation",
            reservation.claimKey,
            reservation.queueId,
            reservation.downloadId,
            reservation.attemptGeneration.toString(),
            reservation.accepted.toString(),
            reservation.activeCountBeforeClaim.toString(),
            reservation.budgetAfterClaim.maxConcurrent.toString(),
            reservation.budgetAfterClaim.reservedSlots.toString(),
            reservation.reason?.name.orEmpty(),
            reservation.message,
        ),
    )

    override fun queueReservations(): List<QueueSlotReservation> = readLines("queue-reservations.log").mapNotNull { fields ->
        runCatching {
            QueueSlotReservation(
                queueId = fields[2],
                downloadId = fields[3],
                attemptGeneration = fields[4].toLong(),
                accepted = fields[5].toBoolean(),
                activeCountBeforeClaim = fields[6].toInt(),
                budgetAfterClaim = com.mikeyphw.xdm.android.model.QueueBudget(
                    maxConcurrent = fields[7].toInt(),
                    reservedSlots = fields[8].toInt(),
                ),
                reason = fields[9].takeIf(String::isNotBlank)?.let { com.mikeyphw.xdm.android.model.QueueHoldReason.valueOf(it) },
                message = fields[10],
            )
        }.getOrNull()
    }

    override fun saveSystemStopReason(record: SystemStopReasonRecord) = append(
        "system-stop-reasons.log",
        listOf(
            "stop",
            record.downloadId,
            record.attemptGeneration.toString(),
            record.owner.name,
            record.workInfoStopReason?.toString().orEmpty(),
            record.jobParametersStopReason?.toString().orEmpty(),
            record.pendingJobReasons.joinToString(","),
            record.occurredAtEpochMs.toString(),
            record.message,
        ),
    )

    override fun systemStopReasons(downloadId: String): List<SystemStopReasonRecord> = readLines("system-stop-reasons.log").mapNotNull { fields ->
        runCatching {
            SystemStopReasonRecord(
                downloadId = fields[1],
                attemptGeneration = fields[2].toLong(),
                owner = SystemExecutionOwner.valueOf(fields[3]),
                workInfoStopReason = fields[4].toIntOrNull(),
                jobParametersStopReason = fields[5].toIntOrNull(),
                pendingJobReasons = csv(fields[6]).mapNotNull(String::toIntOrNull),
                occurredAtEpochMs = fields[7].toLong(),
                message = fields[8],
            )
        }.getOrNull()
    }.filter { it.downloadId == downloadId }

    override fun saveImmediateReevaluation(event: ImmediateReevaluationEvent) = append(
        "immediate-reevaluations.log",
        listOf("reevaluate", event.id, event.source, event.coalesceKey, event.createdAtEpochMs.toString(), event.durable.toString()),
    )

    override fun pendingImmediateReevaluations(): List<ImmediateReevaluationEvent> {
        val coalesced = LinkedHashMap<String, ImmediateReevaluationEvent>()
        readLines("immediate-reevaluations.log").forEach { fields ->
            runCatching {
                coalesced[fields[3]] = ImmediateReevaluationEvent(
                    id = fields[1],
                    source = fields[2],
                    coalesceKey = fields[3],
                    createdAtEpochMs = fields[4].toLong(),
                    durable = fields[5].toBoolean(),
                )
            }
        }
        return coalesced.values.toList()
    }

    override fun saveRecoveryPlan(plan: RecoveryExecutionPlan) = append(
        "recovery-plans.log",
        listOf(
            "recovery",
            plan.downloadId,
            plan.attemptGeneration.toString(),
            plan.operation.name,
            plan.safeToExecute.toString(),
            plan.outcomeIfBlocked.name,
            plan.artifacts.stagingPath.orEmpty(),
            plan.artifacts.checkpointPath.orEmpty(),
            plan.artifacts.controlJournalPath.orEmpty(),
            plan.artifacts.outputPath.orEmpty(),
            plan.artifacts.ownershipClaimId.orEmpty(),
            plan.artifacts.finalizationJournalId.orEmpty(),
            plan.message,
        ),
    )

    override fun recoveryPlan(downloadId: String, attemptGeneration: Long): RecoveryExecutionPlan? = readLines("recovery-plans.log").asReversed().firstNotNullOfOrNull { fields ->
        runCatching {
            if (fields[1] != downloadId || fields[2].toLong() != attemptGeneration) return@runCatching null
            RecoveryExecutionPlan(
                downloadId = fields[1],
                attemptGeneration = fields[2].toLong(),
                operation = RecoveryOperation.valueOf(fields[3]),
                safeToExecute = fields[4].toBoolean(),
                outcomeIfBlocked = RecoveryOperationOutcome.valueOf(fields[5]),
                artifacts = RecoveryArtifactIdentity(
                    stagingPath = fields[6].takeIf(String::isNotBlank),
                    checkpointPath = fields[7].takeIf(String::isNotBlank),
                    controlJournalPath = fields[8].takeIf(String::isNotBlank),
                    outputPath = fields[9].takeIf(String::isNotBlank),
                    ownershipClaimId = fields[10].takeIf(String::isNotBlank),
                    finalizationJournalId = fields[11].takeIf(String::isNotBlank),
                ),
                message = fields[12],
            )
        }.getOrNull()
    }

    override fun saveTerminalNotification(record: TerminalNotificationRecord): Boolean = synchronized(lock) {
        val key = encode(record.idempotencyKey)
        if (file("terminal-notifications.log").takeIf(File::exists)?.readLines().orEmpty().any { it.split('\t').getOrNull(1) == key }) {
            return@synchronized false
        }
        appendLocked(
            "terminal-notifications.log",
            listOf(
                "terminal",
                record.idempotencyKey,
                record.key.downloadId,
                record.key.attemptGeneration.toString(),
                record.key.state.name,
                record.title,
                record.text,
                record.createdAtEpochMs.toString(),
                record.dispatchedAtEpochMs?.toString().orEmpty(),
                record.actions.joinToString(",") { it.label },
            ),
        )
        true
    }

    override fun terminalNotifications(): List<TerminalNotificationRecord> = readLines("terminal-notifications.log").mapNotNull { fields ->
        runCatching {
            TerminalNotificationRecord(
                key = com.mikeyphw.xdm.android.model.TerminalNotificationKey(
                    downloadId = fields[2],
                    attemptGeneration = fields[3].toLong(),
                    state = DownloadState.valueOf(fields[4]),
                ),
                title = fields[5],
                text = fields[6],
                actions = emptyList<NotificationActionModel>(),
                createdAtEpochMs = fields[7].toLong(),
                dispatchedAtEpochMs = fields[8].toLongOrNull(),
            )
        }.getOrNull()
    }

    override fun saveQueueDeletion(plan: QueueDeletionPlan) = append(
        "queue-deletions.log",
        listOf(
            "deletion",
            plan.queueId,
            plan.disposition.name,
            plan.referencedDownloadIds.joinToString(","),
            plan.replacementQueueId.orEmpty(),
            plan.message,
        ),
    )

    override fun queueDeletionPlans(): List<QueueDeletionPlan> = readLines("queue-deletions.log").mapNotNull { fields ->
        runCatching {
            QueueDeletionPlan(
                queueId = fields[1],
                disposition = QueueDeletionDisposition.valueOf(fields[2]),
                referencedDownloadIds = csv(fields[3]),
                replacementQueueId = fields[4].takeIf(String::isNotBlank),
                message = fields[5],
            )
        }.getOrNull()
    }

    private fun append(name: String, fields: List<String>) = synchronized(lock) { appendLocked(name, fields) }

    private fun appendLocked(name: String, fields: List<String>) {
        root.mkdirs()
        FileOutputStream(file(name), true).use { out ->
            out.write(fields.joinToString("\t") { encode(it) }.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.fd.sync()
        }
    }

    private fun readLines(name: String): List<List<String>> = synchronized(lock) {
        file(name).takeIf(File::exists)?.readLines().orEmpty().mapNotNull { line ->
            runCatching { line.split('\t').map(::decode) }.getOrNull()
        }
    }

    private fun file(name: String): File = File(root, name)
    private fun csv(value: String): List<String> = value.split(',').filter(String::isNotBlank)
    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}

class QueueSchedulingRecoveryCoordinator(private val store: QueueSchedulingRecoveryStore) {
    fun recordPauseAll(result: DurableQueueCommandResult) {
        require(result.durableHold?.blocksNewStarts == true) { "Pause All must persist a global hold before active transfer controls run." }
        val hold = requireNotNull(result.durableHold) { "Pause All must carry a durable hold." }
        require(hold.coveredStates.containsAll(QueueStateMachinePlanner.activePauseStates)) {
            "Pause All must cover Connecting, Downloading, Finalizing, Verifying, and Repairing."
        }
        store.saveQueueCommand(result)
    }

    fun recordQueueReservation(reservation: QueueSlotReservation) {
        store.saveQueueReservation(reservation)
    }

    fun recordSystemStop(downloadId: String, attemptGeneration: Long, owner: SystemExecutionOwner, stopReason: Int?, nowEpochMs: Long): SystemStopReasonRecord {
        val record = SystemStopReasonRecord(
            downloadId = downloadId,
            attemptGeneration = attemptGeneration,
            owner = owner,
            workInfoStopReason = if (owner == SystemExecutionOwner.WorkManager) stopReason else null,
            jobParametersStopReason = if (owner == SystemExecutionOwner.UserInitiatedJob) stopReason else null,
            occurredAtEpochMs = nowEpochMs,
            message = "Android execution owner stopped attempt $attemptGeneration; reason is persisted for UI, recovery, diagnostics, and support bundles.",
        )
        store.saveSystemStopReason(record)
        return record
    }

    fun requestImmediateReevaluation(source: String, coalesceKey: String, nowEpochMs: Long): ImmediateReevaluationEvent {
        val event = ImmediateReevaluationEvent(
            id = "reevaluate-$coalesceKey-$nowEpochMs",
            source = source,
            coalesceKey = coalesceKey,
            createdAtEpochMs = nowEpochMs,
        )
        store.saveImmediateReevaluation(event)
        return event
    }

    fun planRecovery(
        downloadId: String,
        attemptGeneration: Long,
        state: DownloadState,
        operation: RecoveryOperation,
        artifacts: RecoveryArtifactIdentity,
    ): RecoveryExecutionPlan {
        val safe = state !in setOf(DownloadState.Completed, DownloadState.Cancelled) && artifacts.outputPath != null
        val plan = RecoveryExecutionPlan(
            downloadId = downloadId,
            attemptGeneration = attemptGeneration,
            operation = operation,
            safeToExecute = safe,
            artifacts = artifacts,
            outcomeIfBlocked = when (operation) {
                RecoveryOperation.LocateFile -> RecoveryOperationOutcome.NeedsFileSelection
                RecoveryOperation.AdoptOrphan -> RecoveryOperationOutcome.NeedsUserConsent
                else -> RecoveryOperationOutcome.Rejected
            },
            message = if (safe) "Recovery operation can execute with concrete artifact identities." else "Recovery is blocked instead of blindly starting a queue item.",
        )
        store.saveRecoveryPlan(plan)
        return plan
    }

    fun recordTerminalNotification(record: TerminalNotificationRecord): Boolean = store.saveTerminalNotification(record)

    fun recordQueueDeletion(plan: QueueDeletionPlan) { store.saveQueueDeletion(plan) }

    fun snapshot(): QueueSchedulingRecoverySnapshot = QueueSchedulingRecoverySnapshot(
        queueCommands = store.queueCommands(),
        queueReservations = store.queueReservations(),
        immediateReevaluations = store.pendingImmediateReevaluations(),
        terminalNotifications = store.terminalNotifications(),
        queueDeletionPlans = store.queueDeletionPlans(),
    )
}

data class QueueSchedulingRecoverySnapshot(
    val queueCommands: List<DurableQueueCommandResult>,
    val queueReservations: List<QueueSlotReservation>,
    val immediateReevaluations: List<ImmediateReevaluationEvent>,
    val terminalNotifications: List<TerminalNotificationRecord>,
    val queueDeletionPlans: List<QueueDeletionPlan>,
)

object TransferExecutionStopReasonRecorder {
    private val reasons = ConcurrentHashMap<String, SystemStopReasonRecord>()
    private val persistentRoot = AtomicReference<File?>()

    fun installPersistentRoot(root: File) {
        persistentRoot.set(root)
    }

    fun record(record: SystemStopReasonRecord) {
        reasons["${record.downloadId}:${record.attemptGeneration}:${record.owner}"] = record
        persistentRoot.get()?.let { root ->
            FileBackedQueueSchedulingRecoveryStore(root).saveSystemStopReason(record)
        }
    }

    fun snapshot(): List<SystemStopReasonRecord> = reasons.values.sortedWith(compareBy({ it.downloadId }, { it.attemptGeneration }, { it.owner.name }))
}
