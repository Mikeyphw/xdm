#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def read(rel: str) -> str:
    try:
        return (ROOT / rel).read_text(encoding="utf-8")
    except Exception as exc:
        errors.append(f"cannot read {rel}: {exc}")
        return ""

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

debug = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt")
shell = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugWorkbenchShellModels.kt")
problems = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ProblemIncidentModels.kt")
reporter = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppProblemReporter.kt")
application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
viewmodel = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
prefs = read("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
locator = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
center = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt")
export = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt")
registry = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferSystemIdRegistry.kt")
core_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ProblemIncidentModelsTest.kt")
app_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ObservabilityProblemReportingContractTest.kt")
report = read("XDM_OBSERVABILITY_PROBLEM_REPORTING_OBS01_OBS02_REPORT.md")
manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("observability_problem_reporting_obs01_obs02", {})

for area in ("MediaResolver", "WebView", "Backend", "Storage", "Persistence", "Thumbnail"):
    need(area in debug, f"DebugArea must include {area}")
    need(f"DebugArea.{area}" in shell, f"DebugArea.supportLabel must cover {area}")
need("setVerboseLoggingEnabled" in debug and "DebugSeverity.Trace && !verboseLoggingEnabled" in debug,
     "rolling recorder must make trace retention explicitly verbose-only")
need("runCatching" in debug and "Diagnostics are strictly best-effort" in debug,
     "debug recording must be best-effort and non-disruptive")

need("data class ProblemIncident(" in problems and "class FileProblemIncidentStore(" in problems,
     "durable problem incident model/store must exist")
need("notificationCooldownMs" in problems and "occurrenceCount" in problems,
     "problem incidents must deduplicate/count and notification-cool down")
need("DebugRedactor.redactText" in problems and "retainedIncidents" in problems,
     "problem persistence must remain redacted and bounded")
need("androidx.room" not in problems and "RoomDatabase" not in problems, "problem ledger must remain independent of Room")

need('CHANNEL_PROBLEMS = "xdm_runtime_problems"' in reporter and 'setContentTitle("XDM needs attention")' in reporter,
     "actionable problem notification channel/title must exist")
need("PROBLEM_NOTIFICATION_ID_BASE = 1_000_000_000" in reporter and "PROBLEM_NOTIFICATION_ID_RANGE" in reporter,
     "problem notification IDs must use the reserved high range")
need("FIRST_ID = 20_000" in registry and "LAST_ID = 900_000_000" in registry,
     "transfer notification ID range contract changed unexpectedly")
need("val upsert = runCatching" in reporter and "store.upsert(" in reporter and "runCatching { notifications.post" in reporter,
     "incident recording/notification must not crash runtime operations")
need("problem-reported" in reporter, "problem creation must correlate into the debug event timeline")

need("ProblemReporterProvider" in application and "problemReporter = AppProblemReporter" in application,
     "application must own and expose the canonical problem reporter")
need("setVerboseLoggingEnabled(prefs.verboseDebugLoggingEnabled)" in application,
     "persisted verbose logging preference must configure the rolling recorder")
need("notifyUser = false" in application and "TransferNotifications already owns" in application,
     "terminal transfer incidents must not duplicate existing transfer notifications")
need("DebugArea.Persistence" in application and "DebugArea.Scheduler" in application and "DebugArea.Backend" in application,
     "startup and terminal runtime failures must feed the incident ledger")

need("verbose_debug_logging_enabled" in prefs and "setVerboseDebugLoggingEnabled" in prefs,
     "verbose logging preference must be durable")
need("setVerboseDebugLoggingEnabled" in viewmodel and "DebugArea.MediaResolver" in viewmodel,
     "ViewModel must expose verbose logging and report centralized media failures")
need("consumeProblemNavigation" in activity and "SettingsPanel.DebugWorkbench" in activity,
     "problem notification review must deep-link into Diagnostics & support")

need("DebugSeverity.Trace" in locator and 'action = "page-load"' in locator and 'action = "renderer-process"' in locator,
     "embedded WebView must emit lifecycle/request observability events")
need("problemReporter?.report" in locator and "Media browser renderer stopped" in locator,
     "renderer death must create an actionable durable problem")

need('Problems("Problems")' in center and 'XdmCardTitle("Debug logging")' in center,
     "Diagnostics & support must expose Problems and Debug logging controls")
for token in ("Mark resolved", "Reopen", "Copy details", "Recommended action"):
    need(token in center, f"Problems UI missing {token}")
need('problem-incidents.txt' in export, "support ZIP must include problem-incidents.txt")

need("incidentsAreRedactedDeduplicatedAndNotificationCooledDown" in core_test,
     "core incident regression test missing")
need("observabilityOverlayWiresDurableProblemsVerboseLoggingAndReviewNavigation" in app_test,
     "app observability contract test missing")
need("All `core-model` main Kotlin sources compile" in report and "Full Gradle/Android validation is intentionally deferred" in report,
     "implementation report must state performed and deferred validation truthfully")

need(entry.get("status") == "implemented_intermediate", "manifest must mark OBS01/OBS02 implemented intermediate")
need(entry.get("standard_logging_default") is True and entry.get("verbose_trace_opt_in") is True,
     "manifest must record standard/verbose logging contract")
need(entry.get("durable_problem_ledger") is True and entry.get("problem_notifications") is True,
     "manifest must record incident ledger and notifications")
need(entry.get("duplicate_transfer_notifications") is False,
     "manifest must forbid duplicate terminal transfer notifications")
need(entry.get("automatic_upload") is False and entry.get("room_schema_unchanged") == 21,
     "observability overlay must preserve privacy and Room schema")
need(entry.get("validation_deferred") is True and entry.get("next_overlay") == "xdm_android_media_thumbnail_mime_presentation_v1.zip",
     "manifest must retain intermediate validation/next-overlay contract")

if errors:
    print("OBS01/OBS02 observability/problem-reporting validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("OBS01/OBS02 observability/problem-reporting validator: OK")
