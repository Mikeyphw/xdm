#!/usr/bin/env python3
"""XAR12 media execution/post-processing closure contract."""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

CANONICAL_IDS = [
    "S12-01", "S12-02", "S12-03", "S12-04", "S12-05", "S12-06", "S12-07", "S12-08",
    "S12-09", "S12-10", "S12-11", "S12-12", "S12-13", "S12-14", "S12-15",
    "DS6-S12-01", "DS6-S12-02", "DS6-S12-03", "DS6-S12-04", "DS6-S12-05", "DS6-S12-06", "DS6-S12-07",
    "RERUN56-S12-01",
]

files = {
    "native_engine": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt",
    "native_manager": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt",
    "ffmpeg_compiler": ROOT / "media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegCommandCompiler.kt",
    "ffmpeg_verifier": ROOT / "media-ffmpeg/src/main/kotlin/com/mikeyphw/xdm/android/media/ffmpeg/FfmpegMediaVerifier.kt",
    "runtime_routing": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaRuntimeRouting.kt",
    "media_library": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "security_policy": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionSecurityPolicy.kt",
    "termux_shell": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt",
    "termux_adapter": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "embedded_manager": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/EmbeddedFfmpegMediaManager.kt",
    "manifest": ROOT / "PROJECT_MANIFEST.json",
    "final_gate": ROOT / "tools/run-final-release-gate.sh",
    "gradle": ROOT / "app/build.gradle.kts",
    "doc": ROOT / "docs/remediation/XAR12-MEDIA-EXECUTION-SEAL.md",
}

missing_files = [str(path.relative_to(ROOT)) for path in files.values() if not path.exists()]
if missing_files:
    raise SystemExit("Missing XAR12 files: " + ", ".join(missing_files))

text = {name: path.read_text() for name, path in files.items()}
checks: list[tuple[str, bool]] = []

def has(name: str, needle: str) -> bool:
    return needle in text[name]

def rx(name: str, pattern: str) -> bool:
    return re.search(pattern, text[name], re.S) is not None

# Native HLS playlist semantics.
checks += [
    ("missing AES-128 key URI fails closed", has("native_engine", "MissingKeyUri") and has("native_engine", "it.isAes128 && it.uri.isNullOrBlank()")),
    ("AES-128 explicit IV strict length", has("native_engine", "InvalidAesIv") and has("native_engine", "isValidAes128IvHex")),
    ("segment URI without EXTINF rejected", has("native_engine", "MissingExtinf") and has("native_engine", "hasSegmentWithoutExtinf")),
    ("EXT-X-GAP becomes skipped durable part", has("native_engine", "#EXT-X-GAP") and has("native_engine", "NativeHlsPartState.Skipped") and has("native_manager", "part.gap || part.state == NativeHlsPartState.Skipped")),
    ("discontinuity sequence included in durable part id", has("native_engine", "#EXT-X-DISCONTINUITY-SEQUENCE") and has("native_engine", "hls-part:$discontinuity:$mediaSequence")),
    ("implicit byte-range offsets scoped per resource", has("native_engine", "byteRangeOffsetsByResource") and has("native_engine", "byteRangeOffsetsByResource[resolvedUrl]")),
    ("EXT-X-MAP emitted only on first applicable segment", has("native_engine", "activeInitMapIdentity") and has("native_engine", "currentMap?.takeIf")),
    ("storage preflight includes HLS byte-range totals", has("native_engine", "knownPartBytes") and has("native_engine", "part.byteRange?.length")),
]

# Native HLS network/cancellation/body correctness.
checks += [
    ("native HLS body reads are bounded", has("native_manager", "readBoundedBody") and has("native_manager", "MAX_HLS_SEGMENT_BYTES") and has("native_manager", "HLS response exceeded bounded cap")),
    ("native HLS cancellation owns body read call", has("native_manager", "RunningHttpResponse") and has("native_manager", "if (cause is CancellationException) call.cancel()")),
    ("native HLS validates 206 Content-Range", has("native_manager", "validateContentRange") and has("native_manager", "HLS Content-Range does not match the requested range")),
    ("native HLS credentials scoped by full origin", has("native_manager", "originKeyForSecurity") and has("native_manager", "scheme == \"https\" -> 443") and has("native_manager", "SENSITIVE_NATIVE_HLS_HEADERS")),
    ("native HLS cancel after commit reconciles journal first", rx("native_manager", r"catch \(cancelled: CancellationException\).*reconcileCommittedPublication\(row\)") and has("native_manager", "if (reconcileCommittedPublication(row)) return")),
    ("Native HLS finalization permits explicit skipped GAP parts", has("native_manager", "durably complete or explicitly skipped") and has("native_manager", "filterNot { it.gap }")),
]

# FFmpeg / Termux execution semantics.
checks += [
    ("embedded FFmpeg enforces wall-clock timeout", has("ffmpeg_compiler", "ffmpegWallClockTimeoutMs") and has("ffmpeg_compiler", "timeoutMs = ffmpegWallClockTimeoutMs")),
    ("FFprobe missing expected duration is failure", has("ffmpeg_verifier", "duration is missing from probe even though this media attempt has an expected duration")),
    ("embedded FFmpeg runs execution security policy", has("embedded_manager", "MediaExecutionSecurityPolicy.requireEmbeddedExecutable")),
    ("Termux fallback restricted to safe HTTPS public sessions", has("security_policy", "termuxNetworkEligible") and has("security_policy", "scheme in setOf(\"https\")") and has("runtime_routing", "MediaExecutionSecurityPolicy.termuxNetworkEligible")),
    ("selected inputs carry origin-scoped headers", has("media_library", "MediaExecutionSecurityPolicy.scopedHeadersFor")),
    ("Termux process control validates start ticks plus wrapper/process group, not stdin-fifo cmdline", has("termux_shell", "process_tree") and has("termux_shell", "WRAPPER_PID") and "cmdline" not in text["termux_shell"].split("private fun controlOwnedProcessScript", 1)[1].split("private fun probeAllToolsScript", 1)[0].lower()),
    ("Termux transient files are app-private", has("termux_adapter", "app-private://xdm-termux/transient/") and has("termux_adapter", "never staged in shared Downloads")),
]

# Gate/manifest/report coverage.
checks += [
    ("XAR12 Gradle task registered", has("gradle", "verifyXar12MediaExecutionSeal")),
    ("final shell gate runs XAR12 validator", has("final_gate", "tools/validate-xar12-media-execution-seal.py")),
    ("report documents all S12 canonical ids", all(cid in text["doc"] for cid in CANONICAL_IDS)),
]

manifest = json.loads(text["manifest"])
entry = manifest.get("xar12_media_execution_seal")
if not entry:
    raise SystemExit("PROJECT_MANIFEST missing xar12_media_execution_seal")
checks += [
    ("manifest marks XAR12 as overlay 12 of 17", entry.get("roadmap_position") == "12 of 17"),
    ("manifest closes exactly 23 S12 findings", entry.get("canonical_findings_closed") == 23 and entry.get("canonical_ids") == CANONICAL_IDS),
    ("manifest wires XAR12 validator/task", entry.get("validator") == "tools/validate-xar12-media-execution-seal.py" and entry.get("validation_task") == "verifyXar12MediaExecutionSeal"),
]

failed = [label for label, ok in checks if not ok]
if failed:
    print("XAR12 validation failed:", file=sys.stderr)
    for label in failed:
        print(f" - {label}", file=sys.stderr)
    raise SystemExit(1)

print(f"XAR12 media execution seal contract passed: {len(checks)} checks; {len(CANONICAL_IDS)}/23 S12 findings covered.")
