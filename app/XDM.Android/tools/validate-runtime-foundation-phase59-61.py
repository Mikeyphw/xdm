#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
errors: list[str] = []

def read(path: str, repo: bool = False) -> str:
    target = (REPO if repo else ROOT) / path
    if not target.is_file():
        errors.append(f"missing {path}")
        return ""
    return target.read_text(encoding="utf-8")

def need(text: str, token: str, label: str) -> None:
    if token not in text:
        errors.append(f"{label}: missing {token}")

def forbid(text: str, token: str, label: str) -> None:
    if token in text:
        errors.append(f"{label}: forbidden {token}")

contract = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkContract.kt")
parser = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkParser.kt")
manager = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt")
application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
vm = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
registry = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserCaptureSessionRegistry.kt")
models = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/BrowserCaptureSessionModels.kt")
screen = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
handoff = read("browser-extension/src/main/extension/xdm-firefox/handoff.js")
network = read("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
frame = read("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
config = read("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
manifest_text = read("browser-extension/src/main/extension/xdm-firefox/manifest.template.json")

for token in ("LegacyVersion = 1", "CurrentVersion = 2", "WrappedKeyParameter", "EnvelopeCiphertextParameter"):
    need(contract, token, "v2 deep-link contract")
need(parser, "parseEncryptedCaptureEnvelope", "v2 parser")
for token in ("AndroidKeyStore", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "AES/GCM/NoPadding", "xdm-capture-v2|"):
    need(manager, token, "capture crypto")
need(activity, "payload.hasEncryptedCaptureEnvelope", "activity v2 routing")
need(activity, "ingestEncryptedBrowserCapture", "activity v2 routing")
need(application, "MediaRequestHandoffStore.initialize(AndroidSecureRequestEnvelopeStore(this))", "secure execution store")
need(application, "val browserHandoffMediaCoordinator = BrowserHandoffMediaCoordinator()", "ephemeral legacy coordinator")
forbid(application, 'FileBackedBrowserHandoffMediaSessionStore(File(filesDir, "browser-handoff-media-sessions")', "plaintext browser session persistence")

for token in ("data class BrowserCaptureSessionSummary", "data class BrowserCaptureCandidateSummary"):
    need(models, token, "capture-session models")
need(registry, "no URLs or request headers", "non-secret session registry")
for token in ("exactUrl", "requestHeaders", "authorization"):
    forbid(registry, token, "non-secret session registry")

for token in (
    "browserCaptureSessionRegistry.record",
    "browserCaptureSessionRegistry.snapshot().any",
    "Replay was ignored",
    "MediaRequestHandoffStore.rememberCapture",
    "decoded.candidates.forEach",
    "navigate(AppRoute.Media)",
):
    need(vm, token, "Android session import")
need(vm, "CurrentRoomSchemaVersion = 17", "Room schema")

for token in ("Firefox capture sessions", "BrowserCaptureSessionHeader", "bounded encrypted Android handoff"):
    need(screen, token, "captured media inbox")
for token in ("captureKeyId", "capturePublicKeySpki", "@@CONTRACT_VERSION@@"):
    need(config, token, "generated extension config")
for token in ("buildEncryptedCaptureSession", 'params.set("ct"', 'params.set("ek"', 'params.set("iv"', "sanitizeHeaderBag"):
    need(handoff, token, "encrypted extension handoff")
for token in ('params.set("authorization"', 'params.set("cookie"', 'params.set("headers"'):
    forbid(handoff, token, "plaintext extension URI")
for token in ("candidateStore.snapshot(tabId, MAX_HANDOFF_CANDIDATES)", "buildEncryptedCaptureSession", "previous.candidateCount === candidateCount", "previous.revision === candidateRevision", "prebuiltXdmLink"):
    need(network, token, "session-aware background dispatch")
need(frame, "prebuiltXdmLink: input.prebuiltXdmLink", "prebuilt encrypted link propagation")

try:
    manifest = json.loads(manifest_text)
    background = (manifest.get("background") or {}).get("scripts") or []
    if "handoff.js" not in background or "network-observer.js" not in background or background.index("handoff.js") > background.index("network-observer.js"):
        errors.append("manifest must load handoff.js before network-observer.js")
except Exception as exc:
    errors.append(f"manifest parse failed: {exc}")

try:
    project = json.loads(read("PROJECT_MANIFEST.json"))
    phase = project.get("runtime_foundation_2026_phase59_61") or {}
    if phase.get("status") != "implemented": errors.append("project manifest Phase 59-61 status is not implemented")
    if phase.get("room_schema_unchanged") != 17: errors.append("project manifest Room schema must remain 17")
except Exception as exc:
    errors.append(f"project manifest parse failed: {exc}")

if errors:
    print("Runtime Foundation Phase 59-61 validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)
print("RUNTIME_FOUNDATION_PHASE59_61_VALIDATION_OK")
