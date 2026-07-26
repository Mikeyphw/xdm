#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/ui"
COMPAT = ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt"

required = [
    "common/UiAudience.kt",
    "common/UiTextHelpers.kt",
    "downloads/DownloadsScreen.kt",
    "downloads/DownloadRow.kt",
    "downloads/DownloadDetails.kt",
    "intake/AddDownloadSurface.kt",
    "media/MediaInboxScreen.kt",
    "media/MediaCaptureCard.kt",
    "library/MediaLibraryScreen.kt",
    "activity/ActivityScreen.kt",
    "activity/QueueManagementScreen.kt",
    "activity/SchedulerScreen.kt",
    "recovery/RecoveryScreen.kt",
    "settings/SettingsScreen.kt",
    "developer/DeveloperToolsScreen.kt",
]
errors: list[str] = []

for rel in required:
    path = UI_ROOT / rel
    if not path.is_file():
        errors.append(f"missing modular UI source: {rel}")

if not COMPAT.is_file():
    errors.append("Screens.kt compatibility marker is missing")
elif len(COMPAT.read_text(errors="replace").splitlines()) > 250:
    errors.append("Screens.kt must remain a compatibility marker under 250 lines")

active_sources = sorted(UI_ROOT.rglob("*.kt"))
active_text = "\n".join(path.read_text(errors="replace") for path in active_sources)
user_sources = [path for path in active_sources if "/developer/" not in path.as_posix()]
user_text = "\n".join(path.read_text(errors="replace") for path in user_sources)
developer_path = UI_ROOT / "developer/DeveloperToolsScreen.kt"
developer_text = developer_path.read_text(errors="replace") if developer_path.is_file() else ""
media_user_text = "\n".join(
    (UI_ROOT / rel).read_text(errors="replace")
    for rel in ("media/MediaInboxScreen.kt", "media/MediaCaptureCard.kt", "library/MediaLibraryScreen.kt")
    if (UI_ROOT / rel).is_file()
)

if "onClick = {}" in active_text:
    errors.append("active modular UI contains an inert onClick placeholder")

for token in (
    "Phase 22", "Phase 23", "Phase 24", "Phase 25", "Phase 26", "Phase 27",
    "Phase 28", "Phase 29", "Phase 30", "Phase 31", "Phase 32", "Phase 33",
    "control tower", "telemetry deck", "worker bridge", "runtime adapter",
    "final validation gate", "session privacy audit", "raw planner output",
):
    if token.lower() in user_text.lower():
        errors.append(f"normal user UI leaks developer-only copy: {token}")

for call in (
    "MediaFinalValidationGateCard(", "MediaMobilePolishCard(",
    "MediaDispatchDashboardCard(", "MediaQueueTelemetryCard(",
    "MediaQueueActionsCard(", "MediaWorkerBridgeCard(",
    "MediaTermuxRuntimeAdapterCard(", "MediaNativeDirectDownloadEngineCard(",
    "MediaCaptureQualityCard(", "SessionPrivacyAuditCard(",
    "OfflineLibraryV2Card(", "PlayerDiagnosticsDeckCard(",
):
    if call in media_user_text:
        errors.append(f"normal Media/Library still renders developer planner card: {call}")

for token in (
    "UiAudience.User", "UiAudience.Advanced", "UiAudience.Developer",
    "MediaDeveloperToolsSection", "Media final validation gate",
    "Media dispatch control tower", "Media queue telemetry", "Media worker bridge",
    "Media Termux runtime adapter", "Native direct download engine",
    "Session privacy audit", "Offline Library 2.0", "Player diagnostics deck",
):
    haystack = active_text if token.startswith("UiAudience") else developer_text
    if token not in haystack:
        errors.append(f"missing UIX R1 audience/developer contract token: {token}")

screens_test = ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/UiSourceTree.kt"
architecture_test = ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt"
if not screens_test.is_file() or "walkTopDown" not in screens_test.read_text(errors="replace"):
    errors.append("package-aware UiSourceTree test helper is missing")
if not architecture_test.is_file() or "readDeveloper" not in architecture_test.read_text(errors="replace"):
    errors.append("architecture contracts do not enforce the user/developer boundary")

app_route = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").read_text(errors="replace")
for forbidden in ("Developer(\"Developer", "Worker(\"Worker", "Telemetry(\"Telemetry"):
    if forbidden in app_route:
        errors.append(f"UIX R1 added a forbidden top-level route: {forbidden}")

manifest = (ROOT / "PROJECT_MANIFEST.json").read_text(errors="replace")
if "uix_r1_surface_contract_modularization" not in manifest:
    errors.append("PROJECT_MANIFEST does not record UIX R1")
if '"room_schema_unchanged": 14' not in manifest:
    errors.append("UIX R1 must preserve Room schema 14")

if errors:
    print("UIX R1 surface-boundary validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("UIX R1 surface-boundary validation passed")
