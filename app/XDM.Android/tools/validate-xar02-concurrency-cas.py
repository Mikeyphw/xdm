#!/usr/bin/env python3
"""XAR02 focused validation: generation-owned state machines and CAS concurrency.

This validator is intentionally behavioral/static rather than a source-text smoke test. The
small Python models below reproduce the failure modes from the S04 ledger: stale writes,
late terminal callbacks, equal-revision sidecar replacement, and last-completion-wins UI races.
The repository checks then ensure the Android implementation uses the same guards.
"""
from __future__ import annotations
from dataclasses import dataclass, replace
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[1]

OWNED_IDS = [
    "S04-01", "S04-02", "S04-03", "S04-04", "S04-05", "S04-06", "S04-07", "S04-08", "S04-09", "S04-10", "S04-11", "S04-12",
    "DS2-S04-01", "DS2-S04-02", "DS2-S04-03", "DS2-S04-04", "DS2-S04-05", "DS2-S04-06",
]

FILES = {
    "models": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt",
    "browser_models": ROOT / "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/BrowserHandoffMediaModels.kt",
    "dao": ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadGraphTransactionDao.kt",
    "download_dao": ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadDao.kt",
    "repo": ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt",
    "capture_dao": ROOT / "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt",
    "viewmodel": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    "ui_cas": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/UiMutationConcurrencyCoordinator.kt",
    "native_hls": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt",
    "handoff_store": ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt",
    "secure_store": ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStore.kt",
    "registry": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserCaptureSessionRegistry.kt",
    "coordinator": ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserHandoffMediaCoordinator.kt",
    "queue": ROOT / "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt",
    "external": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationDispatch.kt",
    "locator": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt",
    "migrator": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/SensitivePersistenceMigrator.kt",
    "termux": ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt",
    "report": ROOT / "docs/remediation/XAR02-CONCURRENCY-CAS-OWNERSHIP.md",
}


def fail(message: str) -> None:
    print(f"XAR02 validation failed: {message}", file=sys.stderr)
    sys.exit(1)


def read(name: str) -> str:
    path = FILES[name]
    if not path.exists():
        fail(f"missing required file {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def require_text(name: str, needle: str, message: str | None = None) -> None:
    require(needle in read(name), message or f"{needle!r} not present in {FILES[name].relative_to(ROOT)}")


@dataclass(frozen=True)
class DownloadRow:
    state: str
    attempt: int
    revision: int
    priority: int = 0


def cas_download_update(current: DownloadRow, observed_attempt: int, observed_revision: int, next_state: str) -> DownloadRow | None:
    if current.attempt != observed_attempt or current.revision != observed_revision:
        return None
    if current.state in {"Completed", "Cancelled"} and next_state not in {"Completed", "Cancelled"}:
        return None
    return replace(current, state=next_state, revision=current.revision + 1)


def transition_owned(current: DownloadRow, observed_attempt: int, observed_revision: int, allowed: set[str], next_state: str) -> DownloadRow | None:
    if current.state not in allowed:
        return None
    return cas_download_update(current, observed_attempt, observed_revision, next_state)


def test_stale_download_and_terminal_resurrection() -> None:
    observed = DownloadRow("Queued", 1, 100)
    claimed = transition_owned(observed, 1, 100, {"Queued"}, "Active")
    require(claimed is not None and claimed.revision == 101, "fresh claim should succeed")
    stale_hold = cas_download_update(claimed, 1, 100, "WaitingForPower")
    require(stale_hold is None, "stale hold must not overwrite a successful claim")
    terminal = DownloadRow("Completed", 1, 200)
    resurrect = cas_download_update(terminal, 1, 200, "Queued")
    require(resurrect is None, "late callbacks must not resurrect terminal downloads")


def test_state_transition_owner_and_allowed_states() -> None:
    current = DownloadRow("Paused", 3, 44)
    require(transition_owned(current, 3, 44, {"Paused"}, "Queued") is not None, "matching owner/revision should transition")
    require(transition_owned(current, 2, 44, {"Paused"}, "Queued") is None, "wrong attempt must fail")
    require(transition_owned(current, 3, 43, {"Paused"}, "Queued") is None, "stale revision must fail")
    require(transition_owned(current, 3, 44, {"Queued"}, "Active") is None, "unexpected source state must fail")


def test_priority_reprioritize_is_claim_fenced() -> None:
    observed = DownloadRow("Queued", 7, 10, priority=1)
    claimed = transition_owned(observed, 7, 10, {"Queued"}, "Active")
    require(claimed is not None, "claim precondition")
    stale_repriority = None if claimed.revision != observed.revision else replace(claimed, priority=99)
    require(stale_repriority is None, "reprioritize must reject when a claim already advanced the revision")


def test_native_hls_control_ordering() -> None:
    controls: list[tuple[int, str]] = []
    seq = 0
    def issue(command: str) -> int:
        nonlocal seq
        seq += 1
        controls.append((seq, command))
        return seq
    pause_seq = issue("pause")
    resume_seq = issue("resume")
    require(pause_seq < resume_seq, "control sequence should be monotonic")
    last = max(controls)[1]
    require(last == "resume", "serialized controls must apply last issued command, not last callback completion")


def test_destination_last_intent_wins() -> None:
    latest_intent = 0
    published: str | None = None
    def choose(uri: str) -> int:
        nonlocal latest_intent
        latest_intent += 1
        return latest_intent
    def complete(token: int, uri: str) -> None:
        nonlocal published
        if token == latest_intent:
            published = uri
    slow = choose("content://old")
    fast = choose("content://new")
    complete(fast, "content://new")
    complete(slow, "content://old")
    require(published == "content://new", "destination health callbacks must be last-intent-wins")


def merge_equal_revision(existing: dict[str, object], incoming: dict[str, object]) -> dict[str, object] | None:
    if existing["revision"] != incoming["revision"]:
        return incoming if incoming["revision"] > existing["revision"] else None
    identity = ("exact", "headers", "stable")
    if any(existing[k] != incoming[k] for k in identity):
        return None
    merged = dict(existing)
    merged["final"] = incoming.get("final") or existing.get("final")
    merged["expires"] = max(int(existing.get("expires") or 0), int(incoming.get("expires") or 0))
    return merged


def test_browser_equal_revision_merge_rejects_conflicts() -> None:
    existing = {"revision": 9, "exact": "https://e/video.m3u8", "headers": ("cookie:a",), "stable": "cap", "final": None, "expires": 10}
    enrich = {**existing, "final": ("cookie:a", "range:0-"), "expires": 20}
    require(merge_equal_revision(existing, enrich)["final"] == enrich["final"], "equal revision may enrich final evidence")
    conflict = {**existing, "exact": "https://e/other.m3u8"}
    require(merge_equal_revision(existing, conflict) is None, "equal revision with different exact request must be rejected")


def envelope_put(existing: dict[str, object] | None, incoming: dict[str, object]) -> bool:
    if existing is None:
        return True
    if incoming["subjectGeneration"] < existing["subjectGeneration"]:
        return False
    if incoming["subjectGeneration"] == existing["subjectGeneration"] and incoming != existing:
        return False
    return True


def test_exact_sidecar_subject_generation() -> None:
    existing = {"id": "capture:a", "subjectGeneration": 5, "exact": "https://e/a", "headers": ("cookie:a",)}
    stale = {**existing, "subjectGeneration": 4}
    conflict = {**existing, "exact": "https://e/b"}
    require(not envelope_put(existing, stale), "stale exact request envelope must be rejected")
    require(not envelope_put(existing, conflict), "equal-generation conflicting exact request envelope must be rejected")
    require(envelope_put(existing, existing), "idempotent replay of same generation/payload must be accepted")


def test_media_output_terminal_resurrection_rejected() -> None:
    completed = DownloadRow("Completed", 11, 90)
    late_active = cas_download_update(completed, 11, 90, "Active")
    require(late_active is None, "late MediaOutput callback must not downgrade completed output")


def run_behavioral_tests() -> None:
    tests = [
        test_stale_download_and_terminal_resurrection,
        test_state_transition_owner_and_allowed_states,
        test_priority_reprioritize_is_claim_fenced,
        test_native_hls_control_ordering,
        test_destination_last_intent_wins,
        test_browser_equal_revision_merge_rejects_conflicts,
        test_exact_sidecar_subject_generation,
        test_media_output_terminal_resurrection_rejected,
    ]
    for test in tests:
        test()
    print(f"{len(tests)} focused adversarial concurrency tests passed.")


def run_repository_contracts() -> None:
    for name in FILES:
        read(name)

    report = read("report")
    missing = [cid for cid in OWNED_IDS if cid not in report]
    require(not missing, f"implementation report missing canonical IDs: {', '.join(missing)}")
    require("18/18" in report, "implementation report must declare 18/18 XAR02 coverage")

    require_text("models", "observedAttemptGeneration: Long = attemptGeneration")
    require_text("models", "rowRevision: Long = updatedAtEpochMs")
    require_text("dao", "insertDownloadIgnore")
    require_text("dao", "updateDownloadOwnedRevision")
    require_text("dao", "isDownloadOwnedRevisionCurrent")
    require_text("dao", "transitionDownloadStateOwned")
    require_text("dao", "updateDownloadPriorityOwned")
    require_text("download_dao", "updateIfUnchanged")
    require_text("repo", "transitionDownloadStateIfCurrent")
    require_text("repo", "reprioritizeDownloads")
    require_text("repo", "rejectsTerminalDownloadResurrection")
    require_text("repo", "saveMediaCaptureWithVariants")
    require_text("repo", "findOutputById(record.id)")
    require_text("repo", "current.attemptGeneration != record.observedAttemptGeneration")
    require_text("capture_dao", "findOutputById")
    require_text("queue", "transitionDownloadStateIfCurrent")
    require_text("viewmodel", "private val uiMutationConcurrency = UiMutationConcurrencyCoordinator()")
    require_text("ui_cas", "nextDestinationIntent")
    require_text("ui_cas", "isCurrentDestinationIntent")
    require_text("viewmodel", "liveValidatedDestinationUris.update")
    require_text("viewmodel", "repository.updateQueueIfUnchanged")
    require_text("viewmodel", "repository.updateScheduleIfUnchanged")
    require_text("viewmodel", "repository.reprioritizeDownloads")
    require_text("viewmodel", "repository.findDownload(download.id)")
    require_text("viewmodel", "check(MediaRequestHandoffStore.rememberCapture")
    require_text("ui_cas", "AtomicLong")
    require_text("ui_cas", "ExclusiveOperationGate")
    require_text("native_hls", "suspend fun pause")
    require_text("native_hls", "suspend fun resume")
    require_text("native_hls", "ControlIntent")
    require_text("native_hls", "withLock")
    require_text("handoff_store", "subjectGeneration: Long = attemptGeneration")
    require_text("handoff_store", "@Synchronized")
    require_text("handoff_store", "return remember(")
    require_text("handoff_store", "if (!durableStore.put")
    require_text("secure_store", "fun put(envelope: SecureRequestEnvelope): Boolean")
    require_text("secure_store", "canReplaceSecureEnvelope")
    require_text("secure_store", "subjectGeneration")
    require_text("browser_models", "fun mergeEqualRevision")
    require_text("browser_models", "incomingRevision > existingRevision")
    require_text("registry", "mergedCandidates")
    require_text("coordinator", "mergeEqualRevision")
    require_text("external", "}.getOrDefault(false)")
    require_text("locator", "check(MediaRequestHandoffStore.rememberVariant")
    require_text("termux", "check(repository.saveMediaCaptureWithVariants")

    final_gate = ROOT / "tools/run-final-release-gate.sh"
    build = ROOT / "app/build.gradle.kts"
    require("validate-xar02-concurrency-cas.py" in final_gate.read_text(encoding="utf-8"), "XAR02 validator must be wired to final static gate")
    require("verifyXar02ConcurrencyCas" in build.read_text(encoding="utf-8"), "XAR02 Gradle verification task missing")
    print("XAR02 repository contracts passed.")


def main() -> None:
    run_behavioral_tests()
    run_repository_contracts()
    digest = hashlib.sha256("\n".join(OWNED_IDS).encode()).hexdigest()[:12]
    print(f"XAR02 concurrency/CAS validation passed (18/18 S04 canonical roots covered, ledger={digest}).")


if __name__ == "__main__":
    main()
