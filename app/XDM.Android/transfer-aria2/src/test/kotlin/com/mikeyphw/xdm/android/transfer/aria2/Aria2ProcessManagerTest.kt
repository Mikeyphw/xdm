package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2ProcessManagerTest {
    @Test
    fun rpcSecretIsAlwaysFirstAndNeverRendered() {
        val secret = Aria2RpcSecret.from("0123456789abcdef0123456789abcdef")
        val control = OkHttpAria2RpcControl(
            Aria2Endpoint(6800),
            secret,
            OkHttpClient(),
        )

        val parameters = control.authenticatedParameters(JsonArray(listOf(JsonPrimitive("gid"))))

        assertEquals("\"token:0123456789abcdef0123456789abcdef\"", parameters.first().toString())
        assertEquals("Aria2RpcSecret(<redacted>)", secret.toString())
        assertFalse(secret.toString().contains("012345"))
    }

    @Test
    fun startsAuthenticatedLoopbackRuntimeAndStopsCleanly() = runTest {
        val root = Files.createTempDirectory("aria2-runtime-test").toFile()
        val files = FakeRuntimeFiles(root)
        val process = FakeManagedProcess()
        val rpc = FakeRpcControl(process)
        var launchPlan: Aria2LaunchPlan? = null
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = files,
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            portAllocator = Aria2PortAllocator { 45678 },
            processLauncher = Aria2ProcessLauncher { plan -> launchPlan = plan; process },
            rpcFactory = Aria2RpcControlFactory { endpoint, _ ->
                assertEquals("http://127.0.0.1:45678/jsonrpc", endpoint.url)
                rpc
            },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 500,
            shutdownTimeoutMillis = 500,
            pollIntervalMillis = 1,
        )

        val start = manager.start()
        assertTrue(start.started)
        assertFalse(start.alreadyRunning)
        val running = start.state as Aria2ProcessState.Running
        assertEquals("1.37.0", running.version.version)
        assertEquals(listOf(root.resolve("libaria2c.so").absolutePath, "--conf-path=${files.configuration.absolutePath}"), launchPlan?.command)
        assertFalse(launchPlan.toString().contains("0123456789abcdef"))
        assertTrue(files.configurationDeleted)

        val stop = manager.stop()
        assertTrue(stop.clean)
        assertTrue(stop.sessionSaved)
        assertFalse(stop.forced)
        assertEquals(0, stop.exitCode)
        assertEquals(Aria2ProcessState.Stopped, manager.state.value)
    }

    @Test
    fun smokeTestExercisesAuthenticatedRpcLifecycleAndCleanShutdown() = runTest {
        val root = Files.createTempDirectory("aria2-smoke-lifecycle").toFile()
        val files = FakeRuntimeFiles(root)
        val process = FakeManagedProcess()
        val rpc = FakeRpcControl(process)
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = files,
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            processLauncher = Aria2ProcessLauncher { process },
            rpcFactory = Aria2RpcControlFactory { _, _ -> rpc },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 500,
            shutdownTimeoutMillis = 500,
            pollIntervalMillis = 1,
        )

        val result = manager.smokeTest()

        assertTrue(result.summary, result.successful)
        assertTrue(rpc.events.contains("add"))
        assertTrue(rpc.events.contains("tell"))
        assertTrue(rpc.events.contains("unpause"))
        assertTrue(rpc.events.contains("pause"))
        assertTrue(rpc.events.contains("save"))
        assertTrue(rpc.events.contains("remove-result"))
        assertTrue(rpc.events.contains("shutdown"))
    }

    @Test
    fun storageProbeMakesAria2WriteIntoRequestedDirectory() = runTest {
        val root = Files.createTempDirectory("aria2-storage-probe-runtime").toFile()
        val destination = Files.createTempDirectory("aria2-storage-probe-destination").toFile()
        val files = FakeRuntimeFiles(root)
        val process = FakeManagedProcess()
        val rpc = FakeRpcControl(process)
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = files,
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            processLauncher = Aria2ProcessLauncher { process },
            rpcFactory = Aria2RpcControlFactory { _, _ -> rpc },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 500,
            shutdownTimeoutMillis = 500,
            pollIntervalMillis = 1,
        )

        val result = manager.storageProbe(destination)

        assertTrue(result.summary, result.successful)
        assertEquals(destination.canonicalPath, rpc.lastOptions?.directory)
        assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".xdm-aria2-probe-") })
    }


    @Test
    fun appRestartReclaimsOnlyPersistedAuthenticatedOwnedDaemon() = runTest {
        val root = Files.createTempDirectory("aria2-app-restart-orphan").toFile()
        val files = FakeRuntimeFiles(root)
        val secrets = FakeRotatableSecretProvider()
        val oldProcess = FakeManagedProcess()
        val oldRpc = FakeRpcControl(oldProcess)
        files.runtimeLease = Aria2RuntimeLease(
            endpoint = Aria2Endpoint(45678),
            secretGeneration = secrets.generation(),
            startedAtEpochMs = 1234L,
        )

        val newProcess = FakeManagedProcess()
        val newRpc = FakeRpcControl(newProcess)
        val newManager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = files,
            secretProvider = secrets,
            portAllocator = Aria2PortAllocator { 45679 },
            processLauncher = Aria2ProcessLauncher { newProcess },
            rpcFactory = Aria2RpcControlFactory { endpoint, _ -> if (endpoint.port == 45678) oldRpc else newRpc },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 500,
            shutdownTimeoutMillis = 500,
            pollIntervalMillis = 1,
        )

        val restarted = newManager.start()

        assertTrue(restarted.started)
        assertFalse(oldProcess.isAlive)
        val running = restarted.state as Aria2ProcessState.Running
        assertEquals(45679, running.endpoint.port)
        assertEquals(Aria2OrphanRecovery.RecoveredOwnedDaemon, running.orphanRecovery)
        assertEquals(45679, files.runtimeLease?.endpoint?.port)
        assertTrue(oldRpc.events.contains("shutdown"))
        newManager.stop()
    }

    @Test
    fun unavailableRuntimeNeverLaunchesProcess() = runTest {
        val root = Files.createTempDirectory("aria2-unavailable-test").toFile()
        var launches = 0
        val report = Aria2CapabilityReport(Aria2Availability.BinaryMissing, "Runtime missing")
        val manager = Aria2ProcessManager(
            capabilityProbe = Aria2CapabilityProbe { report },
            sessionStore = FakeRuntimeFiles(root),
            secretProvider = Aria2SecretProvider { error("secret should not be requested") },
            processLauncher = Aria2ProcessLauncher { launches += 1; error("must not launch") },
            rpcFactory = Aria2RpcControlFactory { _, _ -> error("must not create RPC") },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
        )

        val result = manager.start()

        assertFalse(result.started)
        assertEquals(0, launches)
        assertEquals(Aria2ProcessState.Unavailable(report), result.state)
    }


    @Test
    fun preparationFailureReturnsSafeFailedStateWithoutLaunching() = runTest {
        val root = Files.createTempDirectory("aria2-preparation-failure").toFile()
        var launches = 0
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = FakeRuntimeFiles(root, failPreparation = true),
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            processLauncher = Aria2ProcessLauncher { launches += 1; error("must not launch") },
            rpcFactory = Aria2RpcControlFactory { _, _ -> error("must not create RPC") },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
        )

        val result = manager.start()

        assertFalse(result.started)
        assertEquals(0, launches)
        assertTrue(result.state is Aria2ProcessState.Failed)
        assertFalse((result.state as Aria2ProcessState.Failed).message.contains(root.absolutePath))
    }

    @Test
    fun startupAbortsWhenTemporarySecretConfigurationCannotBeRemoved() = runTest {
        val root = Files.createTempDirectory("aria2-cleanup-failure").toFile()
        val files = FakeRuntimeFiles(root, deleteSucceeds = false)
        val process = FakeManagedProcess()
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = files,
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            portAllocator = Aria2PortAllocator { 45678 },
            processLauncher = Aria2ProcessLauncher { process },
            rpcFactory = Aria2RpcControlFactory { _, _ -> FakeRpcControl(process) },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 500,
            pollIntervalMillis = 1,
        )

        val result = manager.start()

        assertFalse(result.started)
        assertTrue(result.state is Aria2ProcessState.Failed)
        assertFalse(process.isAlive)
        assertTrue(files.deleteAttempts >= 2)
    }

    @Test
    fun startupClassifiesUnauthorizedRpcFailureInsteadOfCollapsingIt() = runTest {
        val root = Files.createTempDirectory("aria2-auth-failure").toFile()
        val process = FakeManagedProcess()
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = FakeRuntimeFiles(root),
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            processLauncher = Aria2ProcessLauncher { process },
            rpcFactory = Aria2RpcControlFactory { _, _ -> FailingRpcControl(Aria2RpcException(1, "Unauthorized")) },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 25,
            pollIntervalMillis = 1,
        )

        val result = manager.start()

        assertFalse(result.started)
        val failed = result.state as Aria2ProcessState.Failed
        assertEquals(Aria2StartupFailureKind.Unauthorized, failed.diagnostic?.kind)
        assertTrue(failed.message.contains("Unauthorized"))
        process.complete(137)
    }

    @Test
    fun startupClassifiesMalformedRpcResponse() = runTest {
        val root = Files.createTempDirectory("aria2-malformed-rpc").toFile()
        val process = FakeManagedProcess()
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = FakeRuntimeFiles(root),
            secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
            processLauncher = Aria2ProcessLauncher { process },
            rpcFactory = Aria2RpcControlFactory { _, _ -> FailingRpcControl(Aria2RpcProtocolException("malformed JSON")) },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 25,
            pollIntervalMillis = 1,
        )

        val result = manager.start()

        assertFalse(result.started)
        assertEquals(Aria2StartupFailureKind.MalformedResponse, (result.state as Aria2ProcessState.Failed).diagnostic?.kind)
        process.complete(137)
    }

    @Test
    fun startupUsesRuntimeLogToClassifyConfigPortAndLinkerFailures() = runTest {
        suspend fun classify(log: String): Aria2StartupFailureKind {
            val root = Files.createTempDirectory("aria2-log-classifier").toFile()
            val process = FakeManagedProcess()
            val manager = Aria2ProcessManager(
                capabilityProbe = availableProbe(root),
                sessionStore = FakeRuntimeFiles(root, runtimeLogTail = log),
                secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
                processLauncher = Aria2ProcessLauncher { process },
                rpcFactory = Aria2RpcControlFactory { _, _ -> FailingRpcControl(ConnectException("refused")) },
                authenticationProbe = Aria2RpcAuthenticationProbe { true },
                scope = this,
                startupTimeoutMillis = 20,
                pollIntervalMillis = 1,
            )
            val result = manager.start()
            process.complete(137)
            return (result.state as Aria2ProcessState.Failed).diagnostic!!.kind
        }

        assertEquals(Aria2StartupFailureKind.ConfigurationInvalid, classify("Unknown option: rpc-bogus"))
        assertEquals(Aria2StartupFailureKind.PortUnavailable, classify("Address already in use while binding RPC listen port"))
        assertEquals(Aria2StartupFailureKind.BinaryLoadFailure, classify("CANNOT LINK EXECUTABLE: library not found"))
    }

    @Test
    fun repairRotatesSecretAndClearsTransientConfigurationsBeforeRestart() = runTest {
        val root = Files.createTempDirectory("aria2-repair").toFile()
        val files = FakeRuntimeFiles(root)
        val process = FakeManagedProcess()
        val secrets = FakeRotatableSecretProvider()
        val manager = Aria2ProcessManager(
            capabilityProbe = availableProbe(root),
            sessionStore = files,
            secretProvider = secrets,
            processLauncher = Aria2ProcessLauncher { process },
            rpcFactory = Aria2RpcControlFactory { _, _ -> FakeRpcControl(process) },
            authenticationProbe = Aria2RpcAuthenticationProbe { true },
            scope = this,
            startupTimeoutMillis = 100,
            pollIntervalMillis = 1,
        )

        val result = manager.repair()

        assertTrue(result.started)
        assertEquals(1, secrets.rotations)
        assertEquals(1L, (result.state as Aria2ProcessState.Running).secretGeneration)
        assertEquals(1, files.transientCleanupCalls)
    }

    private fun availableProbe(root: File): Aria2CapabilityProbe {
        val binary = root.resolve("libaria2c.so").also { it.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) }
        return Aria2CapabilityProbe {
            Aria2CapabilityReport(
                Aria2Availability.Available,
                "Ready",
                Aria2BinaryDescriptor(binary, ARIA2_PRIMARY_ABI, "abc"),
            )
        }
    }
}

private class FakeRuntimeFiles(
    override val rootDirectory: File,
    private val failPreparation: Boolean = false,
    private val deleteSucceeds: Boolean = true,
    private val runtimeLogTail: String? = null,
) : Aria2RuntimeFiles {
    val configuration = rootDirectory.resolve("launch.conf")
    var configurationDeleted = false
    var deleteAttempts = 0
    var transientCleanupCalls = 0
    var runtimeLease: Aria2RuntimeLease? = null
    override val supportsRuntimeLease: Boolean = true

    override fun prepare() {
        if (failPreparation) error("private runtime directory unavailable at ${rootDirectory.absolutePath}")
        rootDirectory.mkdirs()
    }

    override fun cleanupTransientLaunchConfigurations(): Int {
        transientCleanupCalls += 1
        return 0
    }

    override fun readRuntimeLogTail(maxChars: Int): String? = runtimeLogTail?.takeLast(maxChars)
    override fun readRuntimeLease(): Aria2RuntimeLease? = runtimeLease
    override fun writeRuntimeLease(lease: Aria2RuntimeLease): Boolean { runtimeLease = lease; return true }
    override fun clearRuntimeLease(): Boolean { runtimeLease = null; return true }

    override fun writeLaunchConfiguration(endpoint: Aria2Endpoint, secret: Aria2RpcSecret): File {
        prepare()
        configuration.writeText("port=${endpoint.port}\nsecret=${secret.configurationValue()}")
        return configuration
    }

    override fun deleteLaunchConfiguration(file: File): Boolean {
        deleteAttempts += 1
        if (!deleteSucceeds) return false
        configurationDeleted = file == configuration
        return !file.exists() || file.delete()
    }

    override val sessionFile: File = rootDirectory.resolve("xdm.session")

    override fun logFile(): File = rootDirectory.resolve("aria2.log").also { it.createNewFile() }

    override fun taskFiles(downloadId: String, output: File): Aria2TaskFiles {
        val directory = rootDirectory.resolve("tasks/$downloadId").also { it.mkdirs() }
        return Aria2TaskFiles(
            directory = directory,
            output = output,
            control = File(output.absolutePath + ".aria2"),
            ownershipMetadata = directory.resolve("ownership.json"),
            session = sessionFile,
        )
    }

    override fun writeOwnershipMetadata(files: Aria2TaskFiles, mapping: Aria2TaskMapping) {
        files.ownershipMetadata.writeText(mapping.gid)
    }

    override fun deleteTaskMetadata(files: Aria2TaskFiles) {
        files.ownershipMetadata.delete()
    }

    override fun artifactsFor(downloadId: String, fileName: String) = BackendArtifactIdentity(
        "test",
        rootDirectory.resolve("$downloadId-$fileName.part").toURI().toString(),
    )
}

private class FakeManagedProcess : Aria2ManagedProcess {
    private val exit = CompletableDeferred<Int>()
    override val processId: Long = 42
    override val isAlive: Boolean get() = !exit.isCompleted
    override suspend fun awaitExit(): Int = exit.await()
    override fun destroy() { exit.complete(0) }
    override fun destroyForcibly() { exit.complete(137) }
    fun complete(code: Int) { exit.complete(code) }
}

private class FakeRpcControl(private val process: FakeManagedProcess) : Aria2RpcControl {
    val events = mutableListOf<String>()
    var lastOptions: Aria2TaskOptions? = null
    private var status = Aria2TaskStatusValue.Paused
    private var unpauseCount = 0

    override suspend fun getVersion(): Aria2Version {
        if (!process.isAlive) throw ConnectException("aria2 process is no longer reachable")
        return Aria2Version("1.37.0", setOf("Async DNS", "BitTorrent"))
    }
    override suspend fun addUri(uris: List<String>, options: Aria2TaskOptions): String {
        events += "add"
        lastOptions = options
        status = if (options.pause) Aria2TaskStatusValue.Paused else Aria2TaskStatusValue.Active
        return "gid"
    }
    override suspend fun pause(gid: String, force: Boolean) {
        events += "pause"
        status = Aria2TaskStatusValue.Paused
    }
    override suspend fun unpause(gid: String) {
        events += "unpause"
        unpauseCount += 1
        status = if (unpauseCount >= 2) {
            val options = requireNotNull(lastOptions)
            val output = File(options.directory, options.outputName)
            output.parentFile?.mkdirs()
            output.writeText("XDM aria2 RPC lifecycle probe\n")
            Aria2TaskStatusValue.Complete
        } else {
            Aria2TaskStatusValue.Active
        }
    }
    override suspend fun remove(gid: String, force: Boolean) {
        events += "remove"
        status = Aria2TaskStatusValue.Removed
    }
    override suspend fun tellStatus(gid: String): Aria2TaskStatus {
        events += "tell"
        return taskStatus(gid, status)
    }
    override suspend fun tellActive(): List<Aria2TaskStatus> = emptyList()
    override suspend fun tellWaiting(offset: Int, count: Int): List<Aria2TaskStatus> = emptyList()
    override suspend fun tellStopped(offset: Int, count: Int): List<Aria2TaskStatus> = emptyList()
    override suspend fun removeDownloadResult(gid: String) { events += "remove-result" }
    override suspend fun saveSession(): Boolean { events += "save"; return true }
    override suspend fun shutdown(force: Boolean) { events += "shutdown"; process.complete(if (force) 137 else 0) }
}

private class FakeRotatableSecretProvider : Aria2RotatableSecretProvider {
    var rotations = 0
    private var current = "0123456789abcdef0123456789abcdef"
    override fun getOrCreate(): Aria2RpcSecret = Aria2RpcSecret.from(current)
    override fun generation(): Long = rotations.toLong()
    override fun rotate(): Aria2RpcSecret {
        rotations += 1
        current = "fedcba9876543210fedcba9876543210"
        return Aria2RpcSecret.from(current)
    }
}

private class FailingRpcControl(private val error: Throwable) : Aria2RpcControl {
    override suspend fun getVersion(): Aria2Version = throw error
    override suspend fun addUri(uris: List<String>, options: Aria2TaskOptions): String = throw error
    override suspend fun pause(gid: String, force: Boolean) = Unit
    override suspend fun unpause(gid: String) = Unit
    override suspend fun remove(gid: String, force: Boolean) = Unit
    override suspend fun tellStatus(gid: String): Aria2TaskStatus = throw error
    override suspend fun tellActive(): List<Aria2TaskStatus> = emptyList()
    override suspend fun tellWaiting(offset: Int, count: Int): List<Aria2TaskStatus> = emptyList()
    override suspend fun tellStopped(offset: Int, count: Int): List<Aria2TaskStatus> = emptyList()
    override suspend fun removeDownloadResult(gid: String) = Unit
    override suspend fun saveSession(): Boolean = true
    override suspend fun shutdown(force: Boolean) = Unit
}

private fun taskStatus(gid: String, status: Aria2TaskStatusValue) = Aria2TaskStatus(
    gid = gid,
    status = status,
    totalLength = 0,
    completedLength = 0,
    downloadSpeed = 0,
    dir = null,
    files = emptyList(),
)
