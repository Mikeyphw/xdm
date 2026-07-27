#!/usr/bin/env bash
set -euo pipefail

MODE="print"
PACKAGE="com.mikeyphw.xdm.android"
SCHEME="xdmdownload"
MEDIA_URL="https://example.com/master.m3u8"
PAGE_URL="https://example.com/watch"
ADB_BIN="${ADB:-adb}"

usage() {
  cat <<'EOF'
Usage: run-browser-bridge-device-acceptance.sh [options]

Options:
  --print                  Print the device acceptance plan without using adb (default).
  --adb                    Run the automatable Android resolver and launch checks.
  --package ID             Installed XDM package id.
  --scheme SCHEME          XDM scheme registered by that package.
  --media-url URL          Public test media URL without credentials.
  --page-url URL           Public page URL without credentials.
  --adb-bin PATH           adb executable.
  -h, --help               Show this help.

Known variants:
  release  com.mikeyphw.xdm.android        xdmdownload
  beta     com.mikeyphw.xdm.android.beta   xdmdownload-beta
  debug    com.mikeyphw.xdm.android.debug  xdmdownload-debug
EOF
}

while (($#)); do
  case "$1" in
    --print) MODE="print"; shift ;;
    --adb) MODE="adb"; shift ;;
    --package) PACKAGE="${2:?missing package}"; shift 2 ;;
    --scheme) SCHEME="${2:?missing scheme}"; shift 2 ;;
    --media-url) MEDIA_URL="${2:?missing media URL}"; shift 2 ;;
    --page-url) PAGE_URL="${2:?missing page URL}"; shift 2 ;;
    --adb-bin) ADB_BIN="${2:?missing adb path}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

python3 - "$MEDIA_URL" "$PAGE_URL" <<'PY'
import sys
from urllib.parse import urlsplit
for label, value in (("media", sys.argv[1]), ("page", sys.argv[2])):
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https", "ftp"} or not parsed.hostname or parsed.username or parsed.password:
        raise SystemExit(f"Unsafe {label} fixture URL: {value}")
    lowered = value.lower()
    if any(key in lowered for key in ("authorization=", "cookie=", "password=", "credential=", "token=", "signature=", "sig=")):
        raise SystemExit(f"Credential-bearing {label} fixture URL is not allowed")
PY

encoded_media="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$MEDIA_URL")"
encoded_page="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$PAGE_URL")"
capture_uri="${SCHEME}://capture?v=1&url=${encoded_media}&page=${encoded_page}&title=Device%20acceptance&mime=application%2Fvnd.apple.mpegurl&kind=hls"
add_uri="${SCHEME}://add?v=1&url=${encoded_media}&page=${encoded_page}&title=Device%20acceptance"

cat <<EOF
XDM Android browser bridge device acceptance
Package: $PACKAGE
Scheme:  $SCHEME

Automated Android checks:
  1. Package is installed.
  2. VIEW+BROWSABLE resolves to $PACKAGE/.ExternalAddDownloadActivity.
  3. capture and add intents start successfully.

Manual IronFox checks:
  4. Install the generated release XPI and reload the test page.
  5. Direct MP4, HLS, DASH, blob/MediaSource, and cross-origin iframe playback produce the themed FAB.
  6. XDM opens Media review, 1DM+ opens its downloader, and Ask exposes both targets.
  7. Repeated taps do not create duplicate work.
  8. Switching XDM between Dark and AMOLED marks Follow app exports stale and regeneration replaces the XPI.
  9. Revoking the SAF folder produces the specific recovery state.
 10. Release and beta installed together resolve only their own schemes.
 11. URI, logs, diagnostics, screenshots, and exported metadata contain no raw cookies or authorization values.
EOF

if [[ "$MODE" == "print" ]]; then
  cat <<EOF

Resolver command:
  $ADB_BIN shell cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '$capture_uri'

Capture launch:
  $ADB_BIN shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '$capture_uri'

Add launch:
  $ADB_BIN shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '$add_uri'
EOF
  exit 0
fi

command -v "$ADB_BIN" >/dev/null
"$ADB_BIN" get-state >/dev/null
"$ADB_BIN" shell pm path "$PACKAGE" | grep -q '^package:'
resolver="$($ADB_BIN shell cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "$capture_uri" | tr -d '\r')"
printf '\nResolver: %s\n' "$resolver"
case "$resolver" in
  "$PACKAGE"/*ExternalAddDownloadActivity*) ;;
  *) echo "Unexpected resolver for $SCHEME: $resolver" >&2; exit 1 ;;
esac
"$ADB_BIN" shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "$capture_uri"
"$ADB_BIN" shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "$add_uri"
printf '\nAutomated Android acceptance passed. Complete the manual IronFox checklist above.\n'
