package com.mikeyphw.xdm.android.transfer.aria2

import android.content.Context
import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.util.sanitizeFileName
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class Aria2SessionStore(context: Context) : Aria2RuntimeFiles {
    override val rootDirectory: File = File(context.filesDir, "aria2")
    override val sessionFile: File = File(rootDirectory, "xdm.session")
    val taskDirectory: File = File(rootDirectory, "tasks")
    val stagingDirectory: File = File(rootDirectory, "staging")
    val logDirectory: File = File(rootDirectory, "logs")
    private val runtimeLeaseFile: File = File(rootDirectory, "runtime-owner.properties")
    override val supportsRuntimeLease: Boolean = true

    override fun prepare() {
        listOf(rootDirectory, taskDirectory, stagingDirectory, logDirectory).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create ${directory.name}" }
            directory.restrictToOwner()
        }
        if (!sessionFile.exists()) check(sessionFile.createNewFile()) { "Unable to create aria2 session file" }
        sessionFile.restrictToOwner()
    }

    override fun cleanupTransientLaunchConfigurations(): Int {
        prepare()
        var removed = 0
        rootDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("launch-") && it.extension == "conf" }
            .forEach { file ->
                if (deleteLaunchConfiguration(file)) removed += 1
            }
        return removed
    }

    override fun readRuntimeLogTail(maxChars: Int): String? = runCatching {
        val file = File(logDirectory, "aria2-runtime.log")
        if (!file.isFile || file.length() <= 0L) return@runCatching null
        val safeLimit = maxChars.coerceIn(256, 16_384)
        val bytes = RandomAccessFile(file, "r").use { input ->
            val count = minOf(input.length(), safeLimit.toLong()).toInt()
            input.seek((input.length() - count).coerceAtLeast(0L))
            ByteArray(count).also(input::readFully)
        }
        bytes.toString(Charsets.UTF_8)
            .replace(Regex("token:[^\\s,]+"), "token:<redacted>")
            .replace(Regex("rpc-secret=[^\\s,]+"), "rpc-secret=<redacted>")
            .replace(Regex("(?i)(authorization|cookie):\\s*[^\\r\\n]+"), "$1: <redacted>")
            .replace(Regex("(?i)bearer\\s+[^\\s,]+"), "Bearer <redacted>")
            .replace(Regex("([?&][^=\\s&]+)=([^\\s&]+)"), "$1=<redacted>")
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()


    override fun readRuntimeLease(): Aria2RuntimeLease? = runCatching {
        if (!runtimeLeaseFile.isFile) return@runCatching null
        val values = runtimeLeaseFile.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }
            .toMap()
        if (values["schema"] != "1") return@runCatching null
        Aria2RuntimeLease(
            endpoint = Aria2Endpoint(requireNotNull(values["port"]).toInt()),
            secretGeneration = requireNotNull(values["secretGeneration"]).toLong(),
            startedAtEpochMs = requireNotNull(values["startedAtEpochMs"]).toLong(),
        )
    }.getOrNull()

    override fun writeRuntimeLease(lease: Aria2RuntimeLease): Boolean = runCatching {
        prepare()
        val temporary = File(rootDirectory, "runtime-owner.properties.tmp")
        temporary.writeText(
            buildString {
                appendLine("schema=1")
                appendLine("port=${lease.endpoint.port}")
                appendLine("secretGeneration=${lease.secretGeneration}")
                appendLine("startedAtEpochMs=${lease.startedAtEpochMs}")
            },
            Charsets.UTF_8,
        )
        temporary.restrictToOwner()
        check(temporary.renameTo(runtimeLeaseFile) || runCatching {
            temporary.copyTo(runtimeLeaseFile, overwrite = true)
            temporary.delete()
            true
        }.getOrDefault(false)) { "Unable to persist aria2 runtime ownership lease" }
        runtimeLeaseFile.restrictToOwner()
        true
    }.getOrDefault(false)

    override fun clearRuntimeLease(): Boolean = runCatching {
        val temporary = File(rootDirectory, "runtime-owner.properties.tmp")
        temporary.delete()
        !runtimeLeaseFile.exists() || runtimeLeaseFile.delete()
    }.getOrDefault(false)

    override fun writeLaunchConfiguration(endpoint: Aria2Endpoint, secret: Aria2RpcSecret): File {
        prepare()
        val file = File(rootDirectory, "launch-${UUID.randomUUID()}.conf")
        val safeSession = sessionFile.safeConfigurationPath()
        val safeStaging = stagingDirectory.safeConfigurationPath()
        val contents = buildString {
            appendLine("enable-rpc=true")
            appendLine("rpc-listen-all=false")
            appendLine("rpc-listen-port=${endpoint.port}")
            appendLine("rpc-secret=${secret.configurationValue()}")
            appendLine("rpc-allow-origin-all=false")
            appendLine("rpc-max-request-size=2M")
            appendLine("input-file=$safeSession")
            appendLine("save-session=$safeSession")
            appendLine("save-session-interval=30")
            appendLine("save-not-found=true")
            appendLine("keep-unfinished-download-result=true")
            appendLine("dir=$safeStaging")
            appendLine("continue=true")
            appendLine("always-resume=true")
            appendLine("allow-overwrite=false")
            appendLine("auto-file-renaming=false")
            appendLine("file-allocation=none")
            appendLine("console-log-level=warn")
            appendLine("summary-interval=0")
            appendLine("enable-color=false")
        }
        file.writeText(contents, Charsets.UTF_8)
        file.restrictToOwner()
        return file
    }

    override fun deleteLaunchConfiguration(file: File): Boolean = runCatching {
        val belongsToRuntime = file.parentFile?.canonicalFile == rootDirectory.canonicalFile &&
            file.name.startsWith("launch-") && file.extension == "conf"
        if (!belongsToRuntime) return@runCatching false
        if (!file.exists() || file.delete()) return@runCatching true
        runCatching { file.writeText("", Charsets.UTF_8) }
        !file.exists() || file.delete()
    }.getOrDefault(false)

    override fun logFile(): File {
        prepare()
        return File(logDirectory, "aria2-runtime.log").also { file ->
            if (!file.exists()) file.createNewFile()
            file.restrictToOwner()
        }
    }

    override fun taskFiles(downloadId: String, output: File): Aria2TaskFiles {
        require(downloadId.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe download identity" }
        prepare()
        val directory = File(taskDirectory, downloadId).apply {
            check(isDirectory || mkdirs()) { "Unable to create aria2 task directory" }
            restrictToOwner()
        }
        val canonicalOutput = output.canonicalFile
        return Aria2TaskFiles(
            directory = directory.canonicalFile,
            output = canonicalOutput,
            control = File(canonicalOutput.parentFile, canonicalOutput.name + ".aria2"),
            ownershipMetadata = File(directory, "ownership.json"),
            session = sessionFile.canonicalFile,
        )
    }

    override fun writeOwnershipMetadata(files: Aria2TaskFiles, mapping: Aria2TaskMapping) {
        val payload = buildJsonObject {
            put("schema", 2)
            put("downloadId", mapping.downloadId)
            put("gid", mapping.gid)
            put("sourceUrl", mapping.sourceUrl)
            put("destinationKey", mapping.destinationKey)
            put("outputPath", mapping.outputPath)
            put("expectedLength", mapping.expectedLength ?: -1)
            put("ownershipGeneration", mapping.ownershipGeneration)
            put("backendInstanceId", mapping.backendInstanceId)
            put("status", mapping.status)
            put("updatedAtEpochMs", mapping.updatedAtEpochMs)
        }
        val temporary = File(files.directory, "ownership.json.tmp")
        temporary.writeText(payload.toString(), Charsets.UTF_8)
        temporary.restrictToOwner()
        check(temporary.renameTo(files.ownershipMetadata) || runCatching {
            temporary.copyTo(files.ownershipMetadata, overwrite = true)
            temporary.delete()
            true
        }.getOrDefault(false)) { "Unable to persist aria2 ownership metadata" }
        files.ownershipMetadata.restrictToOwner()
    }

    override fun deleteTaskMetadata(files: Aria2TaskFiles) {
        files.ownershipMetadata.delete()
        files.directory.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    override fun artifactsFor(downloadId: String, fileName: String): BackendArtifactIdentity {
        val safeName = safeFileName(fileName)
        val output = File(stagingDirectory, "$downloadId-$safeName.xdm.aria2.part")
        return taskFiles(downloadId, output).artifacts()
    }

    private fun safeFileName(value: String): String = sanitizeFileName(value)

    private fun File.safeConfigurationPath(): String = canonicalPath.also { path ->
        require('\n' !in path && '\r' !in path) { "Unsafe aria2 configuration path" }
    }

    private fun File.restrictToOwner() {
        val directory = isDirectory
        setReadable(false, false)
        setWritable(false, false)
        setExecutable(false, false)
        setReadable(true, true)
        setWritable(true, true)
        if (directory) setExecutable(true, true)
    }
}
