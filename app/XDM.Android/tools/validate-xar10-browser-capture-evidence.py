#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
OWNED = [
    "S10-01", "S10-02", "S10-03", "S10-04", "S10-05", "S10-07", "S10-08", "S10-09", "S10-10", "S10-11", "S10-12", "S10-13",
    "DS5-S10-01", "DS5-S10-02", "DS5-S10-03", "DS5-S10-04", "DS5-S10-05", "RERUN45-S10-01",
]
checks: list[str] = []

def fail(message: str) -> None:
    raise AssertionError(message)

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.exists():
        fail(f"missing {rel}")
    return path.read_text(encoding="utf-8")

def require(rel: str, *needles: str) -> str:
    text = read(rel)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        fail(f"{rel} missing {missing}")
    checks.extend(f"{rel}:{needle}" for needle in needles)
    return text

def coverage() -> None:
    report = read("docs/remediation/XAR10-BROWSER-CAPTURE-EVIDENCE.md")
    missing = [cid for cid in OWNED if cid not in report]
    if missing:
        fail(f"report missing canonical IDs: {missing}")
    if "18/18" not in report:
        fail("report must explicitly claim 18/18 S10 closure")
    checks.append("18/18 S10 coverage")

def source_contracts() -> None:
    require(
        "browser-extension/src/main/extension/xdm-firefox/fab.js",
        "closed Shadow DOM keeps privileged XDM capture URLs",
        "attachShadow({ mode: \"closed\" })",
    )
    require(
        "browser-extension/src/main/extension/xdm-firefox/candidate-store.js",
        "function evidenceKey",
        "exact URL + requestId + frame + generation",
        "clearDocumentEvidence",
        "exactRequestUrl = sameRequest",
        "mergedHeaders = sameRequest",
    )
    require(
        "browser-extension/src/main/extension/xdm-firefox/handoff.js",
        "function senderProof",
        "pageObservationNonce",
        "pageObservationCreatedAt",
        "pageObservationExpiresAt",
        "rawHeaders: finalHeaders",
        "requestId:",
        "requestGeneration:",
        "oversized capture sessions must fail closed",
    )
    require(
        "browser-extension/src/main/extension/xdm-firefox/network-observer.js",
        "function tabUrlAllowedForCapture",
        "function finalHeadersForExecution",
        "final sent-header evidence is unavailable or still pending",
        "browser.webNavigation.onCommitted",
        "candidateStore.clearDocumentEvidence",
        "This site is disabled by XDM site-mode settings.",
        "auto-offer suppression is committed only after handoff construction",
        "refusing to drop selected variants/tracks",
    )
    require(
        "browser-extension/src/main/extension/xdm-firefox/popup.js",
        "function hostAllowedBySiteMode",
        "This site is disabled by XDM site-mode settings.",
    )
    require(
        "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt",
        "sender-bound and direct but never keyless runtime authority",
        "proposed pre-send headers are preserved as audit context",
        "?: final",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt",
        "sender-bound direct v3",
        "sender proof nonce is missing",
        "sender timestamp is missing",
        "sender expiry is missing",
        "Direct browser capture request fingerprint is missing",
        "request evidence identity is missing",
        "createdAtEpochMs = createdAt",
        "expiresAtEpochMs = expiresAt",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
        "forgeable. Never trust",
        "privateNetworkApproved = false",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
        "do not trust forgeable internal direct-capture approval extras",
        "headersForBrowserVariant(candidate.url, source.url",
        "HLS/DASH child inputs on a different origin must not inherit",
        "emptySet<DownloadRequestApprovalScope>()",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/WebViewUserscriptStore.kt",
        "runtimeMatcherJson",
        "wrapForInjection(source: String, metadata: UserscriptMetadata)",
        "matchPatternToRegex",
        "<all_urls>",
    )
    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt",
        "MAX_BRIDGE_JSON_BYTES",
        "payload-too-large",
        "currentDocumentGeneration",
        "beginNewDocument",
        "withoutSensitiveWebViewHeaders",
        "headersForMediaLocatorVariant",
        "userscriptHandlers += WebViewCompat.addDocumentStartJavaScript",
    )
    require(
        "app/src/test/kotlin/com/mikeyphw/xdm/android/Xar10BrowserCaptureEvidenceContractTest.kt",
        "browserEvidenceIsRequestFrameAndGenerationScoped",
        "directCaptureRequiresSenderBoundProof",
        "webViewBridgeAndUserscriptsAreScoped",
    )
    require("app/build.gradle.kts", "verifyXar10BrowserCaptureEvidence", "validate-xar10-browser-capture-evidence.py")
    require("tools/run-final-release-gate.sh", "tools/validate-xar10-browser-capture-evidence.py")

def manifest_contract() -> None:
    manifest = json.loads(read("PROJECT_MANIFEST.json"))
    entry = manifest.get("xar10_browser_capture_evidence") or fail("PROJECT_MANIFEST missing xar10_browser_capture_evidence")
    if entry.get("canonical_findings_closed") != len(OWNED):
        fail("manifest has wrong canonical_findings_closed")
    missing = [cid for cid in OWNED if cid not in entry.get("canonical_ids", [])]
    if missing:
        fail(f"manifest missing canonical ids: {missing}")
    for key in [
        "closed_shadow_privileged_handoff", "request_scoped_evidence", "navigation_clears_evidence",
        "sender_bound_direct_v3", "no_synthetic_private_network_approval", "site_mode_gates_background_and_popup",
        "webview_userscript_handle_removal", "webview_bridge_input_bounded", "final_headers_required_for_privileged_execution",
    ]:
        if entry.get(key) is not True:
            fail(f"manifest missing true {key}")
    checks.append("manifest registered")

def main() -> int:
    coverage()
    source_contracts()
    manifest_contract()
    print(f"XAR10 browser capture evidence contract passed: {len(checks)} checks; 18/18 S10 findings covered.")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"XAR10 validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
