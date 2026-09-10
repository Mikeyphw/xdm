#!/usr/bin/env python3
from pathlib import Path
import json, sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]

def read(rel):
    p=ROOT/rel
    if not p.is_file():
        errors.append(f"missing {rel}")
        return ""
    return p.read_text()

def require(rel,*needles):
    text=read(rel)
    for needle in needles:
        if needle not in text: errors.append(f"{rel} missing {needle!r}")
    return text

def forbid(rel,*needles):
    text=read(rel)
    for needle in needles:
        if needle in text: errors.append(f"{rel} retains forbidden {needle!r}")
    return text

shell=require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt",
              "if (route == AppRoute.Downloads)", 'contentDescription = "New download"')
require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt", "fun XdmPageIntro(", ".alpha(if (enabled) 1f else 0.64f)")
media=require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt", "XdmPageIntro(intro)", "XdmWindowClass.Expanded")
library=require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt", "XdmPageIntro(intro)", "XdmWindowClass.Expanded")
forbid("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt", 'XdmSectionHeader("Activity")')
settings=require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt",
                 "Icons.AutoMirrored.Rounded.ArrowBack", 'contentDescription = "Back to Settings"')
forbidden=forbid("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt",
                 'XdmSectionHeader("Settings")', 'TextButton(onClick = onBack) { Text("Back") }')
require("app/src/test/kotlin/com/mikeyphw/xdm/android/Ux02NavigationVisualHierarchyContractTest.kt",
        "compactShellOwnsTheRouteTitleAndOnlyDownloadsGetsTheGlobalAddAction",
        "secondarySettingsPagesUseAConventionalBackAffordanceAndDisabledRowsStayReadable")
manifest=json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase=manifest.get("ux02_ux03_navigation_downloads_product_ui",{})
for k in ("compact_title_single_owner","contextual_compact_add_action","settings_back_arrow","disabled_row_readability"):
    if phase.get(k) is not True: errors.append(f"manifest ux02_ux03 missing {k}=true")
if errors:
    print("UX02 navigation/visual hierarchy validation failed:")
    for e in errors: print("-",e)
    sys.exit(1)
print("UX02 navigation/visual hierarchy validation passed")
