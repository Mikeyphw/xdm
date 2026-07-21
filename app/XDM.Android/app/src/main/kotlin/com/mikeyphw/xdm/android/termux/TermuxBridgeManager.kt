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

    fun openTermux(): Boolean = runner.openTermux()

    fun setRootMode(mode: TermuxRootMode) {
        TermuxRunStore.setRootMode(appContext, mode)
    }
}
