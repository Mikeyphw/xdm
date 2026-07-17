package com.mikeyphw.xdm.android.transfer.aria2

import android.content.Context
import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import java.io.File
import java.util.UUID

class Aria2SessionStore(context: Context) : Aria2RuntimeFiles {
    override val rootDirectory: File = File(context.filesDir, "aria2")
    val sessionFile: File = File(rootDirectory, "xdm.session")
    val taskDirectory: File = File(rootDirectory, "tasks")
    val stagingDirectory: File = File(rootDirectory, "staging")
    val logDirectory: File = File(rootDirectory, "logs")

    override fun prepare() {
        listOf(rootDirectory, taskDirectory, stagingDirectory, logDirectory).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create ${directory.name}" }
            directory.restrictToOwner()
        }
        if (!sessionFile.exists()) {
            check(sessionFile.createNewFile()) { "Unable to create aria2 session file" }
        }
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

    override fun artifactsFor(downloadId: String, fileName: String): BackendArtifactIdentity {
        require(downloadId.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe download identity" }
        val safeName = fileName
            .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
            .ifBlank { "download.bin" }
            .take(180)
        val directory = File(taskDirectory, downloadId)
        val partial = File(directory, "$safeName.xdm.aria2.part")
        val control = File(directory, "$safeName.xdm.aria2.part.aria2")
        val mapping = File(directory, "ownership.json")
        return BackendArtifactIdentity(
            format = "aria2-controlled-v1",
            primary = partial.toArtifactIdentity(),
            companions = listOf(control.toArtifactIdentity(), mapping.toArtifactIdentity(), sessionFile.toArtifactIdentity()),
        )
    }

    private fun File.safeConfigurationPath(): String = canonicalPath.also { path ->
        require('\n' !in path && '\r' !in path) { "Unsafe aria2 configuration path" }
    }

    private fun File.toArtifactIdentity(): String = canonicalFile.toURI().toString()

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
