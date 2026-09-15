#!/usr/bin/env python3
"""Static release matrix audit for XDM Desktop REM18.

This guard complements runtime build/test/package commands. It verifies that the
repository has a declared Linux/Windows x64/arm64 release topology, signing
requirements, updater metadata binding, final-seal evidence paths, and the
XFE01 single-Firefox-extension convergence contract.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REQUIRED_RIDS = ["linux-x64", "linux-arm64", "win-x64", "win-arm64"]
REQUIRED_SCENARIOS = [
    "browser-native-host-stalled-client",
    "shutdown-during-browser-admission",
    "diagnostics-post-crash-redaction-bundle",
    "aria2-process-death-resume",
    "ffmpeg-output-pressure-timeout-cancel",
    "updater-swap-power-loss-rollback-window",
    "hls-dash-workspace-collision-subtitle-publication",
]


def find_repo_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".devtool.toml").exists() and (candidate / "app/XDM/XDM.Modern.sln").exists():
            return candidate
    raise SystemExit("Unable to locate XDM repository root.")


def read(repo: Path, relative: str) -> str:
    path = repo / relative
    if not path.exists():
        raise SystemExit(f"Required release-matrix file is missing: {relative}")
    return path.read_text(encoding="utf-8", errors="replace")


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit REM18 release matrix contracts.")
    parser.add_argument("--write-evidence", default="")
    args = parser.parse_args()

    repo = find_repo_root(Path.cwd())
    files = {
        "ci": read(repo, ".github/workflows/modern-ci.yml"),
        "release": read(repo, ".github/workflows/modern-release.yml"),
        "shell": read(repo, "app/XDM/eng/rem18-final-release-seal.sh"),
        "powershell": read(repo, "app/XDM/eng/rem18-final-release-seal.ps1"),
        "linuxPackage": read(repo, "app/XDM/eng/package-linux.sh"),
        "windowsPackage": read(repo, "app/XDM/eng/package-windows.ps1"),
        "publishOneShell": read(repo, "app/XDM/eng/publish-one.sh"),
        "publishOnePowerShell": read(repo, "app/XDM/eng/publish-one.ps1"),
        "canonicalFirefoxPackage": read(repo, "app/XDM/eng/package-canonical-firefox-extension.py"),
        "singleFirefoxValidator": read(repo, "app/XDM/eng/validate-xfe01-single-firefox-extension.py"),
        "linuxDesktopEntry": read(repo, "app/XDM/packaging/linux/xdm-modern.desktop"),
        "browserHandoffParser": read(repo, "app/XDM/src/XDM.BrowserIntegration/FirefoxExtensionHandoffParser.cs"),
        "metadata": read(repo, "app/XDM/eng/generate-release-metadata.py"),
        "faultMatrix": read(repo, "app/XDM/eng/rem18-fault-injection-matrix.json"),
    }

    issues: list[str] = []
    combined_release = files["release"] + "\n" + files["ci"] + "\n" + files["shell"] + "\n" + files["powershell"]
    for rid in REQUIRED_RIDS:
        if rid not in combined_release:
            issues.append(f"RID is absent from REM18 release contract: {rid}")

    required_terms = [
        "dotnet restore app/XDM/XDM.Modern.sln",
        "dotnet build app/XDM/XDM.Modern.sln",
        "dotnet test app/XDM/XDM.Modern.sln",
        "--validate-bootstrap",
        "WINDOWS_SIGNING_CERTIFICATE",
        "verify-windows-signatures.ps1",
        "releaseCommitSha",
        "releaseTag",
        "minimumSupportedVersion",
        "libgtk-3-0",
        "libnotify-bin",
        "XDM.NativeHost",
        "XDM.Updater",
        "package-canonical-firefox-extension.py",
        "validate-xfe01-single-firefox-extension.py",
        "XDM-Firefox.xpi",
        "xdm-android-media-bridge@mikeyphw",
        "action == \"add\" && version != 1",
        "action == \"capture\" && version != 3",
        "x-scheme-handler/xdmdownload",
    ]
    combined_all = "\n".join(files.values())
    for term in required_terms:
        if term not in combined_all:
            issues.append(f"release contract term is missing: {term}")

    try:
        matrix = json.loads(files["faultMatrix"])
    except json.JSONDecodeError as exc:
        issues.append(f"fault-injection matrix is invalid JSON: {exc}")
        matrix = {}
    scenario_ids = {str(item.get("id")) for item in matrix.get("scenarios", [])} if isinstance(matrix, dict) else set()
    for scenario_id in REQUIRED_SCENARIOS:
        if scenario_id not in scenario_ids:
            issues.append(f"fault-injection scenario is missing: {scenario_id}")

    evidence = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "requiredRids": REQUIRED_RIDS,
        "requiredScenarios": REQUIRED_SCENARIOS,
        "issues": issues,
        "passed": not issues,
    }
    if args.write_evidence:
        output = (repo / args.write_evidence).resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if issues:
        for issue in issues:
            print(f"REM18 release matrix audit failed: {issue}", file=sys.stderr)
        return 1
    print("REM18 release matrix audit passed for Linux/Windows x64/arm64, updater, signing, fault matrix, and single Firefox extension contracts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
