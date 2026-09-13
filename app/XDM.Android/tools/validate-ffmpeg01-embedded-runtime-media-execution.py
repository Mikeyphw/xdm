#!/usr/bin/env python3
"""FF01 source contract: embedded FFmpeg/FFprobe is an app-owned, attested Android execution lane."""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(path: str) -> str:
    p = ROOT / path
    if not p.is_file():
        errors.append(f"missing {path}")
        return ""
    return p.read_text(encoding="utf-8")


def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def has(source: str, *needles: str) -> bool:
    return all(item in source for item in needles)

manifest_path = ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
need(manifest.get("ffmpegVersion") == "9.0.1", "FFmpeg source version is not pinned to 9.0.1")
need(manifest.get("opensslVersion") == "3.5.8", "OpenSSL source version is not pinned to 3.5.8")
need(manifest.get("ndkVersion") == "29.0.14206865", "FFmpeg runtime does not pin NDK 29.0.14206865")
need(manifest.get("abi") == "arm64-v8a" and manifest.get("elfMachine") == 183, "FFmpeg runtime ABI is not pinned to ARM64/AArch64")
need(manifest.get("requiredLoadAlignment") == 16384, "FFmpeg runtime is not pinned to 16 KB PT_LOAD alignment")
need(manifest.get("gplEnabled") is False and manifest.get("nonfreeEnabled") is False, "FFmpeg LGPL profile must keep GPL/nonfree disabled")
need(manifest.get("httpsRequired") is True, "FFmpeg runtime must require HTTPS support")
need("--enable-openssl" in manifest.get("configureFlags", []) and "--enable-zlib" in manifest.get("configureFlags", []), "FFmpeg runtime must explicitly enable OpenSSL and zlib with autodetect disabled")
for key in ("ffmpegSourceSha256", "opensslSourceSha256"):
    need(bool(re.fullmatch(r"[0-9a-f]{64}", str(manifest.get(key, "")))), f"{key} must be a pinned SHA-256")

installer = text("tools/install-ffmpeg-runtime.py")
need(has(installer, "download(manifest[\"ffmpegSourceUrl\"]", "download(manifest[\"opensslSourceUrl\"]"), "installer must download only manifest-pinned FFmpeg/OpenSSL sources")
need(has(installer, "source digest mismatch", "safe_extract", "ensure_pie"), "installer must hash-check, safe-extract, and verify PIE output")
need("aarch64-linux-android" in installer and "max-page-size=16384" in installer, "installer must cross-build AArch64 with 16 KB linker alignment")
need("COPYING.LGPLv2.1" in installer and "LICENSE.txt" in installer, "installer must preserve upstream license texts")

verifier = text("tools/verify-ffmpeg-runtime.py")
need(has(verifier, "--require-payload", "--require-16kb-alignment", "PT_LOAD", "libxdm_ffmpeg.so", "libxdm_ffprobe.so"), "runtime verifier lacks strict payload/ELF/APK checks")
need("assets/licenses/FFmpeg-LGPL-2.1.txt" in verifier and "assets/licenses/OpenSSL-Apache-2.0.txt" in verifier, "APK verifier must attest bundled third-party license assets")

runtime = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/EmbeddedFfmpegRuntime.kt")
launcher = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegProcessLauncher.kt")
compiler = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompiler.kt")
models = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegRuntimeModels.kt")
probe = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfprobeJsonParser.kt")
trust = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/AndroidTrustBundleProvider.kt")
need(has(runtime, "applicationInfo.nativeLibraryDir", "libxdm_ffmpeg.so", "libxdm_ffprobe.so", "httpsSupported", "VersionMismatch", "AndroidTrustBundleProvider", "withAndroidTrust"), "embedded runtime must resolve app-owned binaries and enforce version/verified-HTTPS capability")
need(has(trust, "AndroidCAStore", "BEGIN CERTIFICATE", "noBackupFilesDir"), "embedded HTTPS runtime must materialize Android CA trust as an app-private PEM bundle")
need("ProcessBuilder" in launcher and "sh -c" not in launcher and "Runtime.getRuntime().exec" not in launcher, "FFmpeg launcher must use typed ProcessBuilder execution without shell source")
need(has(models, "sealed interface FfmpegOperation", "RecordStream", "Probe", "Remux", "MuxTracks", "ExtractAudio", "FastStart"), "typed FFmpeg operation model is incomplete")
need(has(compiler, "Invalid HTTP header name", "HTTP header value contains a control delimiter", "-c", "copy", "-tls_verify", "-ca_file"), "command compiler must reject header injection, force verified HTTPS, and prefer stream-copy")
need("redactFfmpegDiagnostic" in models and "redactFfmpegDiagnostic" in launcher, "FFmpeg diagnostics must redact credential-bearing URLs/headers before display")
need(has(probe, "FfprobeResult", "codec_type", "format_name"), "FFprobe JSON parser is incomplete")

settings = text("settings.gradle.kts")
app_gradle = text("app/build.gradle.kts")
module_gradle = text("media-ffmpeg/build.gradle.kts")
need('":media-ffmpeg"' in settings, "media-ffmpeg module is not registered")
need('implementation(project(":media-ffmpeg"))' in app_gradle, "app does not depend on media-ffmpeg")
need("libxdm_ffmpeg.so" in app_gradle and "libxdm_ffprobe.so" in app_gradle, "app native packaging does not retain FFmpeg/FFprobe")
need(has(module_gradle, 'ndkVersion = "29.0.14206865"', "installPinnedFfmpegRuntime", "verifyFfmpegRuntime", "jniLibs.useLegacyPackaging = true"), "media-ffmpeg Gradle wiring is incomplete")

planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
execution = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
dispatch = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
termux = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt")
worker = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaWorkerBridge.kt")
need("requiresTermux = strategy == MediaDownloadStrategy.YtDlp" in planner, "FfmpegLive is still classified as requiring Termux")
need(has(execution, "EmbeddedFfmpegLive", '"embedded-ffmpeg"', "AndroidMediaWorkKind.EmbeddedFfmpeg", 'ffmpegNeedsContainer', '".mkv"'), "execution planner does not own an embedded FFmpeg lane with a real media output container")
need("MediaExecutionLane.LiveRecording" not in execution + dispatch + worker + termux, "legacy Termux-backed LiveRecording execution lane remains")
need(has(dispatch, "NeedsEmbeddedFfmpegRuntime", "LaunchEmbeddedFfmpeg"), "dispatcher does not gate/launch the embedded FFmpeg lane")
need("EmbeddedFfmpegLive" in termux and "BlockedDiagnostic" in termux, "Termux adapter must refuse ownership of embedded FFmpeg work")
need("YtDlpLiveRecording" not in termux, "obsolete Termux-owned live recording launch kind remains after embedded FFmpeg cutover")
need("MediaWorkerBridgeKind.EmbeddedFfmpeg" in worker, "worker bridge does not model embedded FFmpeg ownership")

manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt")
app = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
owner = text("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt")
repo = text("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
need(has(manager, "enqueueLiveRecording", "FfmpegOperation.RecordStream", "runtime.probe", "prepared.promote()", "RecoveryRequired", "CoroutineStart.LAZY", "outputMimeType"), "embedded media manager lacks durable recording/probe/publication/recovery ownership")
need("EmbeddedFfmpeg" in owner and "MediaOutputOwnerKind.EmbeddedFfmpeg" in manager, "durable output ownership does not distinguish embedded FFmpeg")
need("hideMediaOutput" in repo, "repository cannot tombstone embedded FFmpeg output generations")
need(has(app, "EmbeddedFfmpegRuntime(this)", "EmbeddedFfmpegMediaManager", "recoverInterruptedJobs"), "application container does not own/recover embedded FFmpeg runtime jobs")
need(has(vm, "runFfmpegSelfTest", "NeedsEmbeddedFfmpegRuntime", "enqueueLiveRecording", "MediaExecutionLane.EmbeddedFfmpegLive"), "ViewModel does not integrate FFmpeg health and execution")

workspace = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
need(has(workspace, "Embedded FFmpeg + FFprobe", "Run FFmpeg self-test", "ffmpegDiagnostics"), "Developer Center does not expose FFmpeg/FFprobe health and self-test")

repo_root = ROOT.parents[1]
devtool = (repo_root / ".devtool.toml").read_text(encoding="utf-8") if (repo_root / ".devtool.toml").is_file() else ""
need(
    ":media-ffmpeg:installPinnedFfmpegRuntime" in devtool
    or (":media-ffmpeg:verifyFfmpegRuntime" in devtool and "verifyFfmpegDebugApkRuntime" in app_gradle)
    or (
        'dependsOn(":media-ffmpeg:installPinnedFfmpegRuntime")' in app_gradle
        and "dependsOn(installPinnedFfmpegRuntime)" in module_gradle
    )
    or (
        "requireFfmpegRuntime" in module_gradle
        and "dependsOn(installPinnedFfmpegRuntime)" in module_gradle
    ),
    "FFmpeg runtime installation/provenance is neither Devtool-owned nor packaging-owned by Gradle",
)
need(":media-ffmpeg:testDebugUnitTest" in devtool, "Devtool unit-test phase does not include media-ffmpeg tests")
need(":media-ffmpeg:verifyFfmpegRuntime" in devtool, "Devtool preflight does not verify FFmpeg runtime provenance")

# The verifier itself must succeed in source-only mode; strict binary/APK verification is run in the target build environment.
verify = subprocess.run([sys.executable, str(ROOT / "tools/verify-ffmpeg-runtime.py")], cwd=ROOT, text=True, capture_output=True)
need(verify.returncode == 0, "source-only FFmpeg verifier failed: " + (verify.stderr or verify.stdout).strip())

if errors:
    print("FF01 embedded FFmpeg runtime/media execution contract FAILED", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("FF01 embedded FFmpeg runtime/media execution contract passed")
