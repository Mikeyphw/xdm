#!/usr/bin/env python3
"""RM03 static seal: Android runtime ELF policy + truthful aria2 repair + pinned HLS DNS."""
from pathlib import Path
import json

from android_elf_runtime import ElfPolicyError, validate_android_needed_libraries

ROOT = Path(__file__).resolve().parents[1]


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"missing RM03 source: {rel}")
    return path.read_text(encoding="utf-8")


def need(haystack: str, needle: str, label: str) -> None:
    if needle not in haystack:
        raise AssertionError(f"RM03 missing {label}: {needle!r}")


def ordered(haystack: str, needles: list[str], label: str) -> None:
    cursor = 0
    for needle in needles:
        pos = haystack.find(needle, cursor)
        if pos < 0:
            raise AssertionError(f"RM03 missing ordered marker for {label}: {needle!r}")
        cursor = pos + len(needle)


def expect_elf_rejection(needed: list[str]) -> None:
    try:
        validate_android_needed_libraries(needed)
    except ElfPolicyError:
        return
    raise AssertionError(f"RM03 ELF policy accepted forbidden dependencies: {needed}")


def main() -> int:
    # Shared ELF policy must reject the exact observed Linux/Termux failure class while accepting
    # ordinary Android SONAMEs.
    validate_android_needed_libraries(["libc.so", "libm.so", "libdl.so", "libz.so"])
    expect_elf_rejection(["libz.so.1"])
    expect_elf_rejection(["libc.so.6"])
    expect_elf_rejection(["libexample.so.12.3"])
    policy = text("tools/android_elf_runtime.py")
    for marker in ("PT_INTERP", "DT_NEEDED", "VERSIONED_SONAME", "ANDROID_INTERPRETERS"):
        need(policy, marker, "shared Android ELF policy")

    ff_manifest = json.loads(text("media-ffmpeg/runtime/ffmpeg-runtime.json"))
    aria_manifest = json.loads(text("transfer-aria2/runtime/aria2-runtime.json"))
    if ff_manifest.get("dynamicDependencyPolicy") != "android-unversioned-sonames-v1":
        raise AssertionError("FFmpeg manifest must require Android unversioned SONAME policy")
    if aria_manifest.get("schemaVersion") != 2:
        raise AssertionError("aria2 runtime manifest must use schemaVersion 2")
    if aria_manifest.get("dynamicDependencyPolicy") != "android-unversioned-sonames-v1":
        raise AssertionError("aria2 manifest must require Android unversioned SONAME policy")

    ff_install = text("tools/install-ffmpeg-runtime.py")
    ff_verify = text("tools/verify-ffmpeg-runtime.py")
    for needle, label in (
        ('env["PKG_CONFIG_PATH"] = ""', "host pkg-config isolation"),
        ('env["PKG_CONFIG_LIBDIR"] = str(pkg)', "pinned pkg-config directory"),
        ('android_lib_dir = toolchain / "sysroot/usr/lib/aarch64-linux-android"', "NDK API library path"),
        ('f"--extra-ldflags=-L{android_lib_dir}', "NDK library precedence"),
        ("validate_android_runtime_file(tmp)", "pre-install ELF validation"),
        ('"ffmpegNeededLibraries"', "FFmpeg needed-library attestation"),
        ('"ffprobeNeededLibraries"', "FFprobe needed-library attestation"),
    ):
        need(ff_install, needle, label)
    for needle in ("validate_android_runtime_elf", "DT_NEEDED list differs", "NeededLibraries"):
        need(ff_verify, needle, "FFmpeg installed/APK dependency verification")

    ff_runtime = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/EmbeddedFfmpegRuntime.kt")
    ff_models = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegRuntimeModels.kt")
    need(ff_models, "dynamicDependencyPolicy", "runtime manifest policy model")
    need(ff_runtime, 'getValue("dynamicDependencyPolicy")', "on-device dependency policy attestation")
    need(ff_runtime, "runtime lock contains a versioned non-Android SONAME", "on-device versioned SONAME rejection")

    aria_install = text("tools/install-aria2-runtime.py")
    aria_verify = text("tools/verify-aria2-runtime.py")
    for needle in ("validate_android_runtime_file", '"neededLibraries"', '"elfInterpreter"', '"dynamicDependencyPolicy"'):
        need(aria_install, needle, "aria2 install attestation")
    for needle in ("validate_android_runtime_elf", '"neededLibraries"', '"elfInterpreter"', '"dynamicDependencyPolicy"'):
        need(aria_verify, needle, "aria2 installed/APK verification")

    aria_env = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2AndroidEnvironment.kt")
    aria_models = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt")
    aria_manager = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
    main_view_model = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
    debug_catalog = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestCatalog.kt")
    for needle in (
        'runtime lock schema is obsolete',
        'runtime binary digest mismatch',
        'runtime dependency policy mismatch',
        'hash-attested, dependency-policy verified',
        'attestationVerified = true',
    ):
        need(aria_env, needle, "installed aria2 attestation")
    for needle in ("neededLibraries: List<String>", "binarySha256: String?", "repairHint: String?"):
        need(aria_models, needle, "aria2 diagnostic model")
    ordered(aria_manager, [
        "stop()",
        "cleanupTransientLaunchConfigurations()",
        "sanitizeSavedSessionToOwnedMetadata()",
        "clearRuntimeLease()",
        "rotateRuntimeLog()",
        "rotatable.rotate()",
        "val packagedAfter = probe()",
        "return start()",
    ], "aria2 state repair ordering")
    need(aria_manager, "cannot rewrite a packaged native executable", "truthful immutable-binary repair")
    need(aria_manager, "Install/update XDM with an attested Android aria2 payload", "binary load recovery hint")
    need(main_view_model, "Aria2StartupFailureKind.BinaryLoadFailure", "Repair aria2 UI suppression for linker failure")
    need(main_view_model, "Native dependencies:", "aria2 dependency diagnostics")
    need(debug_catalog, 'put("neededLibraries"', "debug runtime dependency evidence")

    guard = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuard.kt")
    hls = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
    for needle in (
        "TransferHostnameResolver",
        "TransferSecurityFailureKind",
        "ValidatedTransferNetworkTarget",
        "validateAndResolveTarget",
        "resolveWithBoundedRetry",
        "DnsResolutionFailed",
        "UnsafeNetworkTarget",
        "privateNetworkApprovalScopes",
    ):
        need(guard, needle, "typed HLS network security")
    ordered(hls, [
        "val validatedTarget = validateRequest(current",
        "Request.Builder().url(current)",
        "executeCancellable(clientForValidatedTarget(validatedTarget)",
    ], "validate then connect to pinned addresses")
    for needle in (
        "clientForValidatedTarget",
        "target.addresses",
        "HLS transport attempted an unvalidated hostname",
        "HLS_DNS_RETRY_BACKOFF_MILLIS",
        "TransferSecurityFailureKind.DnsResolutionFailed",
        "repeat(6) { redirectCount",
    ):
        need(hls, needle, "HLS DNS pinning/retry")
    if "securityGuard.validate(DownloadRequest(" in hls:
        raise AssertionError("Native HLS still validates then lets transport perform an unrelated DNS resolution")

    # Regression anchors.
    regression_markers = [
        ("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuardTest.kt", "transientDnsFailureRetriesAndReturnsOnlyValidatedAddresses"),
        ("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuardTest.kt", "legitimatePublicCdnTransitionIsValidatedAsANewExactTarget"),
        ("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuardTest.kt", "privateResolvedRouteIsRejectedWithoutExactApproval"),
        ("transfer-aria2/src/test/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManagerTest.kt", "binaryLoadFailureDoesNotPretendRuntimeRepairCanRewritePackagedPayload"),
        ("app/src/test/kotlin/com/mikeyphw/xdm/android/Rm03EmbeddedRuntimesHlsNetworkingContractTest.kt", "nativeHlsPinsTransportToSecurityValidatedDnsAndRevalidatesRedirects"),
    ]
    for rel, marker in regression_markers:
        need(text(rel), marker, f"regression anchor {marker}")

    ff_gradle = text("media-ffmpeg/build.gradle.kts")
    aria_gradle = text("transfer-aria2/build.gradle.kts")
    if ff_gradle.count('file("tools/android_elf_runtime.py")') < 3:
        raise AssertionError("FFmpeg install/runtime/release verification tasks must track shared ELF policy as an input")
    if aria_gradle.count('file("tools/android_elf_runtime.py")') < 3:
        raise AssertionError("aria2 install/runtime/release verification tasks must track shared ELF policy as an input")

    manifest = json.loads(text("PROJECT_MANIFEST.json"))
    rm03 = manifest.get("rm03_embedded_runtimes_hls_networking")
    if not isinstance(rm03, dict) or rm03.get("status") != "implemented":
        raise AssertionError("PROJECT_MANIFEST.json must mark rm03_embedded_runtimes_hls_networking implemented")
    for key in (
        "android_elf_dependency_policy",
        "ffmpeg_ndk_zlib_isolation",
        "ffmpeg_apk_dependency_attestation",
        "aria2_installed_payload_attestation",
        "aria2_truthful_binary_repair_boundary",
        "hls_bounded_dns_retry",
        "hls_validated_dns_pinning",
        "hls_redirect_revalidation",
        "private_network_protection_preserved",
    ):
        if rm03.get(key) is not True:
            raise AssertionError(f"RM03 manifest invariant is not sealed: {key}")

    print("RM03 embedded runtimes/HLS networking contract passed: Android ELF dependencies, FFmpeg sysroot isolation, aria2 attestation/repair truth, and HLS DNS pinning are sealed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
