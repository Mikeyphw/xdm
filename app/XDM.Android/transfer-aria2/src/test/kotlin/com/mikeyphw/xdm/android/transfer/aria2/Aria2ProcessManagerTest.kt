package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import java.io.File
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
) : Aria2RuntimeFiles {
    val configuration = rootDirectory.resolve("launch.conf")
    var configurationDeleted = false
    var deleteAttempts = 0

    override fun prepare() {
        if (failPreparation) error("private runtime directory unavailable at ${rootDirectory.absolutePath}")
        rootDirectory.mkdirs()
    }

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
    override suspend fun getVersion() = Aria2Version("1.37.0", setOf("Async DNS", "BitTorrent"))
    override suspend fun addUri(uris: List<String>, options: Aria2TaskOptions): String = "gid"
    override suspend fun pause(gid: String, force: Boolean) = Unit
    override suspend fun unpause(gid: String) = Unit
    override suspend fun remove(gid: String, force: Boolean) = Unit
    override suspend fun tellStatus(gid: String) = taskStatus(gid, Aria2TaskStatusValue.Paused)
    override suspend fun tellActive(): List<Aria2TaskStatus> = emptyList()
    override suspend fun tellWaiting(offset: Int, count: Int): List<Aria2TaskStatus> = emptyList()
    override suspend fun tellStopped(offset: Int, count: Int): List<Aria2TaskStatus> = emptyList()
    override suspend fun removeDownloadResult(gid: String) = Unit
    override suspend fun saveSession(): Boolean = true
    override suspend fun shutdown(force: Boolean) { process.complete(if (force) 137 else 0) }
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
