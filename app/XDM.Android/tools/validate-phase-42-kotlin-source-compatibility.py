#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android"


def read(relative: str) -> str:
    path = APP / relative
    if not path.is_file():
        raise SystemExit(f"Kotlin source compatibility missing required file: {relative}")
    return path.read_text(encoding="utf-8")

media_player = read("Media3PlayerScreen.kt")
primitives = read("XdmPrimitives.kt")
activity = read("ui/activity/ActivityScreen.kt")
details = read("ui/downloads/DownloadDetails.kt")
row = read("ui/downloads/DownloadRow.kt")
downloads = read("ui/downloads/DownloadsScreen.kt")
organize = read("ui/downloads/OrganizeDownloadsSheet.kt")
add = read("ui/intake/AddDownloadSurface.kt")

if "positionMs = playbackPositionMs" not in media_player:
    raise SystemExit("Media player diagnostics must use the current positionMs parameter name")
if "playbackPositionMs = playbackPositionMs" in media_player:
    raise SystemExit("Stale MediaPlayerDiagnosticsPlanner playbackPositionMs argument remains")

for needle in (
    "import androidx.compose.material3.ExperimentalMaterial3Api",
    "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun XdmAdaptiveSheet",
):
    if needle not in primitives:
        raise SystemExit(f"Adaptive sheet Material3 opt-in missing: {needle}")

if "event.actionLabel?.let { actionLabel ->" not in activity:
    raise SystemExit("Activity action label must be captured locally before rendering")
if "Text(event.actionLabel)" in activity:
    raise SystemExit("Activity screen still relies on an unstable public-property smart cast")

for relative, source in {
    "DownloadDetails.kt": details,
    "DownloadRow.kt": row,
    "DownloadsScreen.kt": downloads,
    "OrganizeDownloadsSheet.kt": organize,
    "AddDownloadSurface.kt": add,
}.items():
    if "import androidx.compose.foundation.layout.weight" in source:
        raise SystemExit(f"{relative} imports Compose's internal weight parent-data property")

for relative, source in {
    "DownloadsScreen.kt": downloads,
    "AddDownloadSurface.kt": add,
}.items():
    if "com.mikeyphw.xdm.android.ui.common" in source:
        raise SystemExit(f"{relative} imports a package that does not match UiAudience.kt")

for relative, source in {
    "DownloadDetails.kt": details,
    "DownloadRow.kt": row,
}.items():
    if "val total = totalBytes" not in source:
        raise SystemExit(f"{relative} must capture totalBytes locally for K2 smart-cast safety")
    if "totalBytes != null ->" in source:
        raise SystemExit(f"{relative} still smart-casts a cross-module public property")

print("Phase 42 Kotlin source compatibility validation passed.")
