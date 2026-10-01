package com.mikeyphw.xdm.android.transfer.aria2

import java.io.File
import java.lang.ProcessBuilder.Redirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SystemAria2ProcessLauncher : Aria2ProcessLauncher {
    override fun launch(plan: Aria2LaunchPlan): Aria2ManagedProcess {
        check(plan.binary.isFile && plan.binary.canExecute()) { "aria2 runtime is not executable" }
        check(plan.workingDirectory.isDirectory) { "aria2 working directory is unavailable" }

        val builder = ProcessBuilder(plan.command)
            .directory(plan.workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(Redirect.appendTo(plan.logFile))

        // Android instrumentation/adb may supply HOME/PWD/TMP values rooted in
        // /data/local/tests. The aria2 Android launcher must never inherit those
        // shell-owned paths: upstream's Android guidance explicitly establishes
        // an application-owned HOME and starts aria2 from there. Keep the child
        // environment anchored to XDM's private aria2 runtime directory.
        builder.environment().apply {
            remove("OLDPWD")
            putAll(plan.privateRuntimeEnvironment())
        }

        return JavaAria2ManagedProcess(builder.start())
    }
}

internal fun Aria2LaunchPlan.privateRuntimeEnvironment(): Map<String, String> {
    val root = workingDirectory.canonicalFile
    val temporary = File(root, "tmp")
    val config = File(root, ".config")
    val cache = File(root, ".cache")
    val data = File(root, ".local/share")
    val state = File(root, ".local/state")
    val runtime = File(root, ".runtime")
    listOf(temporary, config, cache, data, state, runtime).forEach { directory ->
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to prepare aria2 private process environment: ${directory.absolutePath}"
        }
    }

    return linkedMapOf(
        "HOME" to root.absolutePath,
        "PWD" to root.absolutePath,
        "TMPDIR" to temporary.absolutePath,
        "TMP" to temporary.absolutePath,
        "TEMP" to temporary.absolutePath,
        "XDG_CONFIG_HOME" to config.absolutePath,
        "XDG_CACHE_HOME" to cache.absolutePath,
        "XDG_DATA_HOME" to data.absolutePath,
        "XDG_STATE_HOME" to state.absolutePath,
        "XDG_RUNTIME_DIR" to runtime.absolutePath,
    )
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
