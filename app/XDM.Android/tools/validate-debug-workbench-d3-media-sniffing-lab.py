#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

CHECKS = 0


def ok(condition: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if not condition:
        raise SystemExit(f"[D3 validator] {message}")


def read(path: Path) -> str:
    ok(path.is_file(), f"missing file: {path}")
    return path.read_text(encoding="utf-8")


def find_android_root() -> Path:
    script = Path(__file__).resolve()
    candidates = [
        script.parents[1] if len(script.parents) > 1 else script.parent,
        Path.cwd(),
        Path.cwd() / "app" / "XDM.Android",
    ]
    for candidate in candidates:
        if (candidate / "tools/validate-debug-workbench-d3-media-sniffing-lab.py").is_file():
            return candidate
    cursor = Path.cwd().resolve()
    for _ in range(8):
        if (cursor / "tools/validate-debug-workbench-d3-media-sniffing-lab.py").is_file():
            return cursor
        nested = cursor / "app" / "XDM.Android"
        if (nested / "tools/validate-debug-workbench-d3-media-sniffing-lab.py").is_file():
            return nested
        if cursor.parent == cursor:
            break
        cursor = cursor.parent
    raise SystemExit("[D3 validator] unable to locate XDM Android root")


def main() -> None:
    root = find_android_root()
    manifest = json.loads(read(root / "PROJECT_MANIFEST.json"))
    d3 = manifest.get("debug_workbench_phase_d3_media_sniffing_lab")
    ok(isinstance(d3, dict), "manifest missing debug_workbench_phase_d3_media_sniffing_lab")
    ok(d3.get("status") == "implemented", "D3 manifest status must be implemented")
    ok(manifest.get("next_phase") == "debug_workbench_phase_d4_browser_bridge_add_download_debugger", "manifest next_phase must hand off to D4")
    ok(d3.get("static_sniff_only") is True, "D3 manifest must record static_sniff_only=true")
    ok(d3.get("automatic_upload") is False, "D3 manifest must forbid automatic upload")
    ok(d3.get("top_level_route_added") is False, "D3 manifest must not add a top-level route")
    ok(d3.get("uses_shared_media_sniffing_engine") is True, "D3 manifest must record shared sniffer usage")

    shell = read(root / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugWorkbenchSettingsScreen.kt")
    lab_ui = read(root / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/MediaSniffingLabCard.kt")
    lab_model = read(root / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingLab.kt")
    media_test = read(root / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingLabTest.kt")
    app_test = read(root / "app/src/test/kotlin/com/mikeyphw/xdm/android/DebugWorkbenchD3MediaSniffingLabContractTest.kt")
    doc = read(root / "docs/architecture/DEBUG-WORKBENCH-D3-MEDIA-SNIFFING-LAB.md")

    for needle in ["Media Sniffing Lab", "MediaSniffingLabCard()"]:
        ok(needle in shell, f"D2 shell missing {needle}")
    for needle in [
        "Run static sniff",
        "Copy sanitized lab report",
        "${row.reason}\\n${row.redactedUrl}",
        "MediaSniffingLab.inspect",
        "MediaSniffingLabRequest",
        "rememberSaveable",
        "Manual page",
        "Batch input",
        "Shared text",
        "Browser extension",
        "labDisplayLabel",
        "labStateKey",
        "manual-page",
        "shared-text",
        "no network page probe",
        "no arbitrary JavaScript execution",
        "no DRM bypass",
    ]:
        ok(needle in lab_ui, f"lab UI missing {needle}")
    for forbidden in [
        'Text(if (source == option) "✓ ${option.name}" else option.name)',
        "Text(option.name)",
        "option.name",
        "XdmSupportingText(option.name",
        "XdmMetadataText(option.name",
    ]:
        ok(forbidden not in lab_ui, f"lab UI must not render raw enum names: {forbidden}")
    for needle in [
        "object MediaSniffingLab",
        "MediaSniffingEngine",
        "MediaSniffingInput",
        "PrivacyDiagnosticsRedactor",
        "copyText",
        "No network page probe",
        "no arbitrary JavaScript execution",
        "no DRM bypass",
    ]:
        ok(needle in lab_model, f"lab model missing {needle}")
    for forbidden in ["repository.save", "queueIntelligenceCoordinator", "MediaPageProbe(", "HttpURLConnection", "openConnection", "upload"]:
        ok(forbidden not in lab_model, f"lab model must not contain {forbidden}")
    for needle in [
        "labRunsSharedStaticSnifferAndRedactsCopyReport",
        "labDoesNotNeedNetworkProbeForDirectManifest",
        "blankLabInputReturnsIdleReport",
        "secret-token",
        "token=<redacted>",
    ]:
        ok(needle in media_test, f"media test missing {needle}")
    for needle in [
        "debugWorkbenchShellHostsMediaSniffingLabWithoutTopLevelRoute",
        "labUsesSharedSniffingEngineWithSafeStaticBoundaries",
        "d3ManifestDocsValidatorAndMediaTestsAreRecorded",
        "D3 UI must not render raw enum names",
        "lab.contains(\"option.name\")",
    ]:
        ok(needle in app_test, f"app contract test missing {needle}")
    for needle in [
        "Settings → Debug Workbench → Media Sniffing Lab",
        "static sniff only",
        "no network page probe",
        "no arbitrary JavaScript execution",
        "no DRM bypass",
        "PrivacyDiagnosticsRedactor",
    ]:
        ok(needle in doc, f"D3 doc missing {needle}")

    print(f"Debug Workbench D3 Media Sniffing Lab validator passed ({CHECKS} checks).")


if __name__ == "__main__":
    main()
