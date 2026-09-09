package com.mikeyphw.xdm.android.ui.debug

import com.mikeyphw.xdm.android.AppContainer
import com.mikeyphw.xdm.android.MainUiState
import com.mikeyphw.xdm.android.model.DebugEventRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

class DebugTestRunner(
    private val privateRoot: File,
    private val recorder: DebugEventRecorder,
    private val store: DebugTestStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableRun = MutableStateFlow<DebugTestRun?>(null)
    val currentRun: StateFlow<DebugTestRun?> = mutableRun

    @Volatile
    private var stopRequested: Boolean = false

    suspend fun runSelected(
        state: MainUiState,
        selectedTestIds: Set<String>,
        appContainer: AppContainer?,
    ): DebugTestRun {
        stopRequested = false
        val selected = DebugTestRegistry.tests.filter { it.id in selectedTestIds }
        val runId = "debug-${clock()}-${UUID.randomUUID().toString().take(8)}"
        val startedAt = clock()
        val pendingResults = selected.map { test ->
            DebugTestResult(
                testId = test.id,
                groupId = test.group.label,
                name = test.name,
                status = DebugTestStatus.Pending,
                startedAtEpochMs = startedAt,
                durationMs = 0,
                summary = "Waiting to run.",
                suggestedAction = "No action yet.",
            )
        }
        var run = DebugTestRun(
            id = runId,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = null,
            selectedTestIds = selected.map { it.id },
            results = pendingResults,
        )
        mutableRun.value = run

        val results = pendingResults.toMutableList()
        val context = DebugTestContext(
            state = state,
            privateRoot = privateRoot,
            recorder = recorder,
            appContainer = appContainer,
            runId = runId,
            clock = clock,
            isStopRequested = { stopRequested },
        )

        selected.forEachIndexed { index, test ->
            if (stopRequested) {
                markRemainingStopped(results, fromIndex = index, runId = runId, startedAt = startedAt)
                run = run.copy(results = results.toList(), finishedAtEpochMs = clock())
                mutableRun.value = run
                store.saveRun(run)
                return run
            }

            val testStartedAt = clock()
            results[index] = results[index].copy(
                status = DebugTestStatus.Running,
                startedAtEpochMs = testStartedAt,
                summary = "Running ${test.name}…",
            )
            run = run.copy(results = results.toList())
            mutableRun.value = run

            val result = try {
                if (stopRequested) throw CancellationException("Diagnostics run stopped")
                val completed = test.run(context)
                if (stopRequested) {
                    throw CancellationException("Diagnostics run stopped")
                }
                completed
            } catch (cancelled: CancellationException) {
                DebugTestResult(
                    testId = test.id,
                    groupId = test.group.label,
                    name = test.name,
                    status = DebugTestStatus.Skipped,
                    startedAtEpochMs = testStartedAt,
                    durationMs = (clock() - testStartedAt).coerceAtLeast(0),
                    summary = "Stopped before this test completed.",
                    errorCode = "debug-run-stopped",
                    suggestedAction = "Run selected again when ready.",
                )
            } catch (error: Throwable) {
                DebugTestResult(
                    testId = test.id,
                    groupId = test.group.label,
                    name = test.name,
                    status = DebugTestStatus.Failed,
                    startedAtEpochMs = testStartedAt,
                    durationMs = (clock() - testStartedAt).coerceAtLeast(0),
                    summary = error.message ?: "${test.name} failed with ${error.javaClass.simpleName}.",
                    details = mapOf("exception" to error.javaClass.name),
                    errorCode = "debug-test-threw",
                    suggestedAction = "Copy or export this run and inspect the failure details.",
                )
            }
            results[index] = result
            run = run.copy(results = results.toList())
            mutableRun.value = run
        }

        val completed = run.copy(finishedAtEpochMs = clock(), results = results.toList())
        mutableRun.value = completed
        store.saveRun(completed)
        return completed
    }

    fun markStopped() {
        stopRequested = true
        val run = mutableRun.value ?: return
        val stopped = run.copy(
            finishedAtEpochMs = clock(),
            results = run.results.map { result ->
                when (result.status) {
                    DebugTestStatus.Pending, DebugTestStatus.Running -> result.copy(
                        status = DebugTestStatus.Skipped,
                        durationMs = (clock() - result.startedAtEpochMs).coerceAtLeast(0),
                        summary = "Stopped before completion.",
                        errorCode = "debug-run-stopped",
                        suggestedAction = "Run selected again when ready.",
                    )
                    else -> result
                }
            },
        )
        mutableRun.value = stopped
        store.saveRun(stopped)
    }

    private fun markRemainingStopped(
        results: MutableList<DebugTestResult>,
        fromIndex: Int,
        runId: String,
        startedAt: Long,
    ) {
        for (index in fromIndex until results.size) {
            val result = results[index]
            if (result.status == DebugTestStatus.Pending || result.status == DebugTestStatus.Running) {
                results[index] = result.copy(
                    status = DebugTestStatus.Skipped,
                    startedAtEpochMs = if (result.startedAtEpochMs == startedAt) clock() else result.startedAtEpochMs,
                    durationMs = (clock() - result.startedAtEpochMs).coerceAtLeast(0),
                    summary = "Stopped before completion.",
                    errorCode = "debug-run-stopped",
                    suggestedAction = "Run selected again when ready.",
                )
                recorder.record(
                    area = com.mikeyphw.xdm.android.model.DebugArea.Validation,
                    severity = com.mikeyphw.xdm.android.model.DebugSeverity.Info,
                    action = "debug-test:${result.testId}",
                    result = "stopped",
                    safeDetails = mapOf("summary" to "Stopped before completion.", "test" to result.name),
                    sessionId = runId,
                    operationId = result.testId,
                    parentOperationId = runId,
                )
            }
        }
    }
}
