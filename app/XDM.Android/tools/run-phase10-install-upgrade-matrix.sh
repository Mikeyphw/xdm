#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PREVIOUS_APK="${1:?previous signed APK required}"
CANDIDATE_APK="${2:?candidate signed APK required}"
PKG="com.mikeyphw.xdm.android"
adb devices | grep -qE '\t(device|recovery)$'
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$CANDIDATE_APK"
adb shell cmd package resolve-activity "$PKG" >/dev/null
adb uninstall "$PKG" >/dev/null 2>&1 || true
# Previous-to-candidate upgrade path. The previous build must carry retained downloads, queues, settings, SAF grants, MediaStore links, captures, interrupted work, and recovery records from the tester fixture before this script runs.
adb install -r "$PREVIOUS_APK"
adb shell cmd package resolve-activity "$PKG" >/dev/null
adb shell am force-stop "$PKG" || true
adb install -r "$CANDIDATE_APK"
adb shell am force-stop "$PKG" || true
adb reboot
adb wait-for-device
adb shell cmd package resolve-activity "$PKG" >/dev/null
if adb install -d "$PREVIOUS_APK" 2>&1 | grep -qi 'success'; then
  echo 'downgrade unexpectedly succeeded' >&2
  exit 1
fi
echo 'Phase 10 clean install, upgrade, reboot recovery, and downgrade rejection smoke passed'
