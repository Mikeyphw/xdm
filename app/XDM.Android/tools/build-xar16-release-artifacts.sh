#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
variant="${1:-release}"
[[ "$variant" == "release" ]] || { echo "usage: $0 [release]" >&2; exit 64; }
: "${XDM_RELEASE_STORE_FILE:?release keystore path required}"
: "${XDM_RELEASE_STORE_PASSWORD:?release store password required}"
: "${XDM_RELEASE_KEY_ALIAS:?release key alias required}"
: "${XDM_RELEASE_KEY_PASSWORD:?release key password required}"
: "${XDM_RELEASE_SIGNER_SHA256:?pinned release signer SHA-256 required}"
: "${XDM_RELEASE_CERTIFICATE_NOT_AFTER:?release certificate expiry ISO/date required}"
: "${XDM_ARIA2_ARCHIVE_SHA256:?trusted aria2 archive SHA-256 required}"
if [[ -z "${BUNDLETOOL_JAR:-}" ]]; then
  BUNDLETOOL_JAR="$(python3 tools/ensure-bundletool.py --print-path)"
  export BUNDLETOOL_JAR
else
  python3 tools/ensure-bundletool.py --path "$BUNDLETOOL_JAR" --verify-only
fi
RUN_ID="${XDM_XAR16_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short=12 HEAD 2>/dev/null || echo local)}"
export XDM_XAR16_RUN_ID="$RUN_ID"
EVIDENCE_DIR="build/xar16/evidence/$RUN_ID"
mkdir -p "$EVIDENCE_DIR"
python3 tools/install-aria2-runtime.py --download-official --expected-archive-sha256 "$XDM_ARIA2_ARCHIVE_SHA256" --require-trusted-digest
python3 tools/verify-aria2-runtime.py --require-payload --require-16kb-alignment --require-trusted-archive-digest --expected-archive-sha256 "$XDM_ARIA2_ARCHIVE_SHA256" | tee "$EVIDENCE_DIR/aria2-runtime.txt"
python3 tools/install-ffmpeg-runtime.py --build-pinned
python3 tools/verify-ffmpeg-runtime.py --require-payload --require-16kb-alignment | tee "$EVIDENCE_DIR/ffmpeg-runtime.txt"
bash tools/run-final-common-validation.sh | tee "$EVIDENCE_DIR/final-common-validation.txt"
python3 tools/write-rm04-validation-evidence.py \
  --final-common-log "$EVIDENCE_DIR/final-common-validation.txt" \
  --run-id "$RUN_ID" \
  --out "$EVIDENCE_DIR/rm04-validation-seal.json"
./gradlew -Pxdm.requireAria2Runtime=true -Pxdm.requireFfmpegRuntime=true \
  -Pxdm.validation.staticPassed=true -Pxdm.validation.fullPassed=true -Pxdm.validation.realDeviceSmokePassed=false \
  -Pxdm.validation.aria2PayloadVerified=true -Pxdm.validation.ffmpegPayloadVerified=true \
  -Pxdm.validation.diagnosticExportPassed=true -Pxdm.validation.releaseDocsPassed=true \
  -Pxdm.validation.routeTopologyPassed=true -Pxdm.validation.lintPassed=true -Pxdm.validation.nativeSymbolsPassed=true \
  -Pxdm.validation.apkSetInstalled=false -Pxdm.validation.publicationEvidenceVerified=false -Pxdm.validation.signedReleaseJourneysPassed=false \
  :browser-extension:validateFirefoxExtension :browser-extension:packageFirefoxExtensionDark \
  :browser-extension:packageFirefoxExtensionAmoled :browser-extension:verifyFirefoxExtensionReleaseArtifacts \
  :app:xdmAssertReleaseSigningInputs lintRelease testReleaseUnitTest :app:assembleRelease :app:bundleRelease
APK="$(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' | sort | head -n1)"
AAB="$(find app/build/outputs/bundle/release -maxdepth 1 -type f -name '*.aab' | sort | head -n1)"
test -n "$APK" && test -n "$AAB"
APKS="build/outputs/apks/release/xdm-release-$RUN_ID.apks"
mkdir -p "$(dirname "$APKS")"
java -jar "$BUNDLETOOL_JAR" build-apks \
  --bundle "$AAB" \
  --output "$APKS" \
  --ks "$XDM_RELEASE_STORE_FILE" \
  --ks-pass "pass:$XDM_RELEASE_STORE_PASSWORD" \
  --ks-key-alias "$XDM_RELEASE_KEY_ALIAS" \
  --key-pass "pass:$XDM_RELEASE_KEY_PASSWORD"
python3 tools/verify-phase10-release-artifacts.py --require-16kb --bundletool-jar "$BUNDLETOOL_JAR" --inventory tools/phase10-release-inventory.json --apk "$APK" --aab "$AAB" --apks "$APKS" --out "$EVIDENCE_DIR/phase10-release-attestation.json"
python3 tools/verify-aria2-runtime.py --require-payload --require-16kb-alignment --require-trusted-archive-digest --expected-archive-sha256 "$XDM_ARIA2_ARCHIVE_SHA256" --apk "$APK" | tee "$EVIDENCE_DIR/aria2-release-apk.txt"
python3 tools/verify-ffmpeg-runtime.py --require-payload --require-16kb-alignment --apk "$APK" | tee "$EVIDENCE_DIR/ffmpeg-release-apk.txt"
sha256sum "$APK" "$AAB" "$APKS" > "$EVIDENCE_DIR/artifacts.sha256"
python3 - "$EVIDENCE_DIR" "$APK" "$AAB" "$APKS" <<'PY'
from pathlib import Path
import json, os, sys, hashlib, time
out = Path(sys.argv[1])
artifacts = [Path(p) for p in sys.argv[2:5]]
def h(p: Path) -> str:
    d = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''):
            d.update(b)
    return d.hexdigest()
manifest = {
    'schemaVersion': 1,
    'roadmapOverlay': 'XAR16',
    'runId': os.environ['XDM_XAR16_RUN_ID'],
    'buildId': os.environ.get('XDM_RELEASE_BUILD_ID') or os.environ.get('GITHUB_SHA', 'local-dev'),
    'generatedAtEpochMs': int(time.time()*1000),
    'artifacts': [{'kind': k, 'path': str(p), 'sha256': h(p)} for k, p in zip(['apk', 'aab', 'apks'], artifacts)],
    'phase10Attestation': 'phase10-release-attestation.json',
    'sameRunArtifactProvenance': True,
    'publicationAllowed': False,
}
out.joinpath('xar16-same-run-artifacts.json').write_text(json.dumps(manifest, indent=2)+'\n')
PY
echo "$EVIDENCE_DIR"
