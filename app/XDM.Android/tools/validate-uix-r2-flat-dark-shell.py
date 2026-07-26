#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle not in text:
            ERRORS.append(f"{relative} missing {needle!r}")
    return text


window = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmWindowClass.kt",
    "enum class XdmWindowClass",
    "widthDp < 600f",
    "widthDp < 840f",
    "usesBottomNavigation",
    "usesNavigationSidebar",
)

theme = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmTheme.kt",
    "darkColorScheme(",
    "Color(0xFF090B0F)",
    "surfaceTint = Color.Transparent",
    "successContainer",
    "warningContainer",
    "groupedSurface",
)

activity = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
    "XdmTheme",
    "SystemBarStyle.dark(Color.TRANSPARENT)",
)
if "isSystemInDarkTheme" in activity or "lightColorScheme" in activity:
    ERRORS.append("MainActivity must use the dark-first XdmTheme without following the system light theme")

app = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
    "private val routeTopology = AppRoute.entries",
    "filterNot { it == AppRoute.Add }",
    "rememberSaveable",
    "XdmAdaptiveShell(",
    "XdmAdaptiveSheet(",
    "visible = state.route == AppRoute.Add",
    "onDismissRequest = { viewModel.navigate(previousPrimaryRoute) }",
    "stateDescription",
)
for forbidden in ("FloatingActionButton", "CenterAlignedTopAppBar"):
    if forbidden in app:
        ERRORS.append(f"XdmApp.kt must not use {forbidden}")

shell = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt",
    "NavigationBar(",
    "XdmNavigationSidebar(",
    "Modifier.width(224.dp)",
    "WindowInsets.safeDrawing",
    "imePadding()",
    "widthIn(max = 1480.dp)",
    "tonalElevation = 0.dp",
    "shadowElevation = 0.dp",
    "New download",
)
if "AppRoute.Add" in shell:
    ERRORS.append("The visible adaptive shell must not render AppRoute.Add as a destination")

primitives = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt",
    "fun XdmPageHeader",
    "fun XdmMetricStrip",
    "fun XdmNoticeRow",
    "fun XdmGroupedList",
    "fun XdmListRow",
    "fun <T> XdmSegmentedControl",
    "fun XdmFileTypeIcon",
    "fun XdmProgressLine",
    "fun XdmSectionLabel",
    "fun XdmTechnicalDetails",
    "fun XdmAdaptiveSheet",
    "fun XdmEmptyState",
    "testTag(XdmTestTags.PageHeader)",
    "sizeIn(minHeight = 48.dp)",
)

require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/XdmWindowClassTest.kt",
    "599.99f",
    "600f",
    "839.99f",
    "840f",
)
require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/UixR2AdaptiveShellContractTest.kt",
    "visibleNavigationHasExactlyFivePrimaryDestinations",
    "addIsAnInternalAdaptiveModalInsteadOfAPermanentDestination",
    "sharedPrimitivesAndFlatPrimarySurfacesArePresent",
)

primary_sources = [
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
]
for relative in primary_sources:
    text = read(relative)
    if "import androidx.compose.material3.Card" in text:
        ERRORS.append(f"{relative} still imports Material Card")
    if re.search(r"(?<![A-Za-z0-9_])Card\(", text):
        ERRORS.append(f"{relative} still renders a default elevated Material Card")
    if not any(marker in text for marker in ("XdmFlatCard(", "XdmListCard(", "XdmGroupedList(", "Surface(", "XdmMetricStrip(", "XdmAdaptiveSheet(")):
        ERRORS.append(f"{relative} does not use a flat XDM grouped surface")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
route_labels = re.findall(r'^\s*(Downloads|Add|Media|Library|Activity|Settings)\("([^"]+)"', routes, re.MULTILINE)
if [label for _, label in route_labels] != ["Downloads", "Add", "Media", "Library", "Activity", "Settings"]:
    ERRORS.append("AppRoute topology changed; the internal six-route compatibility contract must remain intact")

manifest_path = ROOT / "PROJECT_MANIFEST.json"
try:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception as exc:  # pragma: no cover - validator failure path
    ERRORS.append(f"PROJECT_MANIFEST.json is invalid: {exc}")
else:
    r2 = manifest.get("uix_r2_flat_dark_adaptive_shell", {})
    expected = {
        "dark_theme_default": True,
        "flat_zero_elevation_primary_surfaces": True,
        "compact_breakpoint_dp": 600,
        "expanded_breakpoint_dp": 840,
        "expanded_sidebar_width_dp": 224,
        "visible_primary_destinations": ["Downloads", "Media", "Library", "Activity", "Settings"],
        "add_preserved_as_internal_route": True,
        "room_schema_unchanged": 14,
        "version_name_unchanged": "0.20.0-rc08",
        "version_code_unchanged": 21,
    }
    for key, value in expected.items():
        if r2.get(key) != value:
            ERRORS.append(f"PROJECT_MANIFEST uix_r2_flat_dark_adaptive_shell.{key} must equal {value!r}")

require(
    "docs/architecture/UIX-R2-FLAT-DARK-ADAPTIVE-SHELL.md",
    "Dark-first visual system",
    "Compact: below 600 dp",
    "Medium: 600–839 dp",
    "Expanded: 840 dp and above",
    "five visible destinations",
    "AppRoute.Add",
    "No Room schema bump",
)

if ERRORS:
    print("UIX R2 flat dark adaptive shell validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)

print("UIX R2 flat dark adaptive shell validation passed")
