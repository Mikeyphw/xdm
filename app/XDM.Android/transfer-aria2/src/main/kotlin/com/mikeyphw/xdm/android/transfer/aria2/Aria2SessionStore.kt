package com.mikeyphw.xdm.android.transfer.aria2

import android.content.Context
import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class Aria2SessionStore(context: Context) : Aria2RuntimeFiles {
    override val rootDirectory: File = File(context.filesDir, "aria2")
    override val sessionFile: File = File(rootDirectory, "xdm.session")
    val taskDirectory: File = File(rootDirectory, "tasks")
    val stagingDirectory: File = File(rootDirectory, "staging")
    val logDirectory: File = File(rootDirectory, "logs")

    override fun prepare() {
        listOf(rootDirectory, taskDirectory, stagingDirectory, logDirectory).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create ${directory.name}" }
            directory.restrictToOwner()
        }
        if (!sessionFile.exists()) check(sessionFile.createNewFile()) { "Unable to create aria2 session file" }
        sessionFile.restrictToOwner()
    }

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

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
        .ifBlank { "download.bin" }
        .take(180)

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
