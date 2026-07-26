#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def require(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle not in text:
            ERRORS.append(f"{relative} missing {needle!r}")
    return text


panels = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ActivityPanel.kt",
    'Overview("Needs attention")',
    'Timeline("Recent")',
    'Attention("Needs attention")',
    'Diagnostics("Developer tools")',
    "val primaryPanels = listOf(Attention, Timeline)",
    "val managePanels = listOf(Decisions, Queues, Schedule, Recovery)",
    "fun normalized(developerOptionsEnabled: Boolean)",
)
planner = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityWorkspace.kt",
    "data class ActivityWorkspaceMetrics",
    "object ActivityWorkspacePlanner",
    "fun metrics(",
    "fun forPanel(",
    "fun consequence(",
    'it.source == "queue-policy"',
)
for forbidden in ("android.content", "androidx.compose", "RoomDatabase", "DownloadRepository"):
    if forbidden in planner:
        ERRORS.append(f"Pure Activity workspace planner depends on forbidden runtime token: {forbidden}")

activity = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt",
    'XdmSectionHeader("Activity")',
    'TextButton(onClick = onOpenManage) { Text("Manage") }',
    "ActivityPanel.primaryPanels",
    '"Nothing needs attention"',
    '"No recent activity"',
    "ActivityWorkspacePlanner.consequence(event)",
)
if "ActivityPanel.entries" in activity:
    ERRORS.append("Normal Activity still renders every panel as peer navigation")
for forbidden in (
    "DiagnosticsScreen(",
    "OperationalDiagnosticsHeader(",
    "MediaDispatchDashboardCard",
    "MediaQueueTelemetryCard",
    "MediaWorkerBridgeCard",
    "TermuxBridgeDiagnosticsCard",
):
    if forbidden in activity:
        ERRORS.append(f"Normal Activity exposes developer dashboard token: {forbidden}")

app = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
    "ActivityWorkspaceScreen(",
    "ActivityPanel.managePanels",
    'title = "Manage activity"',
    "XdmAdaptiveSheet(",
    "if (panel == ActivityPanel.Diagnostics) viewModel.openDeveloperTools()",
    "AppRoute.Settings -> SettingsScreen(state, viewModel)",
)

settings = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt",
    'title = "Save location"',
    'title = "Smart queue"',
    'title = "Notifications"',
    'title = "Advanced download rules"',
    'XdmSectionHeader("Appearance")',
    'title = "Privacy"',
    'title = "Copy support report"',
    'title = "Developer options"',
    'XdmSectionHeader("About")',
    "state.supportReportText",
    "if (state.developerOptionsEnabled)",
)
order = [
    settings.find('title = "Save location"'),
    settings.find('title = "Smart queue"'),
    settings.find('title = "Notifications"'),
    settings.find('title = "Advanced download rules"'),
    settings.find('XdmSectionHeader("Appearance")'),
    settings.find('XdmSectionHeader("Privacy and support")'),
    settings.find('XdmSectionHeader("About")'),
]
if any(index < 0 for index in order) or order != sorted(order):
    ERRORS.append("Settings groups are not ordered around everyday user needs")
if settings.find('title = "Copy support report"') > settings.find('title = "Developer options"'):
    ERRORS.append("Support report must remain available before and independently of Developer options")
for forbidden in (
    "MediaDispatchDashboardCard",
    "MediaQueueTelemetryCard",
    "MediaWorkerBridgeCard",
    "ReleaseReadinessSection(",
    "TermuxBridgeDiagnosticsCard",
    "DiagnosticsScreen(",
):
    if forbidden in settings:
        ERRORS.append(f"Normal Settings inlines developer dashboard token: {forbidden}")

advanced = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt",
    "Destination rules",
    "Duplicate URL rules",
    "Proxy and credentials",
    "TermuxBridgeSettingsCard(",
    "TermuxAria2SettingsCard(",
    "PostProcessingAutomationCard(",
    "Settings import/export",
)
developer_settings = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperSettingsScreen.kt",
    "UiAudience.Developer",
    "if (!state.developerOptionsEnabled)",
    "DeveloperToolsWorkspace(",
    "Enable developer options",
)
workspace = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt",
    'RuntimeEngines("Runtime and engines")',
    'TermuxAria2("Termux and aria2")',
    'MediaPipeline("Media pipeline")',
    'DispatchWorkers("Dispatch and workers")',
    'PrivacyCleanup("Privacy and cleanup")',
    'ValidationRelease("Validation and release")',
    'IntakeClipboard("Intake and clipboard")',
    'LogsExports("Redacted logs and exports")',
    "ReleaseReadinessSection(state)",
    "PrivacyDiagnosticsRedactor.redactUrl(item.url)",
    '"Copy support report"',
)
for forbidden in (
    "XdmSupportingText(item.url",
    "Text(item.url",
    "raw shell",
    "shell command textbox",
    "execute arbitrary command",
):
    if forbidden.lower() in workspace.lower():
        ERRORS.append(f"Developer workspace violates redaction or typed-action boundary: {forbidden}")

prefs = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt",
    "enum class XdmThemeMode",
    "val developerOptionsEnabled: Boolean = false",
    'booleanPreferencesKey("developer_options_enabled")',
    "suspend fun setDeveloperOptionsEnabled(enabled: Boolean)",
    'stringPreferencesKey("theme_mode")',
)
view_model = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    "val developerOptionsEnabled: Boolean = false",
    "val settingsPanel: SettingsPanel = SettingsPanel.Overview",
    "val supportReportText: String = \"\"",
    "activityPanel = navigation.activityPanel.normalized(prefs.developerOptionsEnabled)",
    "fun openDeveloperTools()",
    "fun setDeveloperOptionsEnabled(enabled: Boolean)",
    "settingsPanel = SettingsPanel.DeveloperTools",
)
if "if (!enabled && navigationOverride.value.settingsPanel == SettingsPanel.DeveloperTools)" not in view_model:
    ERRORS.append("Disabling Developer options does not close the developer workspace")

theme = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmTheme.kt",
    "XdmAmoledColorScheme",
    "mode: XdmThemeMode = XdmThemeMode.Dark",
    "if (mode == XdmThemeMode.Amoled)",
)
main_activity = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
    "collectAsStateWithLifecycle()",
    "XdmTheme(mode = state.themeMode)",
)

require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/UixR5ActivityWorkspaceTest.kt",
    "metricsCountAttentionDecisionsAndOnlyEventsFromToday",
    "primaryPanelsStayFocusedWhileLegacyPanelsNormalizeSafely",
    "attentionFiltersUnresolvedItemsAndConsequencesUsePlainLanguage",
)
require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/UixR5ActivitySettingsContractTest.kt",
    "normalActivityHasTwoPrimaryViewsAndMovesManagementIntoASecondarySheet",
    "settingsStartsWithEverydayChoicesAndKeepsDeveloperToolsGated",
    "developerWorkspaceIsGroupedRedactedAndHasNoRawShellSurface",
)
require(
    "docs/architecture/UIX-R5-ACTIVITY-SETTINGS-DEVELOPER-BOUNDARY.md",
    "Needs attention",
    "Developer options",
    "Developer mode changes visibility, never privacy",
    "raw shell textbox",
)
require(
    "docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md",
    "UIX R5 Activity, Settings, and Developer Boundary Rules",
    "Diagnostics must not appear as a normal Activity tab",
)

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    if f'{route}("{route}"' not in routes:
        ERRORS.append(f"Stable route missing: {route}")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    ERRORS.append("Room schema must remain 14")
build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    ERRORS.append("App version must remain 0.20.0-rc08 / 21")

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
r5 = manifest.get("uix_r5_activity_settings_developer_boundary", {})
expected = {
    "activity_default": "Needs attention",
    "activity_primary_views": ["Needs attention", "Recent"],
    "activity_management_sheet": ["Queue decisions", "Queues", "Schedules", "Recovery"],
    "normal_activity_diagnostics": False,
    "settings_everyday_first": True,
    "developer_options_default": False,
    "developer_options_persisted": True,
    "support_report_without_developer_mode": True,
    "developer_redaction_mandatory": True,
    "raw_shell_surface": False,
    "room_schema_unchanged": 14,
    "version_name_unchanged": "0.20.0-rc08",
    "version_code_unchanged": 21,
}
for key, value in expected.items():
    if r5.get(key) != value:
        ERRORS.append(f"PROJECT_MANIFEST uix_r5_activity_settings_developer_boundary.{key} must equal {value!r}")
if manifest.get("current_uix_overlay") != "xdm_android_uix_r5_activity_settings_developer_boundary_overlay.zip":
    ERRORS.append("current_uix_overlay must identify UIX R5")

validator = "tools/validate-uix-r5-activity-settings-developer-boundary.py"
for gate in ("tools/run-final-release-gate.sh", ".github/workflows/android.yml"):
    if validator not in read(gate):
        ERRORS.append(f"{gate} does not run {validator}")

if ERRORS:
    print("UIX R5 Activity, Settings, and Developer boundary validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("UIX R5 Activity, Settings, and Developer boundary validation passed")
