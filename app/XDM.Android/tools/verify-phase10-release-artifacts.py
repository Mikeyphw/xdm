#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'
HEX64 = re.compile(r'^[0-9a-fA-F]{64}$')
SENSITIVE_NAMES = {'cookie', 'authorization', 'bearer', 'token', 'signature', 'session', 'password', 'secret'}
DEBUG_DENY = ('/debug/', 'androidTest', 'testOnly', 'debuggable="true"', 'testOnly="true"', 'debug.keystore', '.devtool', 'fixtures/', 'mockwebserver', 'source-marker')

def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024), b''):
            h.update(chunk)
    return h.hexdigest()

def command_available(name: str) -> bool:
    from shutil import which
    return which(name) is not None

def run(cmd: list[str]) -> str:
    proc=subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        raise SystemExit(f"command failed ({proc.returncode}): {' '.join(cmd)}\n{proc.stdout}")
    return proc.stdout


def tool_path(candidates: list[str]) -> str | None:
    for name in candidates:
        found = shutil.which(name)
        if found:
            return found
    android_home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if android_home:
        root = Path(android_home)
        for name in candidates:
            for p in sorted((root/'build-tools').glob(f'*/{name}'), reverse=True):
                if p.is_file():
                    return str(p)
    return None

def inspect_apk_manifest(apk: Path, expected_version_name: str, expected_version_code: int) -> dict:
    inspector = tool_path(['aapt2','aapt'])
    if not inspector:
        raise SystemExit('aapt2 or aapt is required for APK manifest inspection')
    out = run([inspector, 'dump', 'badging', str(apk)])
    package_line = next((line for line in out.splitlines() if line.startswith('package:')), '')
    if f"versionName='{expected_version_name}'" not in package_line and f'versionName="{expected_version_name}"' not in package_line:
        raise SystemExit(f'{apk} manifest versionName does not match {expected_version_name}')
    if f"versionCode='{expected_version_code}'" not in package_line and f'versionCode="{expected_version_code}"' not in package_line:
        raise SystemExit(f'{apk} manifest versionCode does not match {expected_version_code}')
    if 'application-debuggable' in out or 'testOnly' in out:
        raise SystemExit(f'{apk} manifest exposes debug/test-only release flags')
    native_line = next((line for line in out.splitlines() if line.startswith('native-code:')), '')
    return {'packageLine': package_line, 'nativeCode': native_line}

def load_inventory(path: Path | None) -> dict:
    if not path:
        path = ROOT/'tools/phase10-release-inventory.json'
    if not path.is_file():
        raise SystemExit(f'release inventory missing: {path}')
    return json.loads(path.read_text(encoding='utf-8'))

def verify_aab_signature(aab: Path, signer_sha256: str | None) -> None:
    if not command_available('jarsigner'):
        raise SystemExit('jarsigner is required to verify signed Android App Bundles')
    out = run(['jarsigner','-verify','-certs','-verbose',str(aab)])
    if 'jar verified.' not in out.lower():
        raise SystemExit(f'jarsigner did not verify {aab}')
    if signer_sha256 and signer_sha256.lower() not in out.lower().replace(':',''):
        raise SystemExit(f'{aab} signer SHA-256 does not match pinned value')

def verify_bundletool_config(aab: Path, bundletool_jar: str | None, require_16kb: bool) -> str:
    if not require_16kb:
        return ''
    if bundletool_jar:
        out = run(['java','-jar',bundletool_jar,'dump','config','--bundle',str(aab)])
    elif command_available('bundletool'):
        out = run(['bundletool','dump','config','--bundle',str(aab)])
    else:
        raise SystemExit('bundletool or BUNDLETOOL_JAR is required to verify AAB page-size config')
    if 'PAGE_ALIGNMENT_16K' not in out:
        raise SystemExit('AAB bundletool config does not report PAGE_ALIGNMENT_16K')
    return out

def verify_apk(apk: Path, signer_sha256: str|None, require_16kb: bool, inventory: dict) -> dict:
    if not apk.is_file():
        raise SystemExit(f'APK missing: {apk}')
    if command_available('apksigner'):
        out=run(['apksigner','verify','--verbose','--print-certs',str(apk)])
        if 'DOES NOT VERIFY' in out or 'Verified using v' not in out:
            raise SystemExit(f'apksigner did not verify {apk}')
        if signer_sha256 and signer_sha256.lower() not in out.lower().replace(':',''):
            raise SystemExit(f'{apk} signer SHA-256 does not match pinned value')
    else:
        raise SystemExit('apksigner is required for release artifact verification')
    manifest_info = inspect_apk_manifest(apk, inventory.get('versionName','0.21.0'), int(inventory.get('versionCode',22)))
    entries=[]
    seen=set()
    with zipfile.ZipFile(apk) as z:
        names=z.namelist()
        for required in inventory.get('requiredApkEntries', []):
            if required not in names:
                raise SystemExit(f'release APK missing required inventory entry: {required}')
        supported=set(inventory.get('supportedAbis', []))
        native_abis={n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so') and len(n.split('/')) >= 3}
        if native_abis - supported:
            raise SystemExit(f'release APK contains unsupported native ABI(s): {sorted(native_abis-supported)}')
        if supported and not native_abis.issubset(supported):
            raise SystemExit('release APK native ABI set does not match supported inventory')
        for name in names:
            low=name.lower()
            if any(marker.lower() in low for marker in DEBUG_DENY) or any(marker.lower() in low for marker in inventory.get('forbiddenNameFragments', [])):
                raise SystemExit(f'forbidden release APK payload: {name}')
            if name.endswith('.so'):
                info=z.getinfo(name)
                # zip local header data offset: header_offset + fixed header + file name + extra field
                with apk.open('rb') as fh:
                    fh.seek(info.header_offset)
                    hdr=fh.read(30)
                    name_len=int.from_bytes(hdr[26:28],'little')
                    extra_len=int.from_bytes(hdr[28:30],'little')
                    data_offset=info.header_offset+30+name_len+extra_len
                if require_16kb and data_offset % 16384 != 0:
                    raise SystemExit(f'{name} is not 16 KB zip-aligned in {apk}')
            entries.append({'name':name,'size':z.getinfo(name).file_size})
    return {'path':str(apk),'sha256':sha256(apk),'entries':len(entries),'manifest':manifest_info}

def verify_aab(aab: Path, require_16kb: bool, signer_sha256: str|None, bundletool_jar: str|None, inventory: dict) -> dict:
    if not aab.is_file():
        raise SystemExit(f'AAB missing: {aab}')
    verify_aab_signature(aab, signer_sha256)
    bundletool_config = verify_bundletool_config(aab, bundletool_jar, require_16kb)
    with zipfile.ZipFile(aab) as z:
        names=z.namelist()
        if 'BundleConfig.pb' not in names:
            raise SystemExit('AAB missing BundleConfig.pb')
        if require_16kb and not any('lib/' in n and n.endswith('.so') for n in names):
            # native-free AAB is okay; XDM currently carries aria2 in strict release builds, so this catches missing runtime.
            raise SystemExit('strict release AAB expected at least one native runtime library')
        for n in names:
            low=n.lower()
            if any(marker.lower() in low for marker in DEBUG_DENY) or any(marker.lower() in low for marker in inventory.get('forbiddenNameFragments', [])):
                raise SystemExit(f'forbidden release AAB payload: {n}')
    return {'path':str(aab),'sha256':sha256(aab),'bundletoolConfigVerified': bool(bundletool_config)}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--apk', action='append', type=Path, default=[])
    ap.add_argument('--aab', type=Path)
    ap.add_argument('--apks', type=Path)
    ap.add_argument('--signer-sha256', default=os.environ.get('XDM_RELEASE_SIGNER_SHA256'))
    ap.add_argument('--require-16kb', action='store_true')
    ap.add_argument('--bundletool-jar', default=os.environ.get('BUNDLETOOL_JAR'))
    ap.add_argument('--inventory', type=Path, default=ROOT/'tools/phase10-release-inventory.json')
    ap.add_argument('--out', type=Path, default=ROOT/'build/release/phase10-release-attestation.json')
    args=ap.parse_args()
    if args.signer_sha256 and not HEX64.fullmatch(args.signer_sha256):
        raise SystemExit('signer SHA-256 must be 64 hex characters')
    inventory=load_inventory(args.inventory)
    result={'schemaVersion':1,'releaseGate':'bug-hunt-phase10','inventory':inventory,'apks':[]}
    for apk in args.apk:
        result['apks'].append(verify_apk(apk,args.signer_sha256,args.require_16kb,inventory))
    if args.aab:
        result['aab']=verify_aab(args.aab,args.require_16kb,args.signer_sha256,args.bundletool_jar,inventory)
    if args.apks:
        if not args.apks.is_file(): raise SystemExit(f'APK set missing: {args.apks}')
        split_results=[]
        with tempfile.TemporaryDirectory() as tmpdir:
            with zipfile.ZipFile(args.apks) as apks_zip:
                apk_members=[name for name in apks_zip.namelist() if name.endswith('.apk')]
                if not apk_members:
                    raise SystemExit('APK set contains no generated APKs')
                for member in apk_members:
                    target=Path(tmpdir)/Path(member).name
                    target.write_bytes(apks_zip.read(member))
                    split_results.append(verify_apk(target,args.signer_sha256,args.require_16kb,inventory))
        result['apkSet']={'path':str(args.apks),'sha256':sha256(args.apks),'splitApksVerified':len(split_results)}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(result,indent=2)+"\n",encoding='utf-8')
    print(f'Phase 10 release artifact attestation written: {args.out}')
if __name__=='__main__': main()
