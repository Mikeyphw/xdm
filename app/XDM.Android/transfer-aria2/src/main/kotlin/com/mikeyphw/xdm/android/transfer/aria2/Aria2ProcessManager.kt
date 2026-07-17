package com.mikeyphw.xdm.android.transfer.aria2

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class Aria2ProcessManager(
    private val capabilityProbe: Aria2CapabilityProbe,
    private val sessionStore: Aria2RuntimeFiles,
    private val secretProvider: Aria2SecretProvider,
    private val portAllocator: Aria2PortAllocator = LoopbackAria2PortAllocator(),
    private val processLauncher: Aria2ProcessLauncher = SystemAria2ProcessLauncher(),
    private val rpcFactory: Aria2RpcControlFactory = OkHttpAria2RpcControlFactory(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val startupTimeoutMillis: Long = 5_000,
    private val shutdownTimeoutMillis: Long = 3_000,
    private val pollIntervalMillis: Long = 100,
) {
    private val gate = Mutex()
    private val processReference = AtomicReference<Aria2ManagedProcess?>()
    private var rpcControl: Aria2RpcControl? = null
    private val _state = MutableStateFlow<Aria2ProcessState>(Aria2ProcessState.Stopped)
    val state: StateFlow<Aria2ProcessState> = _state

    fun probe(): Aria2CapabilityReport = runCatching { capabilityProbe.probe() }.getOrElse {
        Aria2CapabilityReport(
            availability = Aria2Availability.ProbeFailed,
            summary = "The aria2 runtime probe failed safely.",
        )
    }

    suspend fun start(): Aria2StartResult = gate.withLock {
        val currentProcess = processReference.get()
        val currentState = _state.value
        if (currentProcess?.isAlive == true && currentState is Aria2ProcessState.Running) {
            return@withLock Aria2StartResult(started = true, alreadyRunning = true, state = currentState)
        }
        clearDeadProcess()
        val report = probe()
        if (!report.isAvailable) {
            val unavailable = Aria2ProcessState.Unavailable(report)
            _state.value = unavailable
            return@withLock Aria2StartResult(started = false, alreadyRunning = false, state = unavailable)
        }

        val prepared = prepareLaunch(report) ?: return@withLock failedStart(
            "aria2 runtime preparation failed; no process was started.",
        )
        _state.value = Aria2ProcessState.Starting(prepared.endpoint)
        val process = try {
            processLauncher.launch(prepared.plan)
        } catch (error: Throwable) {
            sessionStore.deleteLaunchConfiguration(prepared.configuration)
            return@withLock failedStart("aria2 could not start: ${safeMessage(error)}")
        }
        processReference.set(process)
        val rpc = try {
            rpcFactory.create(prepared.endpoint, prepared.secret)
        } catch (error: Throwable) {
            secureAbort(process, prepared.configuration)
            return@withLock failedStart("aria2 RPC initialization failed: ${safeMessage(error)}")
        }
        rpcControl = rpc
        val version = waitForRpc(process, rpc)
        val configurationRemoved = sessionStore.deleteLaunchConfiguration(prepared.configuration)
        if (version == null || !configurationRemoved) {
            secureAbort(process, prepared.configuration)
            val message = if (!configurationRemoved) {
                "aria2 was stopped because its temporary RPC configuration could not be removed."
            } else {
                "aria2 started but its authenticated loopback RPC endpoint did not become ready."
            }
            return@withLock failedStart(message)
        }
        val running = Aria2ProcessState.Running(prepared.endpoint, version, process.processId)
        _state.value = running
        observeExit(process)
        Aria2StartResult(started = true, alreadyRunning = false, state = running)
    }

    suspend fun stop(): Aria2StopResult = gate.withLock {
        val process = processReference.getAndSet(null)
        val rpc = rpcControl.also { rpcControl = null }
        val current = _state.value
        val endpoint = when (current) {
            is Aria2ProcessState.Running -> current.endpoint
            is Aria2ProcessState.Starting -> current.endpoint
            is Aria2ProcessState.Stopping -> current.endpoint
            else -> null
        }
        if (process == null || !process.isAlive) {
            _state.value = Aria2ProcessState.Stopped
            return@withLock Aria2StopResult(clean = true, forced = false, sessionSaved = true, exitCode = null)
        }
        _state.value = Aria2ProcessState.Stopping(endpoint ?: Aria2Endpoint(6800))
        val sessionSaved = rpc?.let { runCatching { it.saveSession() }.getOrDefault(false) } ?: false
        val gracefulRequested = rpc?.let { runCatching { it.shutdown(force = false) }.isSuccess } ?: false
        var forced = false
        var exitCode = withTimeoutOrNull(shutdownTimeoutMillis) { process.awaitExit() }
        if (exitCode == null && process.isAlive) {
            forced = true
            if (gracefulRequested) process.destroy() else process.destroyForcibly()
            exitCode = withTimeoutOrNull(750) { process.awaitExit() }
        }
        if (exitCode == null && process.isAlive) {
            forced = true
            process.destroyForcibly()
            exitCode = withTimeoutOrNull(750) { process.awaitExit() }
        }
        val clean = exitCode == 0 && sessionSaved && !forced
        _state.value = Aria2ProcessState.Stopped
        Aria2StopResult(clean, forced, sessionSaved, exitCode)
    }

    suspend fun smokeTest(): Aria2SmokeTestResult {
        val start = start()
        val running = start.state as? Aria2ProcessState.Running
            ?: return Aria2SmokeTestResult(false, describe(start.state))
        val stop = if (start.alreadyRunning) null else stop()
        val successful = stop == null || stop.clean
        val summary = buildString {
            append("aria2 ")
            append(running.version.version)
            append(" authenticated on loopback")
            if (stop != null) append(if (stop.clean) " and shut down cleanly." else "; shutdown required recovery handling.")
            else append("; the existing managed process was left running.")
        }
        return Aria2SmokeTestResult(successful, summary, running.version)
    }

    private fun prepareLaunch(report: Aria2CapabilityReport): PreparedLaunch? {
        var configuration: File? = null
        return try {
            sessionStore.prepare()
            val endpoint = Aria2Endpoint(portAllocator.allocate())
            val secret = secretProvider.getOrCreate()
            configuration = sessionStore.writeLaunchConfiguration(endpoint, secret)
            PreparedLaunch(
                endpoint = endpoint,
                secret = secret,
                configuration = configuration,
                plan = Aria2LaunchPlan(
                    binary = requireNotNull(report.binary).file,
                    workingDirectory = sessionStore.rootDirectory,
                    configurationFile = configuration,
                    logFile = sessionStore.logFile(),
                ),
            )
        } catch (_: Throwable) {
            configuration?.let(sessionStore::deleteLaunchConfiguration)
            null
        }
    }

    private suspend fun waitForRpc(process: Aria2ManagedProcess, rpc: Aria2RpcControl): Aria2Version? =
        withTimeoutOrNull(startupTimeoutMillis) {
            while (process.isAlive) {
                runCatching { rpc.getVersion() }.getOrNull()?.let { return@withTimeoutOrNull it }
                delay(pollIntervalMillis)
            }
            null
        }

    private fun observeExit(process: Aria2ManagedProcess) {
        scope.launch {
            val exitCode = runCatching { process.awaitExit() }.getOrNull()
            gate.withLock {
                if (processReference.compareAndSet(process, null)) {
                    rpcControl = null
                    if (_state.value !is Aria2ProcessState.Stopping) {
                        _state.value = if (exitCode == 0) {
                            Aria2ProcessState.Stopped
                        } else {
                            Aria2ProcessState.Failed("aria2 exited unexpectedly${exitCode?.let { " with code $it" }.orEmpty()}.")
                        }
                    }
                }
            }
        }
    }

    private fun clearDeadProcess() {
        val current = processReference.get()
        if (current != null && !current.isAlive) {
            processReference.compareAndSet(current, null)
            rpcControl = null
        }
    }

    private fun secureAbort(process: Aria2ManagedProcess, configuration: File) {
        sessionStore.deleteLaunchConfiguration(configuration)
        if (process.isAlive) process.destroyForcibly()
        processReference.compareAndSet(process, null)
        rpcControl = null
    }

    private fun failedStart(message: String): Aria2StartResult {
        val failed = Aria2ProcessState.Failed(message)
        _state.value = failed
        return Aria2StartResult(started = false, alreadyRunning = false, state = failed)
    }

    private fun safeMessage(error: Throwable): String = error.message
        ?.replace(Regex("token:[^\\s,]+"), "token:<redacted>")
        ?.replace(Regex("rpc-secret=[^\\s,]+"), "rpc-secret=<redacted>")
        ?: error::class.java.simpleName

    private fun describe(state: Aria2ProcessState): String = when (state) {
        Aria2ProcessState.Stopped -> "aria2 is stopped."
        is Aria2ProcessState.Unavailable -> state.report.summary
        is Aria2ProcessState.Starting -> "aria2 is still starting."
        is Aria2ProcessState.Running -> "aria2 ${state.version.version} is running."
        is Aria2ProcessState.Stopping -> "aria2 is stopping."
        is Aria2ProcessState.Failed -> state.message
    }

    private data class PreparedLaunch(
        val endpoint: Aria2Endpoint,
        val secret: Aria2RpcSecret,
        val configuration: File,
        val plan: Aria2LaunchPlan,
    )
}
