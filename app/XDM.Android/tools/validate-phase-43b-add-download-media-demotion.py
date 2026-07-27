#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def read(path: str) -> str:
    file = ROOT / path
    if not file.exists():
        errors.append(f"missing {path}")
        return ""
    return file.read_text(encoding="utf-8")

def require(path: str, needle: str, label: str | None = None) -> None:
    text = read(path)
    if needle not in text:
        errors.append(f"{path} missing {label or needle}")

def reject(path: str, needle: str, label: str | None = None) -> None:
    text = read(path)
    if needle in text:
        errors.append(f"{path} still contains forbidden {label or needle}")

review = "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperience.kt"
intake = "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadIntake.kt"
add = "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt"
shell = "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt"
vm = "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt"
contract = "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserExtensionPhase43BContractTest.kt"
core_test = "core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DownloaderExperienceTest.kt"
doc = "docs/architecture/PHASE-43B-ADD-DOWNLOAD-MEDIA-DEMOTION.md"
manifest = "PROJECT_MANIFEST.json"

for marker in [
    "enum class MediaInspectionRecommendation",
    "object MediaInspectionPolicy",
    "DownloadIntakeKind.AdaptiveMedia -> MediaInspectionRecommendation.Recommended",
    "DownloadIntakeOrigin.ManualEntry -> MediaInspectionRecommendation.Hidden",
    "DownloadIntakeOrigin.BrowserExtension",
    "Analyze page for media",
    "Use page analysis only when this link is a watch page or playlist, not a normal file.",
]:
    require(review, marker)

require(intake, "BrowserExtension,", "dedicated browser-extension origin")
require(vm, "AutomationCommandSource.BrowserExtension -> DownloadIntakeOrigin.BrowserExtension", "browser extension intake mapping")
require(add, "externalOrigin: DownloadIntakeOrigin? = null")
require(add, "origin = if (externalDraftId != null && url == initialUrl) externalOrigin")
require(add, "Text(review.mediaInspectionActionLabel)")
require(add, "XdmMetadataText(review.mediaInspectionGuidance)")
reject(add, "Media inspection opens the resolver", "old generic helper text")
require(shell, "externalOrigin = state.externalAddDraft?.origin")
require(contract, "addDownloadDemotesMediaAnalysisForOrdinaryUnknownManualLinks")
require(contract, "browserExtensionAndAdaptiveMediaStillSurfaceStrongInspection")
require(core_test, "mediaInspectionRecommendationDemotesOrdinaryAddDownloadLinks")
require(doc, "Phase 43B")
require(manifest, '"browser_bridge_phase43b_add_download_media_recommendation_demotion"')
require(manifest, '"room_schema_unchanged": 14')
require(manifest, '"next_overlay": "browser_bridge_phase44_download_list_action_model_and_menus"')

if errors:
    print("Phase 43B Add Download media-demotion validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Phase 43B Add Download media-demotion validation passed.")
