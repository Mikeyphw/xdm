#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, zipfile
from pathlib import Path

REQUIRED_FILES={"bridge-selftest.js","candidate-store.js","detector-core.js","extension.css","fab.js","frame-bridge.js","generated-config.js","generated-theme.css","handoff.js","icons/icon48.png","icons/icon96.png","manifest.json","network-observer.js","page-sniffer.js","popup.html","popup.js"}
FIXED_ZIP_DATE=(1980,1,1,0,0,0); EXPECTED_ID="xdm-android-media-bridge@mikeyphw"; EXPECTED_SCHEME="xdmdownload"
def sha256(path:Path)->str:
    d=hashlib.sha256();
    with path.open("rb") as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b""): d.update(chunk)
    return d.hexdigest()
def inspect_xpi(path:Path, expected_theme:str, expected_version:str, expected_app_version:str)->dict[str,object]:
    if not path.is_file() or path.stat().st_size<=0: raise SystemExit(f"Missing release XPI: {path}")
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
        if names!=sorted(names): raise SystemExit(f"XPI inventory is not sorted: {path.name}")
        if set(names)!=REQUIRED_FILES: raise SystemExit(f"Unexpected XPI inventory for {path.name}: missing={sorted(REQUIRED_FILES-set(names))}, extra={sorted(set(names)-REQUIRED_FILES)}")
        for info in archive.infolist():
            if info.date_time!=FIXED_ZIP_DATE: raise SystemExit(f"Non-deterministic ZIP timestamp in {path.name}: {info.filename} {info.date_time}")
            if info.filename.startswith("/") or ".." in Path(info.filename).parts: raise SystemExit(f"Unsafe XPI entry: {info.filename}")
        manifest=json.loads(archive.read("manifest.json")); gecko=manifest.get("browser_specific_settings",{}).get("gecko",{})
        if gecko.get("id")!=EXPECTED_ID: raise SystemExit(f"Unexpected extension id in {path.name}")
        if manifest.get("version")!=expected_version: raise SystemExit(f"Unexpected extension version in {path.name}")
        config=archive.read("generated-config.js").decode("utf-8"); theme=archive.read("generated-theme.css").decode("utf-8")
        required=(f'contractVersion: 3',f'extensionVersion: "{expected_version}"',f'appVersion: "{expected_app_version}"','applicationId: "com.mikeyphw.xdm.android"',f'xdmScheme: "{EXPECTED_SCHEME}"',f'themeMode: "{expected_theme}"')
        for needle in required:
            if needle not in config: raise SystemExit(f"Generated config mismatch in {path.name}: {needle}")
        for forbidden in ("captureKeyId","capturePublicKeySpki","captureOaepHash"):
            if forbidden in config: raise SystemExit(f"Per-install capture key leaked into keyless v3 XPI: {forbidden}")
        if f"--xdm-theme-mode: {expected_theme};" not in theme: raise SystemExit(f"Generated theme mismatch in {path.name}")
    return {"file":path.name,"theme":expected_theme,"bytes":path.stat().st_size,"sha256":sha256(path)}
def main()->int:
    p=argparse.ArgumentParser(); p.add_argument("--output-dir",type=Path,required=True); p.add_argument("--metadata",type=Path,required=True); p.add_argument("--extension-version",required=True); p.add_argument("--app-version",required=True); a=p.parse_args()
    out=a.output_dir.resolve(); dark=out/f"XDM-Android-Firefox-{a.extension_version}-release-dark.xpi"; amoled=out/f"XDM-Android-Firefox-{a.extension_version}-release-amoled.xpi"
    artifacts=[inspect_xpi(dark,"dark",a.extension_version,a.app_version),inspect_xpi(amoled,"amoled",a.extension_version,a.app_version)]
    if artifacts[0]["sha256"]==artifacts[1]["sha256"]: raise SystemExit("Dark and AMOLED release XPIs must not be byte-identical.")
    metadata={"schemaVersion":2,"extensionId":EXPECTED_ID,"extensionVersion":a.extension_version,"appVersion":a.app_version,"captureContract":"direct-v3-keyless","applicationId":"com.mikeyphw.xdm.android","scheme":EXPECTED_SCHEME,"fixedZipTimestamp":"1980-01-01T00:00:00Z","artifacts":artifacts}
    a.metadata.parent.mkdir(parents=True,exist_ok=True); a.metadata.write_text(json.dumps(metadata,indent=2,sort_keys=True)+"\n",encoding="utf-8"); print(json.dumps(metadata,sort_keys=True)); return 0
if __name__=="__main__": raise SystemExit(main())
