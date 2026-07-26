#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v adb >/dev/null 2>&1 || ! adb get-state >/dev/null 2>&1; then
  echo "An Android device or emulator is required for UIX device smoke tests." >&2
  exit 2
fi

./gradlew --stacktrace connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mikeyphw.xdm.android.UixR6AdaptiveLayoutTest,com.mikeyphw.xdm.android.AppSmokeTest
