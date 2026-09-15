#!/usr/bin/env python3
"""Validate the XDM Android Gradle/task-graph optimization contract without reducing release coverage."""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
REPO = ANDROID.parents[1]
errors: list[str] = []


def read(path: Path) -> str:
    if not path.is_file():
        errors.append(f"missing {path.relative_to(REPO) if path.is_relative_to(REPO) else path}")
        return ""
    return path.read_text(encoding="utf-8")


def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def has(source: str, *needles: str) -> bool:
    return all(needle in source for needle in needles)


devtool = read(REPO / ".devtool.toml")
app_gradle = read(ANDROID / "app/build.gradle.kts")
aria_gradle = read(ANDROID / "transfer-aria2/build.gradle.kts")
browser_gradle = read(ANDROID / "browser-extension/build.gradle.kts")
final_gate = read(ANDROID / "tools/run-final-release-gate.sh")
aria_installer = read(ANDROID / "tools/install-aria2-runtime.py")
ff01 = read(ANDROID / "tools/validate-ffmpeg01-embedded-runtime-media-execution.py")
ff02 = read(ANDROID / "tools/validate-ffmpeg02-media-mux-hls-postprocessing.py")
ff03 = read(ANDROID / "tools/validate-ffmpeg03-runtime-routing-termux-ui-reliability.py")
ff04 = read(ANDROID / "tools/validate-ffmpeg04-full-release-seal.py")
postseal = read(ANDROID / "tools/validate-ffmpeg-roadmap-postseal-hotfix.py")

# Gradle owns the FFmpeg prerequisite DAG; downstream Python validators retain standalone safety
# through an explicit escape hatch rather than recursively re-running the chain during Gradle use.
need(has(ff02, '--skip-prerequisites', 'if not args.skip_prerequisites'), "FF02 has no Gradle-owned prerequisite mode")
need(has(ff03, '--skip-prerequisites', 'if not args.skip_prerequisites'), "FF03 has no Gradle-owned prerequisite mode")
need(has(ff04, '--skip-prerequisites', 'if not args.skip_prerequisites'), "FF04 has no Gradle-owned prerequisite mode")
need(has(postseal, '--skip-prerequisites', 'if not args.skip_prerequisites'), "post-seal validator has no Gradle-owned prerequisite mode")
need(has(app_gradle,
         'dependsOn(verifyFfmpeg01EmbeddedRuntimeContract)',
         'dependsOn(verifyFfmpeg02MediaMuxHlsPostprocessingContract)',
         'dependsOn(verifyFfmpeg03RuntimeRoutingTermuxUiReliabilityContract, verifyExecutionMediaSemanticsRepair)',
         '"--skip-prerequisites"'),
     "app Gradle file does not own the flattened FF01→FF04 prerequisite DAG")
need(has(app_gradle, 'trackStaticValidation("ffmpeg01")', 'trackStaticValidation("ffmpeg04")', 'outputs.file(stampFile)'),
     "static FFmpeg validators are missing up-to-date stamps")

# Canonical final gate stays complete when called directly, but skips Gradle-owned validators when
# finalRemediationStaticGate already executed them.
need(has(app_gradle, 'environment("XDM_GRADLE_ORCHESTRATED", "1")', 'commandLine("bash", "tools/run-final-release-gate.sh", "--ci")'),
     "finalRemediationStaticGate does not preserve the historical command while declaring Gradle orchestration")
need(has(final_gate, 'XDM_GRADLE_ORCHESTRATED', 'ffmpeg_gradle_owned_validators', 'is_ffmpeg_gradle_owned', '--skip-prerequisites'),
     "canonical final shell gate cannot avoid recursively repeating Gradle-owned FFmpeg validators")
for validator in (
    'validate-ffmpeg01-embedded-runtime-media-execution.py',
    'validate-ffmpeg02-media-mux-hls-postprocessing.py',
    'validate-ffmpeg03-runtime-routing-termux-ui-reliability.py',
    'validate-execution-media-semantics-repair.py',
    'validate-ffmpeg04-full-release-seal.py',
    'validate-ffmpeg-roadmap-postseal-hotfix.py',
):
    need(validator in final_gate, f"canonical final gate lost {validator}")

# Devtool no longer schedules the native installers in both build and package phases. Packaging
# correctness is expressed by Gradle dependencies, so standalone assemble/package remains safe.
def array_block(name: str) -> str:
    match = re.search(rf"(?ms)^{re.escape(name)}\s*=\s*\[(.*?)^\]", devtool)
    return match.group(1) if match else ""

build_block = array_block("build")
package_block = array_block("package")
need('assembleDebug' in build_block, "Devtool build phase no longer assembles debug APK")
need(re.search(r'"clean"\s*,\s*"assembleDebug"', build_block) is not None, "Devtool build phase must clean stale outputs immediately before assembleDebug")
need('clean' not in package_block, "Devtool package phase must not repeat clean during Android-test/APK attestation work")
need(':app:lintDebug' in array_block("lint") and '"lintDebug"' not in array_block("lint"), "Devtool still invokes the root lintDebug aggregate instead of the app-scoped lint gate")
need('installOfficialAria2Runtime' not in build_block + package_block, "Devtool still schedules aria2 installer redundantly across split phases")
need('installPinnedFfmpegRuntime' not in build_block + package_block, "Devtool still schedules FFmpeg installer redundantly across split phases")
need(':app:assembleDebugAndroidTest' in package_block and ':app:verifyFfmpegDebugApkRuntime' in package_block,
     "Devtool package phase lost Android-test/APK runtime attestation")
need(has(devtool, ':app:verifyGradleTaskGraphOptimization', ':app:verifyFfmpeg04FullReleaseSeal', ':app:verifyFfmpegRoadmapPostSealHotfix'),
     "Devtool preflight does not include optimized graph/self-check and FF04 authorities")
need(
    "ReservedCodeCacheSize=256m" in read(ANDROID / "gradle.properties")
    and "+UseCodeCacheFlushing" in read(ANDROID / "gradle.properties"),
    "Gradle defaults do not reserve bounded CodeCache for D8/R8 Android-test dex merging on low-RAM Termux",
)



# XAR16 v18: validation must remain complete but no longer launch a high-pressure
# parallel Gradle daemon on the user's low-storage/low-RAM Termux tablet.
need('org.gradle.parallel=false' in devtool and 'parallel = false' in devtool,
     "Devtool/Gradle validation still enables parallel execution under Termux memory guard")
need('no_daemon = true' in devtool and 'stop_daemon_after_phase = true' in devtool,
     "Devtool validation does not force daemon-free phase isolation after v17 memory-guard failures")
need('max_workers = 1' in devtool and 'cpu_limit = 1' in devtool,
     "Devtool validation does not clamp workers/CPU to one for low-memory native Termux validation")
need('org.gradle.jvmargs=-Xmx1280m' in read(ANDROID / "gradle.properties")
     and 'org.gradle.workers.max=1' in read(ANDROID / "gradle.properties")
     and 'org.gradle.daemon=false' in read(ANDROID / "gradle.properties")
     and 'org.gradle.parallel=false' in read(ANDROID / "gradle.properties"),
     "gradle.properties does not encode the v18 low-memory Termux execution contract")
need('-Dorg.gradle.jvmargs=' not in devtool,
     "Devtool extra Gradle args still inject a duplicate high-pressure org.gradle.jvmargs value")
need(not (ANDROID / "transfer-native/transfer-native").exists(),
     "overlay still carries accidental nested transfer-native/transfer-native source mirror")

# aria2 now behaves like the FFmpeg installer: declared inputs/outputs plus packaging ownership.
need('outputs.upToDateWhen { false }' not in aria_gradle, "aria2 installer is still forced dirty")
need(has(aria_gradle, 'inputs.files(', 'runtime/aria2-runtime.json', 'tools/install-aria2-runtime.py',
         'outputs.files(', 'libaria2c.so', 'aria2-runtime.lock.json', 'dependsOn(installOfficialAria2Runtime)'),
     "aria2 installer lacks incremental inputs/outputs or JNI packaging ownership")
need(has(aria_installer, 'default_cache_dir', 'cached_download_path', 'cached_payload_usable', 'Discarding unusable cached aria2 payload', 'Reusing cached aria2 payload', '--no-download-cache'),
     "aria2 installer lacks deterministic self-healing download-cache reuse")


# Runtime verification is also incremental across Devtool split phases; exact runtime/APK bytes
# remain inputs, so a changed payload cannot reuse a stale success marker.
ffmpeg_gradle = read(ANDROID / "media-ffmpeg/build.gradle.kts")
need(has(ffmpeg_gradle, 'verifyFfmpegRuntime.success', 'inputs.property("requireFfmpegRuntime"', 'tools/verify-ffmpeg-runtime.py',
         'dependsOn(installPinnedFfmpegRuntime)'),
     "FFmpeg runtime verification lacks incremental tracking or an explicit installer dependency")
need(has(aria_gradle, 'verifyAria2Runtime.success', 'inputs.property("requireAlignedAria2Runtime"', 'tools/verify-aria2-runtime.py',
         'dependsOn(installOfficialAria2Runtime)'),
     "aria2 runtime verification lacks incremental tracking or an explicit installer dependency")
need(has(app_gradle, 'verifyFfmpegDebugApkRuntime.success', 'outputs/apk/debug/app-debug.apk', 'media-ffmpeg/runtime/ffmpeg-runtime.lock.json'),
     "APK FFmpeg attestation lacks incremental input/output tracking")

# Browser JS/source checks can be reused across split Gradle invocations when unchanged.
need(has(browser_gradle, 'inputs.dir(layout.projectDirectory.dir("tests"))',
         'outputs.file(successMarker)', 'test-results/jsTest/success.marker',
         'validation/firefox-extension.success', 'inputs.file(layout.projectDirectory.file("tools/validate_extension.py"))'),
     "Firefox JavaScript/extension verification is not incrementally tracked")

# The previous FF04 lifecycle serialized tests → androidTest → APK attestation → static gate. Only
# lint retains ordering for the known generated-JNI race; the independent roots should be free.
need('mustRunAfter("assembleDebugAndroidTest")' not in app_gradle, "APK attestation is still unnecessarily serialized after Android-test assembly")
need('mustRunAfter("testDebugUnitTest")' not in app_gradle, "Android-test assembly is still unnecessarily serialized after app unit tests")
need('mustRunAfter("verifyFfmpegDebugApkRuntime")' not in app_gradle, "static gate is still unnecessarily serialized after APK attestation")
need('mustRunAfter(finalRemediationStaticGate)' in app_gradle, "lint ordering guard for generated JNI race was removed")

# FF01 accepts Gradle-owned packaging as authoritative even though Devtool no longer lists the
# installer as a duplicate top-level phase task.
need(
    'FFmpeg runtime installation/provenance is neither Devtool-owned nor packaging-owned by Gradle' in ff01
    or 'installPinnedFfmpegRuntime' in ff01
    or 'requireFfmpegRuntime' in ff01,
    "FF01 validator was not harmonized with packaging-owned runtime installation",
)

contract_test = read(ANDROID / "app/src/test/kotlin/com/mikeyphw/xdm/android/GradleTaskGraphOptimizationContractTest.kt")
need(has(contract_test, 'requireNotNull(System.getProperty("user.dir"))', 'firstOrNull', 'requireNotNull(androidRoot.parentFile?.parentFile)'),
     "Gradle optimization Kotlin contract still relies on nullable Java user.dir/parent values")

cache_test = subprocess.run(
    [sys.executable, str(ANDROID / "tools/test-aria2-runtime-installer-cache.py")],
    cwd=ANDROID, text=True, capture_output=True,
)
need(cache_test.returncode == 0, "aria2 download-cache regression failed: " + (cache_test.stderr or cache_test.stdout).strip())

if errors:
    print("Gradle task-graph optimization contract FAILED", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("Gradle task-graph optimization contract passed")
