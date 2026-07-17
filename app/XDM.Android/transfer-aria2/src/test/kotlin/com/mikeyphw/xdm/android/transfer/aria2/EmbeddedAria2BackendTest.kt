package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendOwnership
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.storage.FileDestinationWriter
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.transfer.Aria2TaskMappingStore
import com.mikeyphw.xdm.android.transfer.DownloadRequest
import com.mikeyphw.xdm.android.transfer.InMemoryAria2TaskMappingStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAria2BackendTest {
    @Test
    fun taskRemainsPausedUntilOwnershipIsDurablyAttached() = runTest {
        val fixture = BackendFixture()
        val request = fixture.request(expectedLength = 4)
        val preparation = fixture.backend.prepare(request)

        val task = fixture.backend.add(request, preparation)

        assertTrue(task.requiresActivation)
        assertEquals(listOf("add:paused"), fixture.rpc.events.filter { it.startsWith("add:") || it.startsWith("unpause:") })
        assertEquals(0L, fixture.store.findByGid(task.taskId)?.ownershipGeneration)

        val ownership = fixture.ownership(preparation, task.taskId, generation = 41)
        fixture.backend.onOwnershipAttached(task.taskId, ownership)
        assertEquals(41L, fixture.store.findByGid(task.taskId)?.ownershipGeneration)
        assertEquals(listOf("add:paused"), fixture.rpc.events.filter { it.startsWith("add:") || it.startsWith("unpause:") })

        fixture.backend.activate(task.taskId)

        assertEquals(listOf("add:paused", "unpause:${task.taskId}"), fixture.rpc.events.filter { it.startsWith("add:") || it.startsWith("unpause:") })
        assertEquals(DownloadState.Downloading, fixture.backend.query(task.taskId)?.state)
        fixture.close()
    }

    @Test
    fun mappingFailureRemovesPausedGidBeforeItCanWrite() = runTest {
        val fixture = BackendFixture(mappingStore = FailingMappingStore())
        val request = fixture.request()
        val preparation = fixture.backend.prepare(request)

        val failure = runCatching { fixture.backend.add(request, preparation) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(fixture.rpc.events.contains("remove:gid-1"))
        assertTrue(fixture.rpc.events.contains("remove-result:gid-1"))
        assertFalse(fixture.rpc.events.any { it.startsWith("unpause:") })
        fixture.close()
    }

    @Test
    fun reconciliationRejectsAStaleOwnershipGeneration() = runTest {
        val fixture = BackendFixture()
        val request = fixture.request()
        val preparation = fixture.backend.prepare(request)
        val task = fixture.backend.add(request, preparation)
        fixture.backend.onOwnershipAttached(task.taskId, fixture.ownership(preparation, task.taskId, generation = 7))

        val stale = fixture.ownership(preparation, task.taskId, generation = 6)
        val result = fixture.backend.reconcile(stale)

        assertEquals(BackendReconciliationClassification.ConflictingArtifact, result.classification)
        assertFalse(result.safeToResume)
        fixture.close()
    }

    @Test
    fun validatedCompletionPromotesOnlyTheOwnedStagingFile() = runTest {
        val fixture = BackendFixture()
        val request = fixture.request(expectedLength = 4)
        val preparation = fixture.backend.prepare(request)
        val task = fixture.backend.add(request, preparation)
        fixture.backend.onOwnershipAttached(task.taskId, fixture.ownership(preparation, task.taskId, generation = 8))
        fixture.backend.activate(task.taskId)
        val mapping = requireNotNull(fixture.store.findByGid(task.taskId))
        File(mapping.outputPath).writeBytes(byteArrayOf(1, 2, 3, 4))
        fixture.rpc.complete(task.taskId, totalLength = 4)

        val snapshot = fixture.backend.query(task.taskId)

        assertEquals(DownloadState.Completed, snapshot?.state)
        assertTrue(fixture.finalFile.isFile)
        assertEquals(4L, fixture.finalFile.length())
        assertFalse(File(mapping.outputPath).exists())
        assertEquals("Completed", fixture.store.findByGid(task.taskId)?.status)
        fixture.close()
    }

    @Test
    fun changedAria2OutputPathIsQuarantinedDuringReconciliation() = runTest {
        val fixture = BackendFixture()
        val request = fixture.request()
        val preparation = fixture.backend.prepare(request)
        val task = fixture.backend.add(request, preparation)
        val ownership = fixture.ownership(preparation, task.taskId, generation = 9)
        fixture.backend.onOwnershipAttached(task.taskId, ownership)
        fixture.rpc.overrideOutput = fixture.root.resolve("foreign.part")

        val result = fixture.backend.reconcile(ownership)

        assertEquals(BackendReconciliationClassification.ConflictingArtifact, result.classification)
        assertFalse(result.safeToResume)
        fixture.close()
    }
}

private class BackendFixture(
    mappingStore: Aria2TaskMappingStore = InMemoryAria2TaskMappingStore(),
) {
    val root: File = Files.createTempDirectory("xdm-aria2-operational").toFile()
    val finalFile: File = root.resolve("final.bin")
    val store = mappingStore
    val runtimeIdentity = BackendRuntimeIdentity("install-1", "session-1")
    val runtimeFiles = TestRuntimeFiles(root.resolve("runtime"))
    private val process = TestManagedProcess()
    val rpc = TestRpcControl(process)
    private val manager = Aria2ProcessManager(
        capabilityProbe = availableProbe(root),
        sessionStore = runtimeFiles,
        secretProvider = Aria2SecretProvider { Aria2RpcSecret.from("0123456789abcdef0123456789abcdef") },
        portAllocator = Aria2PortAllocator { 49152 },
        processLauncher = Aria2ProcessLauncher { process },
        rpcFactory = Aria2RpcControlFactory { _, _ -> rpc },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        startupTimeoutMillis = 500,
        shutdownTimeoutMillis = 500,
        pollIntervalMillis = 1,
    )
    val backend = EmbeddedAria2Backend(
        processManager = manager,
        sessionStore = runtimeFiles,
        mappingStore = store,
        destinationWriter = FileDestinationWriter(),
        eventSource = object : Aria2TaskEventSource {
            override fun observe(gid: String) = flow { emit(rpc.tellStatus(gid)) }
        },
        runtimeIdentity = runtimeIdentity,
        clock = { 1000L },
    )

    fun request(expectedLength: Long? = null) = DownloadRequest(
        id = "download-1",
        sourceUrl = "https://example.test/archive.bin",
        destinationUri = finalFile.toURI().toString(),
        fileName = finalFile.name,
        preferredBackend = BackendType.Aria2,
        expectedLength = expectedLength,
    )

    fun ownership(
        preparation: com.mikeyphw.xdm.android.transfer.BackendPreparation,
        gid: String,
        generation: Long,
    ) = BackendOwnership(
        downloadId = "download-1",
        destinationKey = preparation.destinationKey,
        artifacts = preparation.artifacts,
        backend = BackendType.Aria2,
        generation = generation,
        status = BackendOwnershipStatus.Active,
        runtimeIdentity = runtimeIdentity,
        backendTaskId = gid,
        claimedAtEpochMs = 1000L,
        synchronizedAtEpochMs = 1000L,
    )

    suspend fun close() {
        manager.stop()
        root.deleteRecursively()
    }
}

private class TestRuntimeFiles(override val rootDirectory: File) : Aria2RuntimeFiles {
    override val sessionFile: File = rootDirectory.resolve("xdm.session")
    private val configuration = rootDirectory.resolve("launch.conf")

    override fun prepare() {
        rootDirectory.mkdirs()
        sessionFile.parentFile?.mkdirs()
        if (!sessionFile.exists()) sessionFile.createNewFile()
    }

    override fun writeLaunchConfiguration(endpoint: Aria2Endpoint, secret: Aria2RpcSecret): File {
        prepare()
        return configuration.also { it.writeText("rpc-listen-port=${endpoint.port}") }
    }

    override fun deleteLaunchConfiguration(file: File): Boolean = !file.exists() || file.delete()
    override fun logFile(): File = rootDirectory.resolve("aria2.log").also { it.parentFile?.mkdirs(); it.createNewFile() }

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
        files.ownershipMetadata.parentFile?.mkdirs()
        files.ownershipMetadata.writeText("${mapping.gid}:${mapping.ownershipGeneration}")
    }

    override fun deleteTaskMetadata(files: Aria2TaskFiles) {
        files.ownershipMetadata.delete()
    }

    override fun artifactsFor(downloadId: String, fileName: String) = taskFiles(
        downloadId,
        rootDirectory.resolve("staging/$fileName.xdm.aria2.part"),
    ).artifacts()
}

private class TestManagedProcess : Aria2ManagedProcess {
    private val exit = CompletableDeferred<Int>()
    override val processId: Long = 77
    override val isAlive: Boolean get() = !exit.isCompleted
    override suspend fun awaitExit(): Int = exit.await()
    override fun destroy() { exit.complete(0) }
    override fun destroyForcibly() { exit.complete(137) }
    fun complete(code: Int) { exit.complete(code) }
}

private class TestRpcControl(private val process: TestManagedProcess) : Aria2RpcControl {
    val events = mutableListOf<String>()
    private val statuses = linkedMapOf<String, Aria2TaskStatus>()
    private var outputPath: File? = null
    private var sourceUrl: String? = null
    var overrideOutput: File? = null

    override suspend fun getVersion() = Aria2Version("1.37.0", setOf("HTTPS", "Async DNS"))

    override suspend fun addUri(uris: List<String>, options: Aria2TaskOptions): String {
        val gid = "gid-${statuses.size + 1}"
        events += "add:${if (options.pause) "paused" else "active"}"
        outputPath = File(options.directory, options.outputName)
        sourceUrl = uris.first()
        statuses[gid] = status(gid, Aria2TaskStatusValue.Paused)
        return gid
    }

    override suspend fun pause(gid: String, force: Boolean) {
        events += "pause:$gid"
        statuses[gid] = status(gid, Aria2TaskStatusValue.Paused)
    }

    override suspend fun unpause(gid: String) {
        events += "unpause:$gid"
        statuses[gid] = status(gid, Aria2TaskStatusValue.Active)
    }

    override suspend fun remove(gid: String, force: Boolean) {
        events += "remove:$gid"
        statuses[gid] = status(gid, Aria2TaskStatusValue.Removed)
    }

    override suspend fun tellStatus(gid: String): Aria2TaskStatus {
        val stored = requireNotNull(statuses[gid])
        return status(gid, stored.status, stored.totalLength)
    }
    override suspend fun tellActive(): List<Aria2TaskStatus> = statuses.values.filter { it.status == Aria2TaskStatusValue.Active }
    override suspend fun tellWaiting(offset: Int, count: Int): List<Aria2TaskStatus> = statuses.values.filter { it.status in setOf(Aria2TaskStatusValue.Waiting, Aria2TaskStatusValue.Paused) }
    override suspend fun tellStopped(offset: Int, count: Int): List<Aria2TaskStatus> = statuses.values.filter { it.status in setOf(Aria2TaskStatusValue.Complete, Aria2TaskStatusValue.Error, Aria2TaskStatusValue.Removed) }

    override suspend fun removeDownloadResult(gid: String) {
        events += "remove-result:$gid"
        statuses.remove(gid)
    }

    override suspend fun saveSession(): Boolean {
        events += "save-session"
        return true
    }

    override suspend fun shutdown(force: Boolean) { process.complete(if (force) 137 else 0) }

    fun complete(gid: String, totalLength: Long) {
        statuses[gid] = status(gid, Aria2TaskStatusValue.Complete, totalLength)
    }

    private fun status(gid: String, value: Aria2TaskStatusValue, totalLength: Long = 0): Aria2TaskStatus {
        val path = (overrideOutput ?: outputPath)?.canonicalPath.orEmpty()
        val completed = if (value == Aria2TaskStatusValue.Complete) totalLength else 0
        return Aria2TaskStatus(
            gid = gid,
            status = value,
            totalLength = totalLength,
            completedLength = completed,
            downloadSpeed = if (value == Aria2TaskStatusValue.Active) 1024 else 0,
            dir = outputPath?.parent,
            files = listOf(
                Aria2RpcFile(
                    index = 1,
                    path = path,
                    length = totalLength,
                    completedLength = completed,
                    selected = true,
                    uris = listOf(Aria2RpcUri(sourceUrl.orEmpty(), "used")),
                ),
            ),
        )
    }
}

private class FailingMappingStore : Aria2TaskMappingStore {
    override suspend fun upsert(mapping: Aria2TaskMapping): Nothing = error("database unavailable")
    override suspend fun findByDownload(downloadId: String): Aria2TaskMapping? = null
    override suspend fun findByGid(gid: String): Aria2TaskMapping? = null
    override suspend fun listAll(): List<Aria2TaskMapping> = emptyList()
    override suspend fun deleteByDownload(downloadId: String) = Unit
    override suspend fun deleteByGid(gid: String) = Unit
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
