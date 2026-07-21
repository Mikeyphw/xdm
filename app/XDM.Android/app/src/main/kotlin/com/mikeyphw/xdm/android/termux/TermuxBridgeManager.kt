package com.mikeyphw.xdm.android.termux

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

class TermuxBridgeManager(context: Context) {
    private val appContext = context.applicationContext
    private val runner = TermuxCommandRunner(appContext)
    val status: StateFlow<TermuxBridgeStatus> = TermuxRunStore.status

    fun refreshStatus() {
        runner.refreshStatus()
    }

    fun runToolProbe() {
        val result = runner.run(XdmTermuxCommand.ProbeAllTools)
        if (!result.started) {
            TermuxRunStore.recordLaunchFailure(appContext, XdmTermuxCommand.ProbeAllTools.operation, result.error)
        }
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
}
