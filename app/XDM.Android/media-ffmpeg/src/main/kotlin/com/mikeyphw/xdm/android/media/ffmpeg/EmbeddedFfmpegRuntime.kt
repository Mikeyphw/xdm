package com.mikeyphw.xdm.android.media.ffmpeg

import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EmbeddedFfmpegRuntime(
    context: Context,
    private val launcher: FfmpegProcessLauncher = FfmpegProcessLauncher(),
    private val trustBundleProvider: AndroidTrustBundleProvider = AndroidTrustBundleProvider(context),
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var cachedCapability: FfmpegRuntimeCapabilityReport? = null

    val ffmpegBinary: File get() = File(appContext.applicationInfo.nativeLibraryDir, "libxdm_ffmpeg.so")
    val ffprobeBinary: File get() = File(appContext.applicationInfo.nativeLibraryDir, "libxdm_ffprobe.so")

    fun manifest(): FfmpegRuntimeManifest {
        val root = appContext.assets.open("ffmpeg-runtime.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        return FfmpegRuntimeManifest(
            ffmpegVersion = root.getValue("ffmpegVersion").jsonPrimitive.content,
            opensslVersion = root.getValue("opensslVersion").jsonPrimitive.content,
            abi = root.getValue("abi").jsonPrimitive.content,
            androidApi = root.getValue("androidApi").jsonPrimitive.intOrNull ?: 26,
            ndkVersion = root.getValue("ndkVersion").jsonPrimitive.content,
            ffmpegLicense = root.getValue("ffmpegLicense").jsonPrimitive.content,
            opensslLicense = root.getValue("opensslLicense").jsonPrimitive.content,
            gplEnabled = root.getValue("gplEnabled").jsonPrimitive.booleanOrNull == true,
            nonfreeEnabled = root.getValue("nonfreeEnabled").jsonPrimitive.booleanOrNull == true,
            httpsRequired = root.getValue("httpsRequired").jsonPrimitive.booleanOrNull != false,
        )
    }

    suspend fun capabilities(force: Boolean = false): FfmpegRuntimeCapabilityReport {
        if (!force) cachedCapability?.let { return it }
        return mutex.withLock {
            if (!force) cachedCapability?.let { return@withLock it }
            val manifest = runCatching { manifest() }.getOrElse { error ->
                return@withLock FfmpegRuntimeCapabilityReport(
                    health = FfmpegRuntimeHealth.Corrupt,
                    detail = "Runtime manifest could not be read: ${error.message ?: error::class.java.simpleName}",
                ).also { cachedCapability = it }
            }
            if (!ffmpegBinary.isFile || !ffprobeBinary.isFile) {
                return@withLock FfmpegRuntimeCapabilityReport(
                    health = FfmpegRuntimeHealth.Missing,
                    expectedVersion = manifest.ffmpegVersion,
                    ffmpegPath = ffmpegBinary.absolutePath,
                    ffprobePath = ffprobeBinary.absolutePath,
                    detail = "Embedded FFmpeg/FFprobe payload is missing from nativeLibraryDir.",
                ).also { cachedCapability = it }
            }
            val ffmpegVersionResult = executeRaw(FfmpegOperation.Version(FfmpegBinary.Ffmpeg))
            val ffprobeVersionResult = executeRaw(FfmpegOperation.Version(FfmpegBinary.Ffprobe))
            if (!ffmpegVersionResult.success || !ffprobeVersionResult.success) {
                val failed = if (!ffmpegVersionResult.success) ffmpegVersionResult else ffprobeVersionResult
                return@withLock FfmpegRuntimeCapabilityReport(
                    health = when (failed.failureKind) {
                        FfmpegFailureKind.PermissionDenied -> FfmpegRuntimeHealth.ExecutionDenied
                        FfmpegFailureKind.RuntimeMissing -> FfmpegRuntimeHealth.Missing
                        else -> FfmpegRuntimeHealth.Failed
                    },
                    expectedVersion = manifest.ffmpegVersion,
                    ffmpegPath = ffmpegBinary.absolutePath,
                    ffprobePath = ffprobeBinary.absolutePath,
                    detail = failed.redactedSummary,
                ).also { cachedCapability = it }
            }
            val ffmpegVersion = parseVersion(ffmpegVersionResult.stdout.ifBlank { ffmpegVersionResult.stderr })
            val ffprobeVersion = parseVersion(ffprobeVersionResult.stdout.ifBlank { ffprobeVersionResult.stderr })
            if (ffmpegVersion != manifest.ffmpegVersion || ffprobeVersion != manifest.ffmpegVersion) {
                return@withLock FfmpegRuntimeCapabilityReport(
                    health = FfmpegRuntimeHealth.VersionMismatch,
                    expectedVersion = manifest.ffmpegVersion,
                    ffmpegVersion = ffmpegVersion,
                    ffprobeVersion = ffprobeVersion,
                    ffmpegPath = ffmpegBinary.absolutePath,
                    ffprobePath = ffprobeBinary.absolutePath,
                    detail = "Packaged runtime version does not match the pinned manifest.",
                ).also { cachedCapability = it }
            }
            val protocols = executeRaw(FfmpegOperation.Protocols())
            val protocolText = protocols.stdout + "\n" + protocols.stderr
            val https = Regex("""(?m)^\s*https\s*$""").containsMatchIn(protocolText)
            val trust = if (manifest.httpsRequired && https) trustBundleProvider.ensure() else Result.success(File("/dev/null"))
            val trustReady = !manifest.httpsRequired || (https && trust.isSuccess)
            FfmpegRuntimeCapabilityReport(
                health = when {
                    manifest.httpsRequired && !https -> FfmpegRuntimeHealth.UnsupportedBuild
                    manifest.httpsRequired && trust.isFailure -> FfmpegRuntimeHealth.Failed
                    else -> FfmpegRuntimeHealth.Ready
                },
                expectedVersion = manifest.ffmpegVersion,
                ffmpegVersion = ffmpegVersion,
                ffprobeVersion = ffprobeVersion,
                httpsSupported = https,
                tlsTrustReady = trustReady,
                ffmpegPath = ffmpegBinary.absolutePath,
                ffprobePath = ffprobeBinary.absolutePath,
                detail = when {
                    !https -> "FFmpeg is executable but HTTPS protocol support is absent; rebuild the pinned runtime with OpenSSL."
                    trust.isFailure -> "FFmpeg HTTPS is present but Android CA trust could not be materialized: ${redactFfmpegDiagnostic(trust.exceptionOrNull()?.message.orEmpty())}"
                    else -> "App-owned FFmpeg and FFprobe are executable; verified HTTPS uses AndroidCAStore. NDK ${manifest.ndkVersion}; ${manifest.ffmpegLicense}; OpenSSL ${manifest.opensslVersion}."
                },
            ).also { cachedCapability = it }
        }
    }

    suspend fun execute(operation: FfmpegOperation): FfmpegExecutionResult {
        val capability = capabilities()
        if (!capability.ready && operation !is FfmpegOperation.Version && operation !is FfmpegOperation.Protocols) {
            return FfmpegExecutionResult(-1, "", "", 0, FfmpegFailureKind.RuntimeInvalid, capability.summary)
        }
        return executeRaw(withAndroidTrust(operation))
    }

    suspend fun probe(input: String, headers: Map<String, String> = emptyMap()): Result<FfprobeResult> = runCatching {
        val result = execute(FfmpegOperation.Probe(input, headers))
        if (!result.success) error(result.redactedSummary)
        FfprobeJsonParser.parse(result.stdout)
    }

    suspend fun selfTest(): FfmpegExecutionResult {
        val capability = capabilities(force = true)
        if (!capability.ready) return FfmpegExecutionResult(-1, "", "", 0, FfmpegFailureKind.RuntimeInvalid, capability.summary)
        return executeRaw(FfmpegOperation.Version(FfmpegBinary.Ffprobe))
    }

    private fun withAndroidTrust(operation: FfmpegOperation): FfmpegOperation {
        val caFile = when (operation) {
            is FfmpegOperation.Probe -> if (operation.input.startsWith("https://", ignoreCase = true)) trustBundleProvider.ensure().getOrNull() else null
            is FfmpegOperation.RecordStream -> if (operation.inputUrl.startsWith("https://", ignoreCase = true)) trustBundleProvider.ensure().getOrNull() else null
            else -> null
        }
        return when (operation) {
            is FfmpegOperation.Probe -> operation.copy(tlsCaFile = caFile ?: operation.tlsCaFile)
            is FfmpegOperation.RecordStream -> operation.copy(tlsCaFile = caFile ?: operation.tlsCaFile)
            else -> operation
        }
    }

    private suspend fun executeRaw(operation: FfmpegOperation): FfmpegExecutionResult {
        val command = runCatching { FfmpegCommandCompiler.compile(operation) }.getOrElse { error ->
            return FfmpegExecutionResult(-1, "", "", 0, FfmpegFailureKind.InvalidArguments, error.message ?: "invalid FFmpeg arguments")
        }
        val binary = when (command.binary) {
            FfmpegBinary.Ffmpeg -> ffmpegBinary
            FfmpegBinary.Ffprobe -> ffprobeBinary
        }
        if (!binary.isFile) return FfmpegExecutionResult(-1, "", "", 0, FfmpegFailureKind.RuntimeMissing, "Embedded ${command.binary.name} runtime is missing")
        return launcher.launch(binary, command)
    }

    private fun parseVersion(text: String): String? = Regex("(?i)ff(?:mpeg|probe) version\\s+([^\\s]+)")
        .find(text)?.groupValues?.getOrNull(1)?.substringBefore('-')
}
