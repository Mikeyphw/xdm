#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise SystemExit(f"missing required file: {path}")
    return target.read_text(encoding="utf-8")

def require(text: str, token: str, label: str) -> None:
    if token not in text:
        raise SystemExit(f"missing {label}: {token}")

def reject(text: str, token: str, label: str) -> None:
    if token in text:
        raise SystemExit(f"forbidden {label}: {token}")

screens = read("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt")
dispatcher = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionDispatcher.kt")
tests = read("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
manifest = read("PROJECT_MANIFEST.json")
read("docs/architecture/PHASE-22-MEDIA-DISPATCH-CONTROL-TOWER.md")

for token in [
    "MediaDispatchReadiness",
    "MediaDispatchStepKind",
    "MediaDispatchPlan",
    "MediaRetryPolicy",
    "MediaProgressSignal",
    "MediaDispatchDashboard",
    "Register terminal cleanup",
    "Verify no durable secrets",
    "redactKnownSecrets",
]:
    require(dispatcher, token, "dispatcher token")

for token in [
    "Media dispatch control tower",
    "Dispatch runbook",
    "toneForDispatchReadiness",
    "primaryActionLabel",
    "secret-safe",
]:
    require(screens, token, "screen token")

for token in [
    "mediaDispatchRunbookKeepsSecretsOut",
    "mediaDispatchDashboardCounts",
    "secret-cookie",
    "secret-token",
    "secret-session",
]:
    require(tests, token, "test token")

require(contract, "mediaDispatchControlTowerContractsArePresent", "architecture contract")
require(manifest, "media_dispatch_control_tower", "manifest phase")
require(manifest, "no_validation_until_final_phase", "deferred validation marker")

for token in ["Cookie:", "Authorization: Bearer", "secret-auth"]:
    reject(dispatcher, token, "raw secret")

print("Phase 22 media dispatch control tower validation passed")
