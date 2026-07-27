#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]
ERRORS: list[str] = []
RETIRED = "be" + "ta"

FORBIDDEN = (
    f"create(\"{RETIRED}\")",
    f"applicationIdSuffix = \".{RETIRED}\"",
    f"versionNameSuffix = \"-{RETIRED}\"",
    f"xdmdownload-{RETIRED}",
    "Be" + "ta" + "Scheme",
    "Channel." + "Be" + "ta",
    "Be" + "ta" + f"(\"{RETIRED}\")",
    "assemble" + "Be" + "ta",
    "lint" + "Be" + "ta",
    "pre" + "Be" + "ta" + "Build",
    "readyFor" + "Be" + "ta",
    RETIRED + "Ready",
)

ACTIVE_ROOTS = [
    ROOT / "app",
    ROOT / "browser-extension",
    ROOT / "browser-integration",
    ROOT / "core-model",
    ROOT / "media",
    ROOT / "transfer-aria2",
    ROOT / "tools",
    ROOT / "docs",
    ROOT / "README.md",
    ROOT / "PROJECT_MANIFEST.json",
    ROOT / ".github",
    REPO_ROOT / ".github",
    REPO_ROOT / ".devtool.toml",
    REPO_ROOT / "build-release-apk.sh",
    REPO_ROOT / "CHANGELOG.md",
    REPO_ROOT / "DEVTOOL_OVERLAY.md",
    REPO_ROOT / "docs" / "RELEASE-CHECKLIST.md",
    REPO_ROOT / "app" / "XDM" / "src",
    REPO_ROOT / "app" / "XDM" / "eng",
]

TEXT_SUFFIXES = {
    ".cs", ".kt", ".kts", ".py", ".sh", ".md", ".json", ".xml", ".yml", ".yaml", ".toml", ".txt",
}


def iter_files(path: Path):
    if path.is_file():
        yield path
        return
    if not path.exists():
        return
    for child in path.rglob("*"):
        if not child.is_file():
            continue
        rel = child.relative_to(REPO_ROOT)
        if any(part in {".git", ".devtool", "build", ".gradle", "bin", "obj"} for part in rel.parts):
            continue
        if child.suffix not in TEXT_SUFFIXES:
            continue
        yield child


seen: set[Path] = set()
for root in ACTIVE_ROOTS:
    for file in iter_files(root):
        file = file.resolve()
        if file in seen:
            continue
        seen.add(file)
        try:
            text = file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        rel = file.relative_to(REPO_ROOT)
        for token in FORBIDDEN:
            if token in text:
                ERRORS.append(f"{rel}: forbidden retired-channel token {token!r}")
        if re.search(r"\b" + re.escape(RETIRED) + r"\b", text, flags=re.IGNORECASE):
            ERRORS.append(f"{rel}: retired pre-release channel wording remains")

build_text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
if 'applicationIdSuffix = ".debug"' not in build_text:
    ERRORS.append("debug channel suffix must remain")
if 'manifestPlaceholders["xdmBrowserScheme"] = "xdmdownload-debug"' not in build_text:
    ERRORS.append("debug browser scheme must remain")

contract_text = (ROOT / "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt").read_text(encoding="utf-8")
if 'setOf(ReleaseScheme, DebugScheme)' not in contract_text:
    ERRORS.append("browser deep-link contract must list only release and debug schemes")

if ERRORS:
    print("Retired-channel validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    raise SystemExit(1)

print("Retired pre-release channel validation passed.")
