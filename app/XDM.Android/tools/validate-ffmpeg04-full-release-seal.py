#!/usr/bin/env python3
"""FF04 final seal: runtime attestation, APK gate, fixtures, no-Termux acceptance, and roadmap closure."""
from __future__ import annotations

import hashlib
import json
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


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

# FF01-FF03 must remain green; FF04 is a seal, not a replacement authority.
for prerequisite in (
    "tools/validate-ffmpeg01-embedded-runtime-media-execution.py",
    "tools/validate-ffmpeg02-media-mux-hls-postprocessing.py",
    "tools/validate-ffmpeg03-runtime-routing-termux-ui-reliability.py",
    "tools/validate-execution-media-semantics-repair.py",
):
    result = subprocess.run([sys.executable, str(ROOT / prerequisite)], cwd=ROOT, text=True, capture_output=True)
    need(result.returncode == 0, f"prerequisite failed: {prerequisite}: " + (result.stderr or result.stdout).strip())

manifest_path = ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
need(manifest.get("buildProfile") == "stream-copy-downloader-v1", "runtime build profile is not pinned to FF04 downloader profile")
need(manifest.get("ndkVersion") == "29.0.14206865", "FF04 runtime must remain pinned to Android NDK 29")
need(manifest.get("gplEnabled") is False and manifest.get("nonfreeEnabled") is False, "runtime licensing profile must fail closed for GPL/nonfree")
need(set(manifest.get("forbiddenConfigureFlags", [])) >= {"--enable-gpl", "--enable-nonfree", "--enable-version3"}, "forbidden FFmpeg licensing flags are not declared")
need(set(manifest.get("requiredProtocols", [])) >= {"file", "http", "https", "tcp", "tls", "crypto"}, "required downloader protocols are not pinned")
need(int(manifest.get("maxCombinedBinaryBytes", 0)) > 0 and int(manifest.get("maxCompressedApkRuntimeBytes", 0)) > 0, "runtime/APK size budgets are missing")
need("--disable-avdevice" in manifest.get("configureFlags", []), "unused FFmpeg avdevice surface is not disabled")
need(manifest.get("ffmpegVersion") == "9.0.1" and "--disable-postproc" not in manifest.get("configureFlags", []), "FFmpeg 9.0.1 removed libpostproc; obsolete --disable-postproc must not be passed")

installer = text("tools/install-ffmpeg-runtime.py")
verifier = text("tools/verify-ffmpeg-runtime.py")
runtime = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/EmbeddedFfmpegRuntime.kt")
models = text("media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegRuntimeModels.kt")
need(has(installer, "llvm-strip", "--strip-unneeded", '"buildProfile": manifest["buildProfile"]', "profile_flags = list(manifest[\"configureFlags\"])", "validate_ffmpeg_profile_flags"), "pinned builder does not strip/attest the manifest-driven FF04 runtime profile")
need(has(verifier, "maxCombinedBinaryBytes", "maxCompressedApkRuntimeBytes", "configureFlags", "forbiddenConfigureFlags", "--apk"), "runtime verifier lacks FF04 size/config/APK enforcement")
need(has(runtime, "verifyInstalledAttestation", "ffmpeg-runtime.lock.json", "ffmpegBinarySha256", "ffprobeBinarySha256", "MessageDigest", "buildProfile", "requiredProtocols", "requiredConfigureFlags", "forbiddenConfigureFlags"), "app runtime does not verify packaged attestation/configuration/protocol profile before readiness")
need(has(models, "attestationVerified", "buildConfigurationVerified", "maxCombinedBinaryBytes", "requiredProtocols", "buildProfile"), "runtime capability model does not expose attestation/build-profile/protocol truth")
need("attestationVerified && buildConfigurationVerified" in models, "runtime ready state must require attestation and build-profile verification")
need(manifest.get("opensslSourceUrl") == "https://github.com/openssl/openssl/releases/download/openssl-3.5.8/openssl-3.5.8.tar.gz", "OpenSSL 3.5.8 must use the permanent official GitHub release URL")
need("openssl-library.org/source/openssl-3.5.8.tar.gz" not in json.dumps(manifest, sort_keys=True), "obsolete OpenSSL source URL must not return to the runtime manifest")
need(has(installer, "ensure_host_build_tools", "XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS", 'pkg, "install", "-y"', '"perl": "perl"', '"make": "make"', '"pkg-config": "pkg-config"'), "native Termux runtime builder must bootstrap OpenSSL/FFmpeg host tools instead of failing when Perl is absent")
need('-D__ANDROID_API__={api}' not in installer, "OpenSSL builder must not redundantly redefine __ANDROID_API__; Android clang owns that API macro")
need(has(installer, ".xdm-openssl-configured.json", ".xdm-openssl-installed.json", ".xdm-ffmpeg-configured.json", "marker_matches", "atomic_json(configured_marker"), "native builder lacks persistent configure/install markers for interrupted-build resume")
need(has(installer, "build_cache_lock", "LOCK_EX", "build_libs", 'return ["make"] if verbose_make else ["make", "-s"]', "XDM_FFMPEG_VERBOSE_MAKE", "XDM_FFMPEG_BUILD_HEARTBEAT_SECONDS"), "native builder lacks serialized quiet/resumable compilation with visible heartbeat and verbose escape hatch")
need(has(installer, "build_cache_manifest_payload", 'cache_flags.append("--disable-postproc")', "json.dumps(cache_manifest, sort_keys=True) + str(ndk) + platform.machine()"), "FF04 v7 must preserve the v5/v6 deterministic build-cache identity while removing postproc from the real configure profile")
need(has(installer, "validate_ffmpeg_profile_flags", '["./configure", *flags, "--help"]', "rejected the pinned configure ", "profile before native compilation"), "FF04 v7 must preflight the exact pinned profile through the real FFmpeg configure parser before compiling")

builder_regression = ROOT / "tools/test-ffmpeg-runtime-builder-resume.py"
need(builder_regression.is_file(), "FF04 v7 runtime-builder resume/configure-surface regression test is missing")
if builder_regression.is_file():
    result = subprocess.run([sys.executable, str(builder_regression)], cwd=ROOT, text=True, capture_output=True)
    need(result.returncode == 0, "FF04 v7 runtime-builder resume/configure-surface regression failed: " + (result.stderr or result.stdout).strip())

planner = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
execution_library = text("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
dev_screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt")
ffmpeg_test = text("media-ffmpeg/src/test/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompilerTest.kt")
ff02_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/FfmpegAdaptiveExecutionFf02Test.kt")
routing_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaRuntimeRoutingFf03Test.kt")
media_capture_test = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt")
need('takeIf { strategy == MediaDownloadStrategy.YtDlp }' in planner, "embedded FFmpeg plans must not retain yt-dlp format/extra arguments")
need('(variant.isDefault || variant.isAutoselect)' in planner and '?: variants.firstOrNull { variant -> variant.kind == kind' not in planner, "adaptive normalization must not silently pick an arbitrary unmarked rendition")
need('if (lane == MediaExecutionLane.YtDlpAdaptive) tempCookieFilePlan(spec) else null' in execution_library, "only the yt-dlp lane may materialize transient cookie files")
need(has(dev_screen, 'MediaDispatchReadiness.NeedsEmbeddedFfmpegRuntime', 'XdmStatusTone.Info'), "Developer Tools readiness tone is not exhaustive for embedded FFmpeg setup")
need('assertEquals(10_000L, command.expectedDurationMs)' in ffmpeg_test, "FFmpeg expected-duration regression test must assert Long semantics")
need('listOf("--verify", "ffprobe")' in ff02_test, "FF02 test must assert the typed --verify ffprobe argument pair")
need('capture().copy(pageUrl = null)' in routing_test, "FF03 public fallback fixture must actually be header-free")
ff03_ui_contract_test = text("app/src/test/kotlin/com/mikeyphw/xdm/android/Ffmpeg03RuntimeRoutingUiContractTest.kt")
need(has(ff03_ui_contract_test, 'requireNotNull(System.getProperty("user.dir"))', "generateSequence(File(userDir).canonicalFile)", 'File(candidate, "app/src/main").isDirectory', 'File(candidate, "app/XDM.Android/app/src/main").isDirectory', ".firstOrNull()"), "FF03 routing UI contract test must resolve Android root robustly from module, Android-root, or repository-root user.dir")
need('File(System.getProperty("user.dir"))' not in ff03_ui_contract_test, "FF03 routing UI contract test must not pass nullable user.dir directly to java.io.File")
need('fun resolverPickerGroupsCreateEmbeddedAdaptivePlanAndPreviewMetadata()' in media_capture_test and 'assertEquals(MediaDownloadStrategy.FfmpegAdaptive, plan.strategy)' in media_capture_test and 'assertEquals(null, plan.ytDlpFormatSelector)' in media_capture_test, "resolver picker regression must assert embedded adaptive ownership rather than stale yt-dlp format state")

fixtures_manifest_path = ROOT / "app/src/androidTest/assets/ffmpeg04/fixtures.json"
fixtures_manifest = json.loads(fixtures_manifest_path.read_text(encoding="utf-8")) if fixtures_manifest_path.is_file() else {}
fixture_entries = fixtures_manifest.get("files", [])
need(len(fixture_entries) == 2, "FF04 deterministic fixture manifest must contain video-only and audio-only inputs")
for entry in fixture_entries:
    p = ROOT / "app/src/androidTest/assets/ffmpeg04" / str(entry.get("name", ""))
    need(p.is_file(), f"missing FF04 fixture {p.name}")
    if p.is_file():
        need(p.stat().st_size == entry.get("bytes"), f"fixture size mismatch: {p.name}")
        need(sha256(p) == entry.get("sha256"), f"fixture digest mismatch: {p.name}")

instrumented = text("app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Ffmpeg04EmbeddedRuntimeAcceptanceInstrumentedTest.kt")
no_termux = text("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaRuntimeNoTermuxFf04Test.kt")
need(has(instrumented, "EmbeddedFfmpegRuntime", "attestationVerified", "buildConfigurationVerified", "muxTracks", "AndroidDestinationWriter", "DestinationUris.APP_PRIVATE_DOWNLOADS", "prepared.promote()", "promoted.atomic", "runtime.probe(staged.absolutePath)", "runtime.probe(committed.absolutePath)", "committedProbe.videoStreams", "committedProbe.audioStreams"), "device acceptance test does not prove attested embedded mux + atomic Android publication + committed-file FFprobe")
need(has(no_termux, "freshInstallNeedsNoTermuxWhenEmbeddedRuntimeIsHealthy", "termuxBridgeReady = false", "MediaFfmpegRuntimeSource.Embedded"), "no-Termux routing acceptance test is missing")

module_gradle = text("media-ffmpeg/build.gradle.kts")
app_gradle = text("app/build.gradle.kts")
devtool = text("../../.devtool.toml")
release_gate = text("tools/run-final-release-gate.sh")
dev_workspace = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
need(has(app_gradle, "XDM_FFMPEG_PAYLOAD_VERIFIED", "verifyFfmpeg04FullReleaseSeal", "verifyFfmpegDebugApkRuntime", "--require-16kb-alignment", "app-debug.apk"), "app Gradle release tasks/evidence do not enforce FF04 payload/APK gate")
need('tasks.named("assembleDebug")' not in app_gradle, "FF04 must not eagerly resolve AGP assembleDebug during build-script evaluation")
need(has(app_gradle, 'tasks.matching { it.name == "assembleDebug" }.configureEach', 'dependsOn(":media-ffmpeg:installPinnedFfmpegRuntime")'), "FF04 APK packaging must lazily depend on the freshly installed embedded runtime")
need(has(app_gradle, "verifyFfmpeg04FinalReleaseValidation", "verifyFfmpegRoadmapPostSealHotfix", ":media-ffmpeg:testDebugUnitTest", ":media:test", "assembleDebugAndroidTest", "verifyFfmpegDebugApkRuntime", "finalRemediationStaticGate", "lintDebug"), "FF04 staged final validation lifecycle task is incomplete or omits the post-seal roadmap ownership audit")
need(has(app_gradle, 'subproject.tasks.matching { it.name.contains("lint", ignoreCase = true) }', "mustRunAfter(finalRemediationStaticGate)"), "FF04 combined validation must keep lint behind the static/runtime stages")
for dependency_native in ("**/libandroidx.graphics.path.so", "**/libdatastore_shared_counter.so"):
    need(dependency_native in app_gradle, f"Termux-native AGP strip exception missing for {dependency_native}")
need("**/*.so" not in app_gradle, "FF04 must not broaden JNI debug-symbol retention to every native library")
need("sourceSets.getByName(\"main\")" not in module_gradle, "FF04 must not use the AGP 9.2-incompatible legacy library source-set cast")
need(has(module_gradle, "androidComponents", "variant.sources.assets?.addStaticSourceDirectory(\"runtime\")"), "FF04 runtime assets must use the AGP variant sources API")
need(has(module_gradle, "task.name.startsWith(\"merge\")", "task.name.endsWith(\"Assets\")", "task.name.contains(\"JniLib\", ignoreCase = true)", "dependsOn(installPinnedFfmpegRuntime)"), "FF04 media asset/JNI merges must depend on the pinned runtime installer")
need(has(module_gradle, 'task.name.contains("lint", ignoreCase = true)', "mustRunAfter(installPinnedFfmpegRuntime)"), "FF04 media-ffmpeg lint tasks must be ordered after native runtime installation when both are scheduled")
need(has(installer, "local_properties_sdk_roots", "termux-native-llvm", "--target=", "sdk.dir"), "FF04 runtime builder lacks daemon-safe SDK discovery or native ARM64 Termux LLVM fallback")
need("toolchainBackend" in verifier, "FF04 verifier does not attest the runtime host toolchain backend")
need(has(devtool, ":app:verifyFfmpeg04FullReleaseSeal", ":app:verifyFfmpegDebugApkRuntime", ":app:assembleDebugAndroidTest"), "Devtool target does not carry FF04 contract/APK/instrumentation packaging gates")
need("tools/validate-ffmpeg04-full-release-seal.py" in release_gate, "canonical final static gate does not include FF04")
need("tools/validate-ffmpeg-roadmap-postseal-hotfix.py" in release_gate, "canonical final static gate does not include the post-seal FFmpeg roadmap ownership audit")
need(has(dev_workspace, "Embedded FFmpeg payload", "XDM_FFMPEG_PAYLOAD_VERIFIED", "XDM_FFMPEG_PAYLOAD_GATE_CONFIGURED"), "Developer Center does not surface FFmpeg payload release truth")
phase10 = text("tools/validate-bug-hunt-phase10-release-upgrade-packaging.py")
need(has(phase10, "libaria2c.so", "libxdm_ffmpeg.so", "libxdm_ffprobe.so", "libandroidx.graphics.path.so", "libdatastore_shared_counter.so", "exact allowlist"), "historical Phase 10 packaging gate is not harmonized with the app-owned runtimes and exact Termux strip exceptions")

report = text("../../XDM_FFMPEG04_FULL_RELEASE_SEAL_REPORT.md")
need(has(report, "FF01", "FF02", "FF03", "no-Termux", "16 KB", "FFprobe", "NDK 29"), "FF04 final report does not document complete promise closure")
hotfix_report = text("../../XDM_FFMPEG_ROADMAP_POSTSEAL_HOTFIX_REPORT.md")
need(has(hotfix_report, "NativeHlsMediaManager", "production caller", "embedded-first", "explicit Termux fallback", "roadmap audit"), "post-seal FFmpeg roadmap audit report is missing or incomplete")

if errors:
    print("FF04 full release seal FAILED", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("FF04 full release seal passed")
