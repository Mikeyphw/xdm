package com.mikeyphw.xdm.android.transfer.aria2

import java.lang.ProcessBuilder.Redirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SystemAria2ProcessLauncher : Aria2ProcessLauncher {
    override fun launch(plan: Aria2LaunchPlan): Aria2ManagedProcess {
        check(plan.binary.isFile && plan.binary.canExecute()) { "aria2 runtime is not executable" }
        check(plan.workingDirectory.isDirectory) { "aria2 working directory is unavailable" }
        val process = ProcessBuilder(plan.command)
            .directory(plan.workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(Redirect.appendTo(plan.logFile))
            .start()
        return JavaAria2ManagedProcess(process)
    }
}

private class JavaAria2ManagedProcess(private val process: Process) : Aria2ManagedProcess {
    // Android does not expose a stable public PID API for java.lang.Process on every supported API level.
    // PID is diagnostics-only, so avoid reflective or hidden-API access.
    override val processId: Long? = null
    override val isAlive: Boolean get() = process.isAlive

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    override fun destroy() {
        process.destroy()
    }

    override fun destroyForcibly() {
        process.destroyForcibly()
    }
}
