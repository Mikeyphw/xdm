#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise SystemExit(f"missing required file: {rel}")
    return path.read_text(encoding="utf-8")

checks = [
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmWindowClass.kt", [
        "data class XdmWindowProfile",
        "foldPosture: XdmFoldPosture",
        "allowsTwoPaneDownloadsFor(availablePaneWidth: Dp)",
        "requiredDownloadsPaneWidth",
        "withAvailablePaneWidth",
        "XdmAdaptiveTestMatrix",
        "split-screen",
        "840dp-threshold",
        "large-font-200-percent",
        "separating-hinge",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmFoldPostureSource.kt", [
        "WindowInfoTracker.getOrCreate",
        "windowLayoutInfo(owner)",
        "filterIsInstance<FoldingFeature>()",
        "isSeparating -> XdmFoldPosture.SeparatingHinge",
        "FoldingFeature.State.HALF_OPENED",
    ]),
    ("gradle/libs.versions.toml", ["androidx-window", "androidx.window:window"]),
    ("app/build.gradle.kts", ["implementation(libs.androidx.window)"]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt", [
        "class XdmFocusRestorationController",
        "FocusRequester",
        "restoreLastFocus()",
        "xdmFocusRestorePoint",
        "XdmTraversalOrder",
        "keyboardDpadSwitchAccessOrder",
        "object XdmContrastPolicy",
        "MinimumNormalTextContrast",
        "requiredSurfaceNames",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", [
        "rememberXdmFoldPosture()",
        "rememberXdmFocusRestorationController()",
        "LocalXdmFocusRestorationController provides focusRestorationController",
        "foldPosture = foldPosture",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt", [
        "sheetFocusRequester.requestFocus()",
        "dismissAndRestoreFocus()",
        "focusRequester(sheetFocusRequester)",
        "XdmContrastPolicy.ensureReadableContentColor",
        "LiveRegionMode.Polite",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt", [
        'xdmFocusRestorePoint(\"new_download_action\")',
        'markLastFocused(\"new_download_action\")',
        "xdmTraversalOrder(XdmTraversalOrder.Navigation)",
        "xdmTraversalOrder(XdmTraversalOrder.Content)",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt", [
        "BoxWithConstraints(Modifier.fillMaxWidth().weight(1f))",
        "measuredWindowProfile = windowProfile.withAvailablePaneWidth(maxWidth)",
        "measuredTwoPaneDownloads",
        "selectedIds = selectedIds.intersect(visibleIds)",
        "xdmTraversalOrder(XdmTraversalOrder.List)",
        'xdmPane(\"Download details pane\", traversal = XdmTraversalOrder.Detail)',
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityRegressionContracts.kt", [
        "riskyLargeFontSurfaces",
        "composeScreenshotSemanticsMatrix",
        "highContrastSurfaces",
        "download-actions-menu",
        "post-processing-job-row",
        "media-variant-row",
    ]),
    ("app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityAdaptiveLayoutInstrumentedTest.kt", [
        "captureToImage()",
        "composeScreenshotAndSemanticsMatrixCoversPhoneSplitThresholdTabletLandscapeLargeFontAndHinge",
        "adaptiveSheetRequestsFocusAndRestoresFocusToOpener",
        "largeFontRiskySurfacesRemainAddressableBySemantics",
        "highContrastPolicyCoversStatusWarningProgressDisabledAndSelectedStates",
        "assertIsFocused",
    ]),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityGapClosurePolicyTest.kt", [
        "matrixNamesCoverEveryRoadmapConfiguration",
        "measuredPaneWidthControlsTwoPaneEligibility",
        "traversalOrderIsExplicitForKeyboardDpadAndSwitchAccess",
        "contrastGateCoversRoadmapSurfaces",
    ]),
]

missing = []
for rel, needles in checks:
    data = text(rel)
    for needle in needles:
        if needle not in data:
            missing.append(f"{rel}: missing {needle!r}")

if missing:
    raise SystemExit("Phase 9 accessibility/adaptive r2 validation failed:\n" + "\n".join(missing))

print("Phase 9 accessibility/adaptive r2 contracts verified")
