#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ADB="${ADB:-adb}"
RUN_ID="${XDM_XAR16_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-journeys}"
EVIDENCE_DIR="${XDM_XAR16_EVIDENCE_DIR:-build/xar16/evidence/$RUN_ID}"
mkdir -p "$EVIDENCE_DIR"
serial="$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
test -n "$serial" || { echo "No connected adb device for XAR16 signed-release journeys" >&2; exit 75; }
pkg="com.mikeyphw.xdm.android"
$ADB -s "$serial" logcat -c
$ADB -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 | tee "$EVIDENCE_DIR/journey-launch.txt"
$ADB -s "$serial" shell am start -W -a android.intent.action.VIEW -d 'xdmdownload://add?v=1&url=https%3A%2F%2Fexample.com%2Ffile.bin&filename=file.bin' "$pkg" | tee "$EVIDENCE_DIR/journey-external-add.txt"
$ADB -s "$serial" shell am start -W -a android.intent.action.VIEW -d 'xdmdownload://capture?v=3&url=https%3A%2F%2Fexample.com%2Fstream.m3u8&type=hls&title=Example' "$pkg" | tee "$EVIDENCE_DIR/journey-media-capture.txt"
$ADB -s "$serial" shell pidof "$pkg" | tee "$EVIDENCE_DIR/journey-process.txt"
$ADB -s "$serial" logcat -d -b crash | tee "$EVIDENCE_DIR/journey-crashes.txt"
python3 - "$EVIDENCE_DIR" "$serial" <<'PY'
from pathlib import Path
import json, sys, time
out=Path(sys.argv[1]); serial=sys.argv[2]
files=['journey-launch.txt','journey-external-add.txt','journey-media-capture.txt','journey-process.txt','journey-crashes.txt']
missing=[f for f in files if not out.joinpath(f).is_file() or not out.joinpath(f).read_text(errors='ignore').strip()]
if missing:
    raise SystemExit('missing journey output: '+', '.join(missing))
for name in ('journey-external-add.txt', 'journey-media-capture.txt'):
    text=out.joinpath(name).read_text(errors='ignore')
    if 'Status: ok' not in text:
        raise SystemExit(f'{name} did not report a successful Activity launch')
if 'FATAL EXCEPTION' in out.joinpath('journey-crashes.txt').read_text(errors='ignore'):
    raise SystemExit('signed-release journey produced a fatal Android crash')
out.joinpath('xar16-signed-release-journeys.json').write_text(json.dumps({
    'schemaVersion':1,
    'roadmapOverlay':'XAR16',
    'serial':serial,
    'generatedAtEpochMs':int(time.time()*1000),
    'journeys':['launch','external-add-intake','media-capture-handoff'],
    'signedReleaseJourneysPassed':True,
    'evidenceFiles':files,
}, indent=2)+'\n')
PY
