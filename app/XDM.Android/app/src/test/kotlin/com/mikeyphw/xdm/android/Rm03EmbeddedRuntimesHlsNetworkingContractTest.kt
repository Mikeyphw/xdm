package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rm03EmbeddedRuntimesHlsNetworkingContractTest {
    private val root = androidRoot()

    @Test fun ffmpegBuildAndVerificationSealAndroidDynamicDependencies() {
        val installer = source("tools/install-ffmpeg-runtime.py")
        val verifier = source("tools/verify-ffmpeg-runtime.py")
        val policy = source("tools/android_elf_runtime.py")
        val runtime = source("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/EmbeddedFfmpegRuntime.kt")

        assertTrue(installer.contains("env[\"PKG_CONFIG_PATH\"] = \"\""))
        assertTrue(installer.contains("PKG_CONFIG_LIBDIR"))
        assertTrue(installer.contains("android_lib_dir"))
        assertTrue(installer.contains("validate_android_runtime_file"))
        assertTrue(verifier.contains("validate_android_runtime_elf"))
        assertTrue(verifier.contains("NeededLibraries"))
        assertTrue(policy.contains("libz.so.1"))
        assertTrue(policy.contains("VERSIONED_SONAME"))
        assertTrue(runtime.contains("dynamicDependencyPolicy"))
        assertTrue(runtime.contains("runtime lock contains a versioned non-Android SONAME"))
    }

    @Test fun aria2CapabilityAndRepairRemainTruthfulAboutImmutableBinaryFailures() {
        val environment = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2AndroidEnvironment.kt")
        val manager = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
        val model = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")

        assertTrue(environment.contains("runtime binary digest mismatch"))
        assertTrue(environment.contains("android-unversioned-sonames-v1"))
        assertTrue(model.contains("neededLibraries: List<String>"))
        assertTrue(model.contains("repairHint: String?"))
        assertTrue(manager.contains("cannot rewrite a packaged native executable"))
        assertTrue(manager.contains("Install/update XDM with an attested Android aria2 payload"))
        assertTrue(viewModel.contains("Aria2StartupFailureKind.BinaryLoadFailure"))
        assertTrue(viewModel.contains("Native dependencies:"))
    }

    @Test fun nativeHlsPinsTransportToSecurityValidatedDnsAndRevalidatesRedirects() {
        val guard = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuard.kt")
        val hls = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")

        assertTrue(guard.contains("validateAndResolveTarget"))
        assertTrue(guard.contains("ValidatedTransferNetworkTarget"))
        assertTrue(guard.contains("DnsResolutionFailed"))
        assertTrue(guard.contains("UnsafeNetworkTarget"))
        assertTrue(guard.contains("resolveWithBoundedRetry"))
        assertTrue(hls.contains("clientForValidatedTarget"))
        assertTrue(hls.contains("target.addresses"))
        assertTrue(hls.contains("HLS transport attempted an unvalidated hostname"))
        assertTrue(hls.contains("repeat(6) { redirectCount"))
        assertTrue(hls.contains("validatedTarget = validateRequest(current"))
        assertTrue(hls.contains("HLS_DNS_RETRY_BACKOFF_MILLIS"))
        assertFalse(hls.contains("securityGuard.validate(DownloadRequest("))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
}
