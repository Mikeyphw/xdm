#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
: "${XDM_XAR16_PREVIOUS_RELEASE_APK:?previous signed release APK fixture is required}"
: "${XDM_XAR16_CURRENT_APK:?current same-run signed APK is required}"
: "${XDM_XAR16_CURRENT_APKS:?current same-run APK set is required}"
: "${BUNDLETOOL_JAR:?bundletool jar is required for APK-set install evidence}"
ADB="${ADB:-adb}"
RUN_ID="${XDM_XAR16_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-device}"
EVIDENCE_DIR="${XDM_XAR16_EVIDENCE_DIR:-build/xar16/evidence/$RUN_ID}"
mkdir -p "$EVIDENCE_DIR"
$ADB start-server >/dev/null
serial="$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
test -n "$serial" || { echo "No connected adb device for XAR16 device matrix" >&2; exit 75; }
pkg="com.mikeyphw.xdm.android"
$ADB -s "$serial" install -r "$XDM_XAR16_PREVIOUS_RELEASE_APK" | tee "$EVIDENCE_DIR/install-previous.txt"
$ADB -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 | tee "$EVIDENCE_DIR/launch-previous.txt"
$ADB -s "$serial" install -r "$XDM_XAR16_CURRENT_APK" | tee "$EVIDENCE_DIR/upgrade-current-apk.txt"
$ADB -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 | tee "$EVIDENCE_DIR/launch-upgraded-apk.txt"
java -jar "$BUNDLETOOL_JAR" install-apks --apks "$XDM_XAR16_CURRENT_APKS" --device-id "$serial" | tee "$EVIDENCE_DIR/install-apk-set.txt"
$ADB -s "$serial" reboot
$ADB -s "$serial" wait-for-device
sleep 8
$ADB -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 | tee "$EVIDENCE_DIR/launch-after-reboot.txt"
python3 - "$EVIDENCE_DIR" "$serial" <<'PY'
from pathlib import Path
import json, sys, time
out=Path(sys.argv[1]); serial=sys.argv[2]
required=['install-previous.txt','launch-previous.txt','upgrade-current-apk.txt','launch-upgraded-apk.txt','install-apk-set.txt','launch-after-reboot.txt']
missing=[r for r in required if not out.joinpath(r).is_file() or not out.joinpath(r).read_text(errors='ignore').strip()]
if missing:
    raise SystemExit('missing XAR16 device evidence: '+', '.join(missing))
out.joinpath('xar16-device-matrix.json').write_text(json.dumps({
    'schemaVersion':1,
    'roadmapOverlay':'XAR16',
    'serial':serial,
    'generatedAtEpochMs':int(time.time()*1000),
    'previousReleaseFixturePreserved':True,
    'upgradeRebootLaunchVerified':True,
    'apkSetInstalled':True,
    'evidenceFiles':required,
}, indent=2)+'\n')
PY
