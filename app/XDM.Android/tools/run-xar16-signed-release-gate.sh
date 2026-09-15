#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ci="${1:-}"
python3 tools/validate-xar16-release-evidence-seal.py
export XDM_XAR16_IN_PROGRESS=1
EVIDENCE_DIR="$(bash tools/build-xar16-release-artifacts.sh release | tail -n1)"
export XDM_XAR16_EVIDENCE_DIR="$EVIDENCE_DIR"
export XDM_XAR16_CURRENT_APK="$(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' | sort | head -n1)"
export XDM_XAR16_CURRENT_APKS="$(find build/outputs/apks/release -maxdepth 1 -type f -name '*.apks' | sort | tail -n1)"
bash tools/run-xar16-install-upgrade-matrix.sh
bash tools/run-xar16-signed-release-journeys.sh
bash tools/generate-xar16-publication-bundle.sh >/tmp/xdm-xar16-publication-path.txt
./gradlew -Pxdm.requireAria2Runtime=true -Pxdm.requireFfmpegRuntime=true \
  -Pxdm.validation.staticPassed=true -Pxdm.validation.fullPassed=true -Pxdm.validation.realDeviceSmokePassed=true \
  -Pxdm.validation.aria2PayloadVerified=true -Pxdm.validation.ffmpegPayloadVerified=true \
  -Pxdm.validation.diagnosticExportPassed=true -Pxdm.validation.releaseDocsPassed=true \
  -Pxdm.validation.routeTopologyPassed=true -Pxdm.validation.lintPassed=true -Pxdm.validation.nativeSymbolsPassed=true \
  -Pxdm.validation.apkSetInstalled=true -Pxdm.validation.publicationEvidenceVerified=true -Pxdm.validation.signedReleaseJourneysPassed=true \
  :app:verifyXar16ReleaseEvidenceSeal :app:xdmAssertReleaseSigningInputs :app:assembleRelease :app:bundleRelease
pub="$(cat /tmp/xdm-xar16-publication-path.txt)"
echo "XAR16 signed release evidence seal passed: $pub"
