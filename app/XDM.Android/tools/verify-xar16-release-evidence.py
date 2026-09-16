#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, hashlib, time
from pathlib import Path

REQUIRED = [
    'xar16-same-run-artifacts.json',
    'phase10-release-attestation.json',
    'xar16-device-matrix.json',
    'xar16-signed-release-journeys.json',
    'aria2-release-apk.txt',
    'ffmpeg-release-apk.txt',
    'artifacts.sha256',
    'rm04-validation-seal.json',
]

def sha256(p: Path) -> str:
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''):
            h.update(b)
    return h.hexdigest()

def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except Exception as exc:
        raise SystemExit(f'invalid JSON evidence {path}: {exc}')

def main() -> None:
    ap=argparse.ArgumentParser()
    ap.add_argument('--evidence-dir', type=Path, required=True)
    ap.add_argument('--require-publication-ready', action='store_true')
    ap.add_argument('--out', type=Path)
    args=ap.parse_args()
    d=args.evidence_dir
    if not d.is_dir(): raise SystemExit(f'evidence dir missing: {d}')
    missing=[name for name in REQUIRED if not (d/name).is_file() or not (d/name).read_text(errors='ignore').strip()]
    if missing: raise SystemExit('missing required XAR16 evidence: '+', '.join(missing))
    same=load_json(d/'xar16-same-run-artifacts.json')
    phase=load_json(d/'phase10-release-attestation.json')
    device=load_json(d/'xar16-device-matrix.json')
    journeys=load_json(d/'xar16-signed-release-journeys.json')
    rm04=load_json(d/'rm04-validation-seal.json')
    artifacts={item['kind']: item for item in same.get('artifacts', [])}
    for kind in ['apk','aab','apks']:
        if kind not in artifacts: raise SystemExit(f'same-run evidence missing {kind}')
        p=Path(artifacts[kind]['path'])
        if not p.is_file(): raise SystemExit(f'artifact path missing for {kind}: {p}')
        if sha256(p) != artifacts[kind]['sha256']: raise SystemExit(f'artifact hash mismatch for {kind}')
    if phase.get('apkSet',{}).get('splitSemantics') != 'set-level-required-inventory':
        raise SystemExit('APK-set attestation did not verify set-level split semantics')
    if not device.get('apkSetInstalled') or not device.get('upgradeRebootLaunchVerified'):
        raise SystemExit('device matrix does not prove APK-set install and upgrade/reboot/launch')
    if not journeys.get('signedReleaseJourneysPassed'):
        raise SystemExit('signed-release journeys did not pass')
    if rm04.get('roadmapOverlay') != 'RM04' or rm04.get('runId') != same.get('runId'):
        raise SystemExit('RM04 validation evidence is not bound to the same XAR16 run')
    if not rm04.get('canonicalNonDeviceValidationPassed'):
        raise SystemExit('RM04 canonical non-device validation did not pass')
    for key in ['runtimeDiagnosticsPrivacyContract','finalDiagnosticsZipPrivacyIntegrity','staticValidatorChain','releaseDocsValidator','routeTopologyValidator','fullSelectedTaskValidation']:
        if not rm04.get(key):
            raise SystemExit(f'RM04 validation evidence missing {key}')
    log = rm04.get('finalCommonValidationLog') or {}
    log_path = Path(log.get('path',''))
    if not log_path.is_file() or sha256(log_path) != log.get('sha256'):
        raise SystemExit('RM04 final-common validation log hash mismatch')
    report={
        'schemaVersion':1,
        'roadmapOverlay':'XAR16',
        'generatedAtEpochMs':int(time.time()*1000),
        'publicationReady':True,
        'evidenceDir':str(d),
        'artifacts':same.get('artifacts', []),
        'phase10Attestation':str(d/'phase10-release-attestation.json'),
        'deviceMatrix':str(d/'xar16-device-matrix.json'),
        'journeys':str(d/'xar16-signed-release-journeys.json'),
        'rm04ValidationSeal':str(d/'rm04-validation-seal.json'),
    }
    if args.require_publication_ready and not report['publicationReady']:
        raise SystemExit('publication evidence is not ready')
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print('XAR16 release evidence verified')

if __name__ == '__main__':
    main()
