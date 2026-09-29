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
        "foldHingeWidth: Dp",
        "foldIsVertical: Boolean",
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
        "data class XdmFoldLayoutInfo",
        "feature.bounds",
        "feature.isSeparating",
        "feature.orientation == FoldingFeature.Orientation.VERTICAL",
        "bounds.left.toDp()",
        "bounds.right.toDp()",
        "bounds.top.toDp()",
        "bounds.bottom.toDp()",
        "FoldingFeature.State.HALF_OPENED",
    ]),
    ("gradle/libs.versions.toml", ["androidx-window", "androidx.window:window"]),
    ("app/build.gradle.kts", ["implementation(libs.androidx.window)"]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt", [
        "class XdmFocusRestorationController",
        "XdmFocusRestoreToken",
        "FocusRequester",
        "restoreLastFocus()",
        "xdmFocusRestorePoint",
        "captureFocusedRestoreTarget",
        "baselineFocusAcquisition",
        "ViewTreeObserver.OnWindowFocusChangeListener",
        "onHostWindowFocusChanged",
        "postOnAnimation",
        "MaximumRestoreAttempts",
        "RequiredStableTraversalChecks",
        "requestFocus() returning true is only admission",
        "requesters[key] !== requester",
        "capturedFocusAcquisition > focusAcquisitionGeneration",
        "XdmTraversalOrder",
        "keyboardDpadSwitchAccessOrder",
        "object XdmContrastPolicy",
        "MinimumNormalTextContrast",
        "requiredSurfaceNames",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", [
        "rememberXdmFoldLayoutInfo()",
        "rememberXdmFocusRestorationController()",
        "LocalXdmFocusRestorationController provides focusRestorationController",
        "foldHingeWidth = foldLayoutInfo.hingeWidth",
        "foldHingeHeight = foldLayoutInfo.hingeHeight",
        "foldHingeLeft = foldLayoutInfo.hingeLeft",
        "foldHingeRight = foldLayoutInfo.hingeRight",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt", [
        "sheetFocusRequester.requestFocus()",
        "dismissAndRestoreFocus()",
        "focusRequester(sheetFocusRequester)",
        "captureFocusedRestoreTarget()",
        "DisposableEffect(focusRestorationController, restoreTarget)",
        "restoreTarget?.let(focusRestorationController::requestRestore)",
        "XdmContrastPolicy.ensureReadableContentColor",
        "LiveRegionMode.Polite",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt", [
        "Modifier.fillMaxSize().imePadding()",
        "externalDraftId",
        "advancedExpanded",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt", [
        'xdmFocusRestorePoint(\"new_download_action\")',
        'markLastFocused(\"new_download_action\")',
        "xdmTraversalOrder(XdmTraversalOrder.Navigation)",
        "xdmTraversalOrder(XdmTraversalOrder.Content)",
    ]),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt", [
        "BoxWithConstraints(",
        "measuredWindowProfile = windowProfile.withAvailablePaneWidth(maxWidth)",
        "positionInWindow()",
        "verticalHingeSplitFor",
        "hingeSplit.leftPaneWidth",
        "hingeSplit.rightPaneWidth",
        "measuredTwoPaneDownloads",
        "twoPaneLayoutActive = measuredTwoPaneDownloads",
        "visible = !twoPaneLayoutActive && detailDownload != null",
        "measuredWindowProfile.minimumPaneGap",
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
        "requestFocus()",
        "ExperimentalTestApi",
        "@OptIn(ExperimentalTestApi::class)",
        "waitUntilDoesNotExist",
        "performClick",
    ]),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityGapClosurePolicyTest.kt", [
        "matrixNamesCoverEveryRoadmapConfiguration",
        "measuredPaneWidthControlsTwoPaneEligibility",
        "verticalHingeSplitFor",
        "preferredFoldSafePane",
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

test_rel = "app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Phase9AccessibilityAdaptiveLayoutInstrumentedTest.kt"
test_data = text(test_rel)
if "useUnmergedTree = false" in test_data:
    missing.append(f"{test_rel}: unsupported waitUntilDoesNotExist overload must not be used")


accessibility_rel = "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt"
accessibility_data = text(accessibility_rel)
for forbidden in ("repeat(90)", "stableFocusedFrames"):
    if forbidden in accessibility_data:
        missing.append(f"{accessibility_rel}: obsolete frame-polling focus heuristic remains: {forbidden!r}")

primitives_rel = "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt"
primitives_data = text(primitives_rel)
if "focusRestorationController.requestRestoreLastFocus()" in primitives_data:
    missing.append(f"{primitives_rel}: adaptive sheet must restore its captured opener token, not global last focus")

if missing:
    raise SystemExit("Phase 9 accessibility/adaptive r2 validation failed:\n" + "\n".join(missing))

print("Phase 9 accessibility/adaptive r2 contracts verified")
