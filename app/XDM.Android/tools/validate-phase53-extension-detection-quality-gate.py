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

core = read('app/XDM.Android/browser-extension/src/main/extension/xdm-firefox/detector-core.js')
observer = read('app/XDM.Android/browser-extension/src/main/extension/xdm-firefox/network-observer.js')
store = read('app/XDM.Android/browser-extension/src/main/extension/xdm-firefox/candidate-store.js')
frame = read('app/XDM.Android/browser-extension/src/main/extension/xdm-firefox/frame-bridge.js')
popup = read('app/XDM.Android/browser-extension/src/main/extension/xdm-firefox/popup.html')
popup_js = read('app/XDM.Android/browser-extension/src/main/extension/xdm-firefox/popup.js')
test = read('app/XDM.Android/browser-extension/tests/test_detector.js')
contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase53ExtensionDetectionQualityGateContractTest.kt')
phase52_contract = read('app/XDM.Android/app/src/test/kotlin/com/mikeyphw/xdm/android/Phase52BrowserSessionHealthContractTest.kt')
manifest_text = read('app/XDM.Android/PROJECT_MANIFEST.json')
changelog = read('CHANGELOG.md')
doc = read('app/XDM.Android/docs/architecture/PHASE-53-EXTENSION-DETECTION-QUALITY-GATE.md')

require('QUALITY_STRONG' in core and 'QUALITY_POSSIBLE' in core and 'QUALITY_REJECTED' in core, 'detector quality buckets missing')
require('function makeAccepted' in core and 'function makeRejected' in core, 'detector accept/reject helpers missing')
require('function hasMediaDisposition' in core, 'Content-Disposition media filename detection missing')
require('function hasRangeContext' in core, 'range/length context detection missing')
require('possible-octet' in core and 'possible-stream-hint' in core, 'possible-media reasons missing')
require('candidateSignal' in core, 'body candidate quality signal missing')
require('candidate.quality === QUALITY_POSSIBLE' in core, 'rank penalty for possible media missing')
require('autoOffer: bucket === QUALITY_STRONG' in core, 'possible media must not auto-offer from detector core')

require('showPossibleMediaCandidates: false' in observer, 'network observer default must hide possible media')
require('function allowsQuality' in observer, 'network observer quality gate helper missing')
require('quality !== "possible" || settings.showPossibleMediaCandidates === true' in observer, 'possible media must be behind explicit setting')
require('POSSIBLE_OFFER_THRESHOLD' in observer, 'possible media threshold missing')
require('contentDisposition: headerValue(details.responseHeaders, "content-disposition")' in observer, 'webRequest Content-Disposition must feed detector')
require('contentRange: headerValue(details.responseHeaders, "content-range")' in observer, 'webRequest Content-Range must feed detector')
require('quality: classification.quality' in observer, 'classification quality must be stored')
require('quality: payload.quality === "possible" ? "possible" : "strong"' in observer, 'diagnostics quality label missing')

require('quality: candidate.quality || previous.quality || "strong"' in store, 'candidate store must retain quality')
require('showPossibleMediaCandidates: false' in frame, 'frame bridge default must hide possible media')
require('Possible media found' in frame, 'frame bridge possible-media label missing')
require('possibleAllowed' in frame, 'frame bridge possible-media offer gate missing')

require('Show possible media after rescan' in popup, 'popup advanced toggle missing')
require('fake video offers' in popup, 'popup must explain false-positive protection')
require('showPossibleMediaCandidates: false' in popup_js, 'popup defaults must hide possible media')
require('showPossibleMediaCandidates: document.getElementById("showPossible").checked' in popup_js, 'popup save path must persist advanced toggle')
require('Possible media' in popup_js and 'High confidence' in popup_js, 'popup diagnostics must use human quality labels')

require('possibleBody.candidates.some' in test, 'detector JS test must cover possible body candidates')
require('contentDisposition: "attachment; filename=movie.mp4"' in test, 'detector JS test must cover Content-Disposition strong signal')
require('strictEqual(classified.quality, "possible")' in test, 'detector JS test must assert possible quality')
require('Phase53ExtensionDetectionQualityGateContractTest' in contract, 'Phase53 contract test missing')
require('private fun repoRoot(): File' in phase52_contract, 'Phase52 warning cleanup helper missing')
require('root.parentFile.parentFile' not in phase52_contract, 'Phase52 nullable parentFile chain still present')

require('XDM Android Phase 53 Extension Detection Quality Gate' in changelog, 'changelog missing Phase53 entry')
require('Strong media is offered by default' in doc, 'Phase53 doc must describe strong default')
require('Possible media stays behind an explicit advanced toggle' in doc, 'Phase53 doc must describe advanced possible-media gate')
require('No cookies, Authorization values, bearer tokens, or full URLs are added to normal UI' in doc, 'Phase53 doc privacy line missing')

for text_name, text in [('popup', popup), ('popup_js', popup_js), ('frame', frame)]:
    for secret in ['Cookie value', 'Authorization value', 'Bearer token']:
        require(secret not in text, f'{text_name} must not render {secret}')

try:
    manifest = json.loads(manifest_text)
    require(53 in manifest.get('project', {}).get('implemented_phases', []), 'implemented phases must include 53')
    p53 = manifest.get('field_bugfix_phase_53', {})
    require(p53.get('room_schema_unchanged') == 14, 'Phase53 must keep Room schema 14')
    require(p53.get('top_level_route_added') is False, 'Phase53 must not add a top-level route')
    require(p53.get('debug_workbench_reopened') is False, 'Phase53 must not reopen Debug Workbench')
    gate = p53.get('detector_quality_gate', {})
    require(gate.get('strong_media_default') is True, 'Phase53 strong default manifest flag missing')
    require(gate.get('possible_media_advanced_toggle') is True, 'Phase53 possible-media toggle manifest flag missing')
    require(gate.get('generic_json_url_src_rejected') is True, 'Phase53 generic JSON rejection manifest flag missing')
    privacy = p53.get('privacy', {})
    require(privacy.get('cookies_or_authorization_in_normal_ui') is False, 'Phase53 privacy must forbid cookie/auth UI')
    require(privacy.get('automatic_upload') is False, 'Phase53 privacy must forbid automatic upload')
except Exception as exc:
    errors.append(f'manifest parse failed: {exc}')

if errors:
    print('Phase 53 extension detection quality gate validator failed:')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)
print('Phase 53 extension detection quality gate validator passed')
