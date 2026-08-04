#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
APK="${1:?release APK path required}"
AAB="${2:?release AAB path required}"
APKS="${3:-}"
OUT="build/release/publication"
mkdir -p "$OUT"
cp "$APK" "$OUT/"
cp "$AAB" "$OUT/"
if [[ -n "$APKS" ]]; then cp "$APKS" "$OUT/"; fi
( cd "$OUT" && sha256sum "$(basename "$APK")" "$(basename "$AAB")" ${APKS:+"$(basename "$APKS")"} > SHA256SUMS )
( cd "$OUT" && sha512sum "$(basename "$APK")" "$(basename "$AAB")" ${APKS:+"$(basename "$APKS")"} > SHA512SUMS )
find app/build/outputs/mapping app/build/intermediates/merged_native_libs app/build/intermediates/stripped_native_libs -type f \( -name 'mapping.txt' -o -name '*.so' \) -print0 2>/dev/null | while IFS= read -r -d '' item; do
  mkdir -p "$OUT/auxiliary/$(dirname "$item")"
  cp "$item" "$OUT/auxiliary/$item"
done
python3 - "$OUT" "$APK" "$AAB" "$APKS" <<'PY'
from pathlib import Path
import hashlib,json,os,sys,time
out=Path(sys.argv[1]); apk=Path(sys.argv[2]); aab=Path(sys.argv[3]); apks=Path(sys.argv[4]) if len(sys.argv)>4 and sys.argv[4] else None
def h(p, algo):
    d=getattr(hashlib, algo)()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''): d.update(b)
    return d.hexdigest()
manifest={
 'schemaVersion':1,
 'releaseGate':'bug-hunt-phase10',
 'versionName':'0.21.0',
 'versionCode':22,
 'buildId':os.environ.get('XDM_RELEASE_BUILD_ID') or os.environ.get('GITHUB_SHA','local-dev')[:12],
 'signerSha256':os.environ.get('XDM_RELEASE_SIGNER_SHA256',''),
 'artifacts':[{'path':apk.name,'sha256':h(apk,'sha256'),'sha512':h(apk,'sha512')},{'path':aab.name,'sha256':h(aab,'sha256'),'sha512':h(aab,'sha512')}] + ([] if apks is None else [{'path':apks.name,'sha256':h(apks,'sha256'),'sha512':h(apks,'sha512')}]),
 'publicationRequires':['signed APK','signed AAB','APK-set split verification','16 KB native alignment','SBOM','provenance attestation','upgrade reboot smoke','backup and D2D exclusion smoke'],
 'sbom':'phase10-sbom.json',
 'provenance':'phase10-provenance.json',
}
(out/'release-metadata.json').write_text(json.dumps(manifest,indent=2)+'\n')
(out/'phase10-sbom.json').write_text(json.dumps({'schemaVersion':1,'bomFormat':'XDM-Phase10-minimal','artifacts':manifest['artifacts']},indent=2)+'\n')
(out/'phase10-provenance.json').write_text(json.dumps({'schemaVersion':1,'buildId':manifest['buildId'],'source':'git','commit':os.environ.get('GITHUB_SHA') or os.environ.get('XDM_RELEASE_BUILD_ID','local-dev'),'attestationRequired':True},indent=2)+'\n')
PY
if command -v gpg >/dev/null 2>&1 && [[ -n "${XDM_RELEASE_GPG_KEY:-}" ]]; then
  gpg --batch --yes --detach-sign --armor "$OUT/SHA256SUMS"
else
  echo "GPG signing skipped; provide XDM_RELEASE_GPG_KEY in CI to attest checksum files" > "$OUT/CHECKSUM_ATTESTATION_REQUIRED.txt"
fi
echo "Publication bundle: $OUT"
