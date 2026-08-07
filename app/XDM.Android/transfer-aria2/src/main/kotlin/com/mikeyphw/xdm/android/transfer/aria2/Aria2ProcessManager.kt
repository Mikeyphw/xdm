package com.mikeyphw.xdm.android.transfer.aria2

import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
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
    private val authenticationProbe: Aria2RpcAuthenticationProbe = OkHttpAria2RpcAuthenticationProbe(),
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
        val persistedRuntime = reconcilePersistedRuntime()
        if (persistedRuntime.failure != null) {
            return@withLock failedStart(
                "aria2 persisted-runtime recovery failed (${persistedRuntime.failure.kind.name}): ${persistedRuntime.failure.detail}",
                persistedRuntime.failure,
            )
        }
        val report = probe()
        if (!report.isAvailable) {
            val unavailable = Aria2ProcessState.Unavailable(report)
            _state.value = unavailable
            return@withLock Aria2StartResult(started = false, alreadyRunning = false, state = unavailable)
        }

        val prepared = prepareLaunch(report) ?: return@withLock failedStart(
            "aria2 runtime preparation failed; no process was started.",
            Aria2StartupDiagnostic(
                kind = Aria2StartupFailureKind.LaunchFailure,
                detail = "Private runtime preparation failed before process launch.",
                logTail = safeLogTail(),
            ),
        )
        _state.value = Aria2ProcessState.Starting(
            endpoint = prepared.endpoint,
            secretGeneration = prepared.secretGeneration,
            startedAtEpochMs = prepared.startedAtEpochMs,
        )
        val process = try {
            processLauncher.launch(prepared.plan)
        } catch (error: Throwable) {
            sessionStore.deleteLaunchConfiguration(prepared.configuration)
            return@withLock failedStart(
                "aria2 could not start: ${safeMessage(error)}",
                diagnosticFor(error, kindOverride = Aria2StartupFailureKind.LaunchFailure),
            )
        }
        processReference.set(process)
        val rpc = try {
            rpcFactory.create(prepared.endpoint, prepared.secret)
        } catch (error: Throwable) {
            secureAbort(process, prepared.configuration)
            return@withLock failedStart(
                "aria2 RPC initialization failed: ${safeMessage(error)}",
                diagnosticFor(error),
            )
        }
        rpcControl = rpc
        val readiness = waitForRpc(process, rpc)
        val configurationRemoved = sessionStore.deleteLaunchConfiguration(prepared.configuration)
        if (readiness.version == null || !configurationRemoved) {
            secureAbort(process, prepared.configuration)
            val diagnostic = if (!configurationRemoved) {
                Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.ConfigurationCleanup,
                    detail = "Temporary RPC configuration could not be removed after startup.",
                    exitCode = readiness.exitCode,
                    logTail = safeLogTail(),
                )
            } else {
                diagnosticFor(readiness.lastFailure, readiness.exitCode, readiness.timedOut)
            }
            val message = if (!configurationRemoved) {
                "aria2 was stopped because its temporary RPC configuration could not be removed."
            } else {
                "aria2 RPC startup failed (${diagnostic.kind.name}): ${diagnostic.detail}"
            }
            return@withLock failedStart(message, diagnostic)
        }

        val unauthenticatedRejected = runCatching {
            authenticationProbe.rejectsUnauthenticated(prepared.endpoint)
        }.getOrElse { error ->
            secureAbort(process, prepared.configuration)
            val diagnostic = diagnosticFor(error, kindOverride = Aria2StartupFailureKind.AuthenticationBoundary)
            return@withLock failedStart(
                "aria2 authenticated RPC came up, but XDM could not verify the unauthenticated rejection boundary.",
                diagnostic,
            )
        }
        if (!unauthenticatedRejected) {
            secureAbort(process, prepared.configuration)
            return@withLock failedStart(
                "aria2 RPC authentication boundary is unsafe: an unauthenticated getVersion call was accepted.",
                Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.AuthenticationBoundary,
                    detail = "Unauthenticated aria2.getVersion unexpectedly returned a result.",
                    logTail = safeLogTail(),
                ),
            )
        }

        val lease = Aria2RuntimeLease(
            endpoint = prepared.endpoint,
            secretGeneration = prepared.secretGeneration,
            startedAtEpochMs = prepared.startedAtEpochMs,
        )
        if (sessionStore.supportsRuntimeLease && !sessionStore.writeRuntimeLease(lease)) {
            secureAbort(process, prepared.configuration)
            return@withLock failedStart(
                "aria2 was stopped because XDM could not persist its runtime ownership lease.",
                Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.RuntimeOwnership,
                    detail = "Authenticated aria2 started, but the app-private runtime ownership lease could not be persisted.",
                    logTail = safeLogTail(),
                ),
            )
        }
        val running = Aria2ProcessState.Running(
            endpoint = prepared.endpoint,
            version = readiness.version,
            processId = process.processId,
            secretGeneration = prepared.secretGeneration,
            startedAtEpochMs = prepared.startedAtEpochMs,
            orphanRecovery = persistedRuntime.status,
        )
        _state.value = running
        observeExit(process)
        Aria2StartResult(started = true, alreadyRunning = false, state = running)
    }

    suspend fun repair(): Aria2StartResult {
        stop()
        sessionStore.cleanupTransientLaunchConfigurations()
        val rotatable = secretProvider as? Aria2RotatableSecretProvider
            ?: return failedStart(
                "aria2 repair could not rotate the private RPC secret.",
                Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.AuthenticationBoundary,
                    detail = "Configured secret provider does not support rotation.",
                    logTail = safeLogTail(),
                ),
            )
        runCatching { rotatable.rotate() }.getOrElse { error ->
            return failedStart(
                "aria2 repair could not rotate the private RPC secret: ${safeMessage(error)}",
                diagnosticFor(error, kindOverride = Aria2StartupFailureKind.AuthenticationBoundary),
            )
        }
        return start()
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
            val persistedRuntime = reconcilePersistedRuntime()
            if (persistedRuntime.failure != null) {
                _state.value = Aria2ProcessState.Failed(
                    message = "aria2 persisted-runtime stop failed: ${persistedRuntime.failure.detail}",
                    diagnostic = persistedRuntime.failure,
                )
                return@withLock Aria2StopResult(clean = false, forced = false, sessionSaved = false, exitCode = null)
            }
            _state.value = Aria2ProcessState.Stopped
            return@withLock Aria2StopResult(clean = true, forced = false, sessionSaved = persistedRuntime.sessionSaved, exitCode = null)
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
        val leaseCleared = if (!process.isAlive) sessionStore.clearRuntimeLease() else false
        val clean = exitCode == 0 && sessionSaved && !forced && leaseCleared
        _state.value = if (leaseCleared || !sessionStore.supportsRuntimeLease) {
            Aria2ProcessState.Stopped
        } else {
            Aria2ProcessState.Failed(
                message = "aria2 stopped, but XDM could not clear its runtime ownership lease.",
                diagnostic = Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.RuntimeOwnership,
                    detail = "The managed process stopped but its app-private runtime ownership lease could not be removed.",
                    exitCode = exitCode,
                    logTail = safeLogTail(),
                ),
            )
        }
        Aria2StopResult(clean, forced, sessionSaved, exitCode)
    }

    suspend fun rpc(): Aria2RpcControl {
        val started = start()
        check(started.started && started.state is Aria2ProcessState.Running) { describe(started.state) }
        return gate.withLock {
            val process = processReference.get()
            check(process?.isAlive == true) { "aria2 process exited before RPC acquisition" }
            requireNotNull(rpcControl) { "aria2 RPC channel is unavailable" }
        }
    }

    suspend fun activeTaskIds(): List<String> = runCatching {
        val rpc = rpc()
        (rpc.tellActive() + rpc.tellWaiting() + rpc.tellStopped()).map(Aria2TaskStatus::gid).distinct()
    }.getOrDefault(emptyList())

    suspend fun smokeTest(): Aria2SmokeTestResult {
        val start = start()
        val running = start.state as? Aria2ProcessState.Running
            ?: return Aria2SmokeTestResult(false, describe(start.state))
        val lifecycle = runCatching { rpcLifecycleProbe(sessionStore.rootDirectory) }.getOrElse { error ->
            Aria2RpcLifecycleProbeResult(false, "aria2 RPC lifecycle probe failed: ${safeMessage(error)}")
        }
        val stop = if (start.alreadyRunning) null else stop()
        val successful = lifecycle.successful && (stop == null || stop.clean)
        val summary = buildString {
            append("aria2 ")
            append(running.version.version)
            append(" authenticated on loopback; unauthenticated RPC was rejected. ")
            append(lifecycle.summary)
            if (stop != null) append(if (stop.clean) " Shutdown was clean." else " Shutdown required recovery handling.")
            else append(" The existing managed process was left running.")
        }
        return Aria2SmokeTestResult(successful, summary, running.version)
    }

    suspend fun storageProbe(directory: File): Aria2StorageProbeResult {
        val target = runCatching { directory.canonicalFile }.getOrElse { error ->
            return Aria2StorageProbeResult(false, "aria2 storage probe could not resolve the target directory: ${safeMessage(error)}")
        }
        if ((!target.isDirectory && !target.mkdirs()) || !target.canWrite()) {
            return Aria2StorageProbeResult(false, "aria2 storage probe target is not writable: ${target.absolutePath}")
        }
        val start = start()
        if (start.state !is Aria2ProcessState.Running) {
            return Aria2StorageProbeResult(false, "aria2 storage probe could not start the embedded runtime: ${describe(start.state)}")
        }
        val probe = runCatching { rpcLifecycleProbe(target) }.getOrElse { error ->
            Aria2RpcLifecycleProbeResult(false, "aria2 storage probe failed: ${safeMessage(error)}")
        }
        if (!start.alreadyRunning) stop()
        return Aria2StorageProbeResult(
            successful = probe.successful,
            summary = probe.summary,
            outputPath = target.absolutePath,
        )
    }

    private suspend fun rpcLifecycleProbe(directory: File): Aria2RpcLifecycleProbeResult {
        val rpc = rpc()
        val payload = "XDM aria2 RPC lifecycle probe\n".toByteArray(Charsets.UTF_8)
        val output = File(directory, ".xdm-aria2-probe-${UUID.randomUUID()}.bin")
        var gid: String? = null
        LoopbackProbeServer(payload, responseDelayMillis = 800L).use { server ->
            try {
                gid = rpc.addUri(
                    listOf(server.url),
                    Aria2TaskOptions(
                        directory = directory.canonicalPath,
                        outputName = output.name,
                        pause = true,
                        continueDownload = false,
                        split = 1,
                        maxConnectionsPerServer = 1,
                    ),
                )
                val created = rpc.tellStatus(requireNotNull(gid))
                check(created.status == Aria2TaskStatusValue.Paused || created.status == Aria2TaskStatusValue.Waiting) {
                    "aria2.addUri did not create a paused probe task (status=${created.status})"
                }
                rpc.unpause(requireNotNull(gid))
                delay(100L)
                val afterResume = rpc.tellStatus(requireNotNull(gid))
                if (afterResume.status != Aria2TaskStatusValue.Complete) {
                    rpc.pause(requireNotNull(gid), force = true)
                    val paused = rpc.tellStatus(requireNotNull(gid))
                    check(paused.status == Aria2TaskStatusValue.Paused || paused.status == Aria2TaskStatusValue.Waiting) {
                        "aria2 pause probe did not reach a paused state (status=${paused.status})"
                    }
                    rpc.unpause(requireNotNull(gid))
                }
                val terminal = withTimeoutOrNull(5_000L) {
                    while (true) {
                        val status = rpc.tellStatus(requireNotNull(gid))
                        if (status.status == Aria2TaskStatusValue.Complete || status.status == Aria2TaskStatusValue.Error || status.status == Aria2TaskStatusValue.Removed) {
                            return@withTimeoutOrNull status
                        }
                        delay(50L)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                } ?: error("aria2 probe transfer did not reach a terminal state")
                check(terminal.status == Aria2TaskStatusValue.Complete) {
                    "aria2 probe transfer ended as ${terminal.status}: ${terminal.errorMessage.orEmpty()}"
                }
                check(output.isFile) { "aria2 reported completion but did not create the probe file" }
                val actual = output.readBytes()
                check(actual.contentEquals(payload)) { "aria2 probe output did not match the loopback payload" }
                check(rpc.saveSession()) { "aria2.saveSession did not report success" }
                rpc.removeDownloadResult(requireNotNull(gid))
                gid = null
                return Aria2RpcLifecycleProbeResult(
                    successful = true,
                    summary = "RPC lifecycle passed: addUri, tellStatus, pause/resume, local transfer, saveSession, and result cleanup all succeeded.",
                    outputBytes = actual.size.toLong(),
                )
            } finally {
                gid?.let { activeGid ->
                    runCatching { rpc.remove(activeGid, force = true) }
                    runCatching { rpc.removeDownloadResult(activeGid) }
                }
                output.delete()
                File(output.parentFile, output.name + ".aria2").delete()
            }
        }
    }


    private suspend fun reconcilePersistedRuntime(): PersistedRuntimeReconciliation {
        if (!sessionStore.supportsRuntimeLease) return PersistedRuntimeReconciliation()
        val lease = sessionStore.readRuntimeLease() ?: return PersistedRuntimeReconciliation()
        val secret = runCatching { secretProvider.getOrCreate() }.getOrElse { error ->
            return PersistedRuntimeReconciliation(
                failure = diagnosticFor(error, kindOverride = Aria2StartupFailureKind.RuntimeOwnership),
            )
        }
        val generation = runCatching { secretProvider.generation() }.getOrDefault(0L)
        if (lease.secretGeneration != generation) {
            if (!sessionStore.clearRuntimeLease()) {
                return PersistedRuntimeReconciliation(
                    failure = Aria2StartupDiagnostic(
                        kind = Aria2StartupFailureKind.RuntimeOwnership,
                        detail = "A stale aria2 ownership marker was found, but XDM could not clear it safely.",
                        logTail = safeLogTail(),
                    ),
                )
            }
            return PersistedRuntimeReconciliation(status = Aria2OrphanRecovery.ClearedStaleMarker)
        }
        val rpc = runCatching { rpcFactory.create(lease.endpoint, secret) }.getOrElse { error ->
            return PersistedRuntimeReconciliation(
                failure = diagnosticFor(error, kindOverride = Aria2StartupFailureKind.OrphanRecovery),
            )
        }
        val ownedDaemonIsReachable = runCatching { rpc.getVersion(); true }.getOrDefault(false)
        if (!ownedDaemonIsReachable) {
            if (!sessionStore.clearRuntimeLease()) {
                return PersistedRuntimeReconciliation(
                    failure = Aria2StartupDiagnostic(
                        kind = Aria2StartupFailureKind.RuntimeOwnership,
                        detail = "An unreachable aria2 ownership marker could not be cleared.",
                        logTail = safeLogTail(),
                    ),
                )
            }
            return PersistedRuntimeReconciliation(status = Aria2OrphanRecovery.ClearedStaleMarker)
        }

        // The daemon answered authenticated RPC using the still-current private secret and persisted
        // generation, which is the ownership proof available across an Android app-process restart.
        val sessionSaved = runCatching { rpc.saveSession() }.getOrDefault(false)
        val shutdownRequested = runCatching { rpc.shutdown(force = false); true }.getOrDefault(false)
        if (!shutdownRequested) {
            return PersistedRuntimeReconciliation(
                failure = Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.OrphanRecovery,
                    detail = "XDM proved ownership of a persisted aria2 daemon but graceful RPC shutdown failed.",
                    logTail = safeLogTail(),
                ),
                sessionSaved = sessionSaved,
            )
        }
        var stopped = waitUntilRpcStops(rpc, 1_500L)
        if (!stopped) {
            runCatching { rpc.shutdown(force = true) }
            stopped = waitUntilRpcStops(rpc, 750L)
        }
        if (!stopped) {
            return PersistedRuntimeReconciliation(
                failure = Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.OrphanRecovery,
                    detail = "An authenticated XDM-owned orphan aria2 daemon did not stop after graceful and forced RPC shutdown requests.",
                    logTail = safeLogTail(),
                ),
                sessionSaved = sessionSaved,
            )
        }
        if (!sessionStore.clearRuntimeLease()) {
            return PersistedRuntimeReconciliation(
                failure = Aria2StartupDiagnostic(
                    kind = Aria2StartupFailureKind.RuntimeOwnership,
                    detail = "The recovered aria2 daemon stopped, but its runtime ownership lease could not be cleared.",
                    logTail = safeLogTail(),
                ),
                sessionSaved = sessionSaved,
            )
        }
        return PersistedRuntimeReconciliation(
            status = Aria2OrphanRecovery.RecoveredOwnedDaemon,
            sessionSaved = sessionSaved,
        )
    }

    private suspend fun waitUntilRpcStops(rpc: Aria2RpcControl, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val reachable = runCatching { rpc.getVersion(); true }.getOrDefault(false)
                if (!reachable) return@withTimeoutOrNull true
                delay(50L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    private fun prepareLaunch(report: Aria2CapabilityReport): PreparedLaunch? {
        var configuration: File? = null
        return try {
            sessionStore.prepare()
            val endpoint = Aria2Endpoint(portAllocator.allocate())
            val secret = secretProvider.getOrCreate()
            val secretGeneration = secretProvider.generation()
            val startedAtEpochMs = System.currentTimeMillis()
            configuration = sessionStore.writeLaunchConfiguration(endpoint, secret)
            PreparedLaunch(
                endpoint = endpoint,
                secret = secret,
                secretGeneration = secretGeneration,
                startedAtEpochMs = startedAtEpochMs,
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

    private suspend fun waitForRpc(process: Aria2ManagedProcess, rpc: Aria2RpcControl): RpcReadiness {
        var lastFailure: Throwable? = null
        val version = withTimeoutOrNull(startupTimeoutMillis) {
            while (process.isAlive) {
                try {
                    return@withTimeoutOrNull rpc.getVersion()
                } catch (error: Throwable) {
                    lastFailure = error
                }
                delay(pollIntervalMillis)
            }
            null
        }
        val exitCode = if (!process.isAlive) withTimeoutOrNull(250) { process.awaitExit() } else null
        return RpcReadiness(
            version = version,
            lastFailure = lastFailure,
            exitCode = exitCode,
            timedOut = version == null && process.isAlive,
        )
    }

    private fun observeExit(process: Aria2ManagedProcess) {
        scope.launch {
            val exitCode = runCatching { process.awaitExit() }.getOrNull()
            gate.withLock {
                if (processReference.compareAndSet(process, null)) {
                    rpcControl = null
                    sessionStore.clearRuntimeLease()
                    if (_state.value !is Aria2ProcessState.Stopping) {
                        _state.value = if (exitCode == 0) {
                            Aria2ProcessState.Stopped
                        } else {
                            Aria2ProcessState.Failed(
                                message = "aria2 exited unexpectedly${exitCode?.let { " with code $it" }.orEmpty()}.",
                                diagnostic = Aria2StartupDiagnostic(
                                    kind = Aria2StartupFailureKind.ProcessExited,
                                    detail = "Managed aria2 process exited outside an XDM stop request.",
                                    exitCode = exitCode,
                                    logTail = safeLogTail(),
                                ),
                            )
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
        sessionStore.clearRuntimeLease()
    }

    private fun failedStart(message: String, diagnostic: Aria2StartupDiagnostic? = null): Aria2StartResult {
        val failed = Aria2ProcessState.Failed(message, diagnostic)
        _state.value = failed
        return Aria2StartResult(started = false, alreadyRunning = false, state = failed)
    }

    private fun diagnosticFor(
        error: Throwable?,
        exitCode: Int? = null,
        timedOut: Boolean = false,
        kindOverride: Aria2StartupFailureKind? = null,
    ): Aria2StartupDiagnostic {
        val logTail = safeLogTail()
        val kind = kindOverride ?: when {
            logTail.looksLikeBinaryLoadFailure() -> Aria2StartupFailureKind.BinaryLoadFailure
            logTail.looksLikeInvalidConfiguration() -> Aria2StartupFailureKind.ConfigurationInvalid
            logTail.looksLikePortFailure() -> Aria2StartupFailureKind.PortUnavailable
            error is Aria2RpcProtocolException -> Aria2StartupFailureKind.MalformedResponse
            error is ConnectException -> Aria2StartupFailureKind.ConnectionRefused
            error is SocketTimeoutException -> Aria2StartupFailureKind.Timeout
            error is Aria2RpcException && error.code == 1 && error.message.orEmpty().contains("unauthor", ignoreCase = true) -> Aria2StartupFailureKind.Unauthorized
            error is Aria2RpcException -> Aria2StartupFailureKind.RpcFailure
            error?.message.orEmpty().contains("RPC HTTP", ignoreCase = true) -> Aria2StartupFailureKind.HttpFailure
            exitCode != null -> Aria2StartupFailureKind.ProcessExited
            timedOut -> Aria2StartupFailureKind.Timeout
            else -> Aria2StartupFailureKind.Unknown
        }
        val detail = when (kind) {
            Aria2StartupFailureKind.ConnectionRefused -> "Loopback connection was refused before authenticated RPC became ready."
            Aria2StartupFailureKind.Unauthorized -> "aria2 rejected XDM's current private RPC secret."
            Aria2StartupFailureKind.HttpFailure -> safeMessage(error ?: IllegalStateException("HTTP failure"))
            Aria2StartupFailureKind.RpcFailure -> safeMessage(error ?: IllegalStateException("RPC failure"))
            Aria2StartupFailureKind.MalformedResponse -> safeMessage(error ?: IllegalStateException("Malformed aria2 RPC response"))
            Aria2StartupFailureKind.ConfigurationInvalid -> "aria2 rejected or could not parse its generated launch configuration."
            Aria2StartupFailureKind.PortUnavailable -> "aria2 could not bind the allocated loopback RPC port."
            Aria2StartupFailureKind.BinaryLoadFailure -> "Android could not load or execute the packaged aria2 binary or one of its native dependencies."
            Aria2StartupFailureKind.ProcessExited -> "aria2 exited before authenticated RPC became ready${exitCode?.let { " (code $it)" }.orEmpty()}."
            Aria2StartupFailureKind.Timeout -> "aria2 stayed alive but authenticated RPC did not become ready before the startup timeout."
            Aria2StartupFailureKind.LaunchFailure -> safeMessage(error ?: IllegalStateException("Launch failure"))
            Aria2StartupFailureKind.ConfigurationCleanup -> "Temporary launch configuration cleanup failed."
            Aria2StartupFailureKind.AuthenticationBoundary -> safeMessage(error ?: IllegalStateException("Authentication boundary check failed"))
            Aria2StartupFailureKind.RuntimeOwnership -> safeMessage(error ?: IllegalStateException("Runtime ownership lease failure"))
            Aria2StartupFailureKind.OrphanRecovery -> safeMessage(error ?: IllegalStateException("Owned orphan aria2 recovery failure"))
            Aria2StartupFailureKind.Unknown -> error?.let(::safeMessage) ?: "No authenticated RPC response was received."
        }
        return Aria2StartupDiagnostic(kind, detail, exitCode, logTail)
    }

    private fun String?.looksLikeBinaryLoadFailure(): Boolean {
        val text = this.orEmpty().lowercase()
        return listOf("cannot link executable", "dlopen failed", "linker", "library not found", "needed by").any(text::contains)
    }

    private fun String?.looksLikeInvalidConfiguration(): Boolean {
        val text = this.orEmpty().lowercase()
        return listOf("unknown option", "unrecognized option", "failed to parse", "configuration file error", "option error").any(text::contains)
    }

    private fun String?.looksLikePortFailure(): Boolean {
        val text = this.orEmpty().lowercase()
        return listOf("address already in use", "failed to bind", "cannot bind", "listen port").any(text::contains)
    }

    private fun safeLogTail(): String? = sessionStore.readRuntimeLogTail(4096)?.let(::redactRuntimeText)

    private fun safeMessage(error: Throwable): String = redactRuntimeText(error.message ?: error::class.java.simpleName)

    private fun redactRuntimeText(value: String): String = value
        .replace(Regex("token:[^\\s,]+"), "token:<redacted>")
        .replace(Regex("rpc-secret=[^\\s,]+"), "rpc-secret=<redacted>")
        .replace(Regex("(?i)(authorization|cookie):\\s*[^\\r\\n]+"), "$1: <redacted>")
        .replace(Regex("(?i)bearer\\s+[^\\s,]+"), "Bearer <redacted>")
        .replace(Regex("([?&][^=\\s&]+)=([^\\s&]+)"), "$1=<redacted>")
        .take(4096)

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
        val secretGeneration: Long,
        val startedAtEpochMs: Long,
        val configuration: File,
        val plan: Aria2LaunchPlan,
    )

    private data class PersistedRuntimeReconciliation(
        val status: Aria2OrphanRecovery = Aria2OrphanRecovery.None,
        val failure: Aria2StartupDiagnostic? = null,
        val sessionSaved: Boolean = true,
    )

    private data class RpcReadiness(
        val version: Aria2Version?,
        val lastFailure: Throwable?,
        val exitCode: Int?,
        val timedOut: Boolean,
    )

    private class LoopbackProbeServer(
        private val payload: ByteArray,
        private val responseDelayMillis: Long,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:${server.localPort}/xdm-aria2-probe"
        private val thread = Thread({
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    if (responseDelayMillis > 0L) Thread.sleep(responseDelayMillis)
                    socket.getOutputStream().buffered().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(payload)
                        output.flush()
                    }
                }
            }
        }, "xdm-aria2-probe-server").apply {
            isDaemon = true
            start()
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { thread.join(1_000L) }
        }
    }
}
