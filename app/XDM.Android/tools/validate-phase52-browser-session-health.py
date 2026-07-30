#!/usr/bin/env python3
from pathlib import Path
import json
import sys


def find_root() -> Path:
    cursor = Path.cwd().resolve()
    for parent in [cursor, *cursor.parents]:
        if (parent / 'app' / 'XDM.Android' / 'settings.gradle.kts').is_file():
            return parent
        if (parent / 'settings.gradle.kts').is_file() and (parent / 'app' / 'src').is_dir():
            return parent.parent.parent
    raise SystemExit('Android root not found')

ROOT = find_root()
errors = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f'missing {rel}')
        return ''
    return path.read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

model = read('app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/BrowserSessionHealth.kt')
model_test = read('app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/BrowserSessionHealthPlannerTest.kt')
screen = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt')
app = read('app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase52BrowserSessionHealthContractTest.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-52-BROWSER-SESSION-HEALTH.md')

require('data class BrowserSessionHealthReport' in model, 'BrowserSessionHealthReport missing')
require('data class BrowserSessionHealthSignal' in model, 'BrowserSessionHealthSignal missing')
require('object BrowserSessionHealthPlanner' in model, 'BrowserSessionHealthPlanner missing')
require('fun evaluate(' in model, 'BrowserSessionHealthPlanner evaluate API missing')
require('hasCredentialBearingQuery' in model, 'credential-bearing query risk detection missing')
require('Raw Cookie' in model and 'must remain transient and redacted' in model, 'model privacy boundary missing')
require('File(' not in model and 'SharedPreferences' not in model and 'Room' not in model, 'session health must not persist private values')

require('signedBrowserHandoffReportsHighRiskWithoutLeakingSecrets' in model_test, 'high-risk redaction test missing')
require('missingBrowserContextRecommendsMediaInspectionForUnknownPages' in model_test, 'missing-context model test missing')

require('externalSessionHealth: BrowserSessionHealthReport?' in screen, 'Add Download must accept session health report')
require('visibleSessionHealth' in screen and 'BrowserSessionHealthCard' in screen, 'Add Download session health card missing')
require('Browser session health' in screen, 'Browser Session Health visible card title missing')
require('Private browser values are never shown here' in screen, 'privacy copy missing from Add Download')
require('XdmMetric("Sign-in", health.protectedRequestLabel)' in screen, 'sign-in status metric missing')
for forbidden in ['Text("Cookie', 'Text("Authorization', 'Text("Bearer', 'ReviewSummaryRow("Cookie', 'ReviewSummaryRow("Authorization', 'ReviewSummaryRow("requestHeaders']:
    require(forbidden not in screen, f'normal UI must not render {forbidden}')

require('BrowserSessionHealthPlanner.evaluate(state.externalAddDraft)' in app, 'XdmApp must wire health planner into Add Download')
require('Phase52BrowserSessionHealthContractTest' in contract, 'Phase52 contract test missing')
require('addDownloadShowsSessionHealthWithoutSecretLabels' in contract, 'Phase52 UI privacy contract missing')
require('sessionHealthPlannerStaysModelOnlyAndSchemaFree' in contract, 'Phase52 schema-free contract missing')
require('Phase 52 Browser Session Health' in changelog, 'changelog missing Phase52 entry')
require('Normal UI must not show' in doc and 'No Room migration' in doc, 'Phase52 doc privacy/schema boundary missing')

try:
    manifest = json.loads(manifest_text)
    require(52 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 52')
    p52 = manifest.get('field_bugfix_phase_52', {})
    require(p52.get('room_schema_unchanged') == 14, 'Phase52 must keep Room schema 14')
    require(p52.get('top_level_route_added') is False, 'Phase52 must not add a top-level route')
    require(p52.get('debug_workbench_reopened') is False, 'Phase52 must not reopen Debug Workbench')
    privacy = p52.get('browser_session_health', {})
    require(privacy.get('shows_cookie_values') is False, 'Phase52 must not show cookie values')
    require(privacy.get('shows_authorization_values') is False, 'Phase52 must not show authorization values')
    require(privacy.get('shows_raw_urls') is False, 'Phase52 must not show raw URLs')
    require(privacy.get('shows_raw_header_names_in_normal_ui') is False, 'Phase52 must not show raw header names in normal UI')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 52 browser session health validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 52 browser session health validator passed')
