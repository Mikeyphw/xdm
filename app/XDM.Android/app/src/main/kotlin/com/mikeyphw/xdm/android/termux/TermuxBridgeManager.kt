package com.mikeyphw.xdm.android.termux

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

class TermuxBridgeManager(context: Context) {
    @Volatile private var toolProbeRequestedAtEpochMs: Long = 0L
    private val appContext = context.applicationContext
    private val runner = TermuxCommandRunner(appContext)
    val status: StateFlow<TermuxBridgeStatus> = TermuxRunStore.status

    fun refreshStatus() {
        runner.refreshStatus()
        val current = status.value
        val now = System.currentTimeMillis()
        if (current.hasFreshSuccessfulToolProbe(now)) {
            toolProbeRequestedAtEpochMs = 0L
        } else if (
            current.termuxInstalled &&
            current.runCommandPermissionGranted &&
            now - toolProbeRequestedAtEpochMs >= TOOL_PROBE_RETRY_GUARD_MS
        ) {
            toolProbeRequestedAtEpochMs = now
            runToolProbe()
        }
    }

    fun runToolProbe() {
        toolProbeRequestedAtEpochMs = System.currentTimeMillis()
        val result = runner.run(XdmTermuxCommand.ProbeAllTools)
        if (!result.started) {
            TermuxRunStore.recordLaunchFailure(appContext, XdmTermuxCommand.ProbeAllTools.operation, result.error)
        }
    }

    fun runStoragePathProbe(path: String): TermuxCommandRunner.LaunchResult {
        val command = XdmTermuxCommand.StoragePathProbe(path)
        val result = runner.run(command)
        if (!result.started) {
            TermuxRunStore.recordLaunchFailure(appContext, command.operation, result.error)
        }
        return result
    }

    fun runRootProbe() {
        val result = runner.run(XdmTermuxCommand.RootProbe)
        TermuxRunStore.recordRootProbeLaunch(
            runId = result.runId,
            started = result.started,
            message = if (result.started) "Root probe launched through Termux." else result.error,
        )
        if (!result.started) {
            TermuxRunStore.recordLaunchFailure(appContext, XdmTermuxCommand.RootProbe.operation, result.error)
        }
    }

    fun collectRootProcessDiagnostics() = runRootAction(XdmRootAction.CollectProcessDiagnostics(appContext.packageName))

    fun killStuckTermuxAria2Daemon(port: Int = 16800) = runRootAction(XdmRootAction.KillTermuxAria2Daemon(port))

    fun fixTermuxDownloadPermissions(path: String) = runRootAction(XdmRootAction.FixFilePermissions(path))

    private fun runRootAction(action: XdmRootAction) {
        val current = TermuxRunStore.status.value
        if (current.rootMode == TermuxRootMode.Off) {
            TermuxRunStore.recordRootActionLaunch("", action, started = false, message = "Root mode is off.")
            return
        }
        if ((!current.rootAvailable || !current.rootProbeSucceeded) && action.risk != RootActionRisk.Low) {
            TermuxRunStore.recordRootActionLaunch("", action, started = false, message = "Run the root probe before medium-risk root actions.")
            return
        }
        val command = XdmTermuxCommand.RootAction(action)
        val result = runner.run(command)
        TermuxRunStore.recordRootActionLaunch(
            runId = result.runId,
            action = action,
            started = result.started,
            message = if (result.started) "Typed root action launched." else result.error,
        )
        if (!result.started) {
            TermuxRunStore.recordLaunchFailure(appContext, command.operation, result.error)
        }
    }

    fun openTermux(): Boolean = runner.openTermux()

    fun setRootMode(mode: TermuxRootMode) {
        TermuxRunStore.setRootMode(appContext, mode)
    }

    private companion object {
        const val TOOL_PROBE_RETRY_GUARD_MS = 60_000L
    }
}
