package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import java.io.File
import kotlinx.coroutines.flow.Flow

const val ARIA2_PACKAGED_BINARY_NAME = "libaria2c.so"
const val ARIA2_PRIMARY_ABI = "arm64-v8a"

enum class Aria2Availability {
    Available,
    UnsupportedAbi,
    NativeLibraryDirectoryMissing,
    BinaryMissing,
    BinaryInvalid,
    BinaryNotExecutable,
    RuntimeDirectoryUnavailable,
    ProbeFailed,
}

data class Aria2BinaryDescriptor(
    val file: File,
    val abi: String,
    val sha256: String,
)

data class Aria2CapabilityReport(
    val availability: Aria2Availability,
    val summary: String,
    val binary: Aria2BinaryDescriptor? = null,
) {
    val isAvailable: Boolean get() = availability == Aria2Availability.Available && binary != null
}

data class Aria2Endpoint(val port: Int) {
    init {
        require(port in 1024..65535) { "aria2 RPC port must be between 1024 and 65535" }
    }

    val url: String get() = "http://127.0.0.1:$port/jsonrpc"
}

class Aria2RpcSecret private constructor(private val value: String) {
    init {
        require(value.length >= 32) { "aria2 RPC secret is too short" }
        require(value.none(Char::isWhitespace)) { "aria2 RPC secret must not contain whitespace" }
    }

    internal fun tokenParameter(): String = "token:$value"
    internal fun configurationValue(): String = value

    override fun equals(other: Any?): Boolean = other is Aria2RpcSecret && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "Aria2RpcSecret(<redacted>)"

    companion object {
        fun from(value: String): Aria2RpcSecret = Aria2RpcSecret(value)
    }
}

data class Aria2Version(
    val version: String,
    val enabledFeatures: Set<String> = emptySet(),
)

sealed interface Aria2ProcessState {
    data object Stopped : Aria2ProcessState
    data class Unavailable(val report: Aria2CapabilityReport) : Aria2ProcessState
    data class Starting(val endpoint: Aria2Endpoint) : Aria2ProcessState
    data class Running(
        val endpoint: Aria2Endpoint,
        val version: Aria2Version,
        val processId: Long?,
    ) : Aria2ProcessState
    data class Stopping(val endpoint: Aria2Endpoint) : Aria2ProcessState
    data class Failed(val message: String) : Aria2ProcessState
}

data class Aria2LaunchPlan(
    val binary: File,
    val workingDirectory: File,
    val configurationFile: File,
    val logFile: File,
) {
    val command: List<String> get() = listOf(binary.absolutePath, "--conf-path=${configurationFile.absolutePath}")
}

data class Aria2StartResult(
    val started: Boolean,
    val alreadyRunning: Boolean,
    val state: Aria2ProcessState,
)

data class Aria2StopResult(
    val clean: Boolean,
    val forced: Boolean,
    val sessionSaved: Boolean,
    val exitCode: Int?,
)

data class Aria2SmokeTestResult(
    val successful: Boolean,
    val summary: String,
    val version: Aria2Version? = null,
)

data class Aria2TaskFiles(
    val directory: File,
    val output: File,
    val control: File,
    val ownershipMetadata: File,
    val session: File,
) {
    fun artifacts(): BackendArtifactIdentity = BackendArtifactIdentity(
        format = "aria2-controlled-v2",
        primary = output.canonicalFile.toURI().toString(),
        companions = listOf(
            control.canonicalFile.toURI().toString(),
            ownershipMetadata.canonicalFile.toURI().toString(),
            session.canonicalFile.toURI().toString(),
        ),
    )
}

interface Aria2RuntimeFiles {
    val rootDirectory: File
    val sessionFile: File
    fun prepare()
    fun writeLaunchConfiguration(endpoint: Aria2Endpoint, secret: Aria2RpcSecret): File
    fun deleteLaunchConfiguration(file: File): Boolean
    fun logFile(): File
    fun taskFiles(downloadId: String, output: File): Aria2TaskFiles
    fun writeOwnershipMetadata(files: Aria2TaskFiles, mapping: com.mikeyphw.xdm.android.transfer.Aria2TaskMapping)
    fun deleteTaskMetadata(files: Aria2TaskFiles)
    fun artifactsFor(downloadId: String, fileName: String): BackendArtifactIdentity
}

fun interface Aria2CapabilityProbe {
    fun probe(): Aria2CapabilityReport
}

fun interface Aria2SecretProvider {
    fun getOrCreate(): Aria2RpcSecret
}

fun interface Aria2PortAllocator {
    fun allocate(): Int
}

fun interface Aria2ProcessLauncher {
    fun launch(plan: Aria2LaunchPlan): Aria2ManagedProcess
}

interface Aria2ManagedProcess {
    val processId: Long?
    val isAlive: Boolean
    suspend fun awaitExit(): Int
    fun destroy()
    fun destroyForcibly()
}

enum class Aria2TaskStatusValue { Active, Waiting, Paused, Error, Complete, Removed, Unknown }

data class Aria2RpcUri(val uri: String, val status: String? = null)
data class Aria2RpcFile(
    val index: Int,
    val path: String,
    val length: Long,
    val completedLength: Long,
    val selected: Boolean,
    val uris: List<Aria2RpcUri>,
)
data class Aria2TaskStatus(
    val gid: String,
    val status: Aria2TaskStatusValue,
    val totalLength: Long,
    val completedLength: Long,
    val downloadSpeed: Long,
    val dir: String?,
    val files: List<Aria2RpcFile>,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val followedBy: List<String> = emptyList(),
    val following: String? = null,
    val belongsTo: String? = null,
)

data class Aria2TaskOptions(
    val directory: String,
    val outputName: String,
    val pause: Boolean = true,
    val continueDownload: Boolean = true,
    val split: Int = 4,
    val maxConnectionsPerServer: Int = 4,
    val headers: Map<String, String> = emptyMap(),
)

interface Aria2RpcControl {
    suspend fun getVersion(): Aria2Version
    suspend fun addUri(uris: List<String>, options: Aria2TaskOptions): String
    suspend fun pause(gid: String, force: Boolean = false)
    suspend fun unpause(gid: String)
    suspend fun remove(gid: String, force: Boolean = false)
    suspend fun tellStatus(gid: String): Aria2TaskStatus
    suspend fun tellActive(): List<Aria2TaskStatus>
    suspend fun tellWaiting(offset: Int = 0, count: Int = 1000): List<Aria2TaskStatus>
    suspend fun tellStopped(offset: Int = 0, count: Int = 1000): List<Aria2TaskStatus>
    suspend fun removeDownloadResult(gid: String)
    suspend fun saveSession(): Boolean
    suspend fun shutdown(force: Boolean = false)
}

fun interface Aria2RpcControlFactory {
    fun create(endpoint: Aria2Endpoint, secret: Aria2RpcSecret): Aria2RpcControl
}

interface Aria2TaskEventSource {
    fun observe(gid: String): Flow<Aria2TaskStatus>
}
