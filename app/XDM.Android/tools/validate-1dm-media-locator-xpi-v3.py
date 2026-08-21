#!/usr/bin/env python3
"""Current source-of-truth contracts for 1DM-style media locator parity and keyless XPI v3."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent.parent
errors = []

def read(rel):
    p = ROOT / rel
    if not p.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return p.read_text(encoding="utf-8", errors="replace")

def need(text, token, label):
    if token not in text: errors.append(f"{label}: missing {token}")

def forbid(text, token, label):
    if token in text: errors.append(f"{label}: forbidden {token}")

handoff = read("browser-extension/src/main/extension/xdm-firefox/handoff.js")
config = read("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
detector = read("browser-extension/src/main/extension/xdm-firefox/detector-core.js")
observer = read("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
page_sniffer = read("browser-extension/src/main/extension/xdm-firefox/page-sniffer.js")
store = read("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
contract = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt")
parser = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt")
payload = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkPayload.kt")
export_models = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt")
legacy = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt")
locator = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
media_classifier = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
media_engine = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")
release_test = read("browser-extension/tests/test_release_gate.js")
detector_test = read("browser-extension/tests/test_detector.js")
background_test = read("browser-extension/tests/test_background.js")
parser_test = read("browser-integration/src/test/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParserTest.kt")
parity_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/MediaLocatorParityContractTest.kt")
workflow = (REPO / ".github/workflows/android.yml").read_text(encoding="utf-8", errors="replace")
project_manifest = read("PROJECT_MANIFEST.json")

# Keyless v3 generation, with v2 reader retained only for migration.
for token in ('CurrentVersion = 3', 'EncryptedCaptureVersion = 2'):
    need(contract, token, "deep-link contract")
for token in ('params.set("v"', 'params.set("url"', 'params.set("length"', 'params.set("durationMs"', 'params.set("thumbnail"', 'sanitizeHeaderBag'):
    need(handoff, token, "direct v3 handoff")
for token in ('captureKeyId', 'capturePublicKeySpki', 'captureOaepHash'):
    forbid(config, token, "generated extension config")
for token in ('params.set("kid"', 'params.set("ek"', 'params.set("iv"', 'params.set("ct"', 'crypto.subtle'):
    forbid(handoff, token, "new v3 handoff")
need(parser, 'EncryptedCaptureVersion -> parseEncryptedCaptureEnvelope', "legacy v2 migration parser")
need(parser, 'CurrentVersion -> parseDirectPayload', "v3 direct parser")
need(payload, 'hasEncryptedCaptureEnvelope', "legacy v2 payload")
forbid(export_models, 'appVersion != metadata.appVersion', "XPI staleness")
need(legacy, 'Legacy v2 browser capture key no longer matches this app install.', "legacy-key migration guidance")

# Repository-level signed publication must also be keyless; otherwise CI would still require retired RSA inputs.
for token in ('XDM_CAPTURE_KEY_ID', 'XDM_CAPTURE_PUBLIC_KEY_SPKI', 'XDM_CAPTURE_OAEP_HASH', 'Require browser capture release key inputs'):
    forbid(workflow, token, "signed-release keyless-v3 cutover")
need(project_manifest, '"browser_release_gate"', "project manifest browser release truth")
need(project_manifest, 'direct-v3 keyless', "project manifest keyless release truth")

# Replay context and optional metadata promised by v3.
for token in ('"accept-language"', '"authorization"', '"cookie"', '"referer"', '"user-agent"', '"range"'):
    need(handoff, token, "v3 replay header allowlist")
    need(parser, token, "Android v3 replay header allowlist")
need(observer, '"accept-language"', "privileged webRequest header capture")
need(page_sniffer, '"accept-language"', "page-side visible header capture")
need(release_test, 'missing thumbnail must not resolve to the page URL', "metadata release regression")
need(release_test, 'thumbnailUrl:"https://cdn.example/poster.jpg"', "explicit thumbnail release regression")
need(parser_test, 'extensionCaptureParsesOptionalSizeDurationAndThumbnailMetadata', "v3 parser metadata regression")

# False-positive controls and evidence promotion.
for token in ('HARD_NON_MEDIA_MIME_RE', 'possible-manifest-extension', 'manifestByMime', 'manifestByExtension'):
    need(detector, token, "browser response classifier")
need(observer, 'visibleCandidateSnapshot', "visible-vs-internal candidate separation")
need(observer, 'analysis.manifestBody', "manifest body promotion")
need(observer, 'reason: analysis.hlsBody ? "hls-body" : "dash-body"', "manifest promotion reason")
need(store, 'mergedQuality', "strong-candidate downgrade prevention")
for token in ('JSON response must beat a misleading .mp4 suffix', 'manifest suffix alone must not auto-offer', 'later weak observations must not downgrade promoted evidence'):
    need(detector_test, token, "detector regression")
for token in ('suffix-only manifest evidence stays hidden by default', 'verified HLS body promotes retained privileged evidence', 'Accept-Language must survive privileged request capture'):
    need(background_test, token, "background evidence regression")

# App/live locator follows the same evidence boundary.
for token in ("document.querySelectorAll('video,audio,source')", 'window.fetch = async function', 'XMLHttpRequest', 'MutationObserver', "initiator !== 'video' && initiator !== 'audio'", 'MediaSniffingEngine()', 'ExternalHandoffReviewActivity::class.java'):
    need(locator, token, "live media locator")
forbid(locator, 'MEDIA_EXT.test(entry.name)', "live performance-resource false-positive path")
for token in ('hardNonMediaMime -> MediaSourceKind.Unknown', 'application/json', 'text/html', 'image/'):
    need(media_classifier, token, "app media classifier")
for token in ('structuredMediaValuePattern', 'mediaTagPattern'):
    need(media_engine, token, "app media evidence extraction")
for token in ('cssUrlPattern.findAll', 'htmlAttributePattern.findAll'):
    forbid(media_engine, token, "generic app-side resource scraping")

capture = view_model.split('private suspend fun executeCaptureMediaCommand', 1)[-1].split('private suspend fun openExternalAddDraft', 1)[0]
need(capture, 'AutomationRejectionReason.NoMediaDetected', "app final media authority")
need(capture, 'Non-media capture ignored', "false-positive feedback")
forbid(capture, 'openExternalAddDraft', "media rejection fallback")
need(screen, 'Static sniff does not execute page JavaScript.', "truthful static/live locator copy")
need(parity_test, 'browserAndAppKeepWeakEvidenceInternalUntilCorroboratedAndNeverFallbackToGenericDownload', "Android parity contract")

if errors:
    print("1DM media locator / XPI v3 validation failed:", file=sys.stderr)
    for error in errors: print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("1DM media locator / XPI v3 validation passed")
