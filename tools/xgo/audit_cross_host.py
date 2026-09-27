#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class AuditError(RuntimeError):
    pass

def read(rel: str) -> str:
    p = ROOT / rel
    if not p.is_file():
        raise AuditError(f"missing required source: {rel}")
    return p.read_text(encoding="utf-8")

def go_const(source: str, name: str) -> str:
    m = re.search(rf"(?m)^\s*{re.escape(name)}\s*=\s*(\"[^\"]*\"|-?\d+)\s*$", source)
    if not m:
        raise AuditError(f"Go constant {name} not found")
    raw = m.group(1)
    return json.loads(raw) if raw.startswith('"') else raw

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True)
    ns = ap.parse_args()

    wire = read("engine/api/v1/wire.go")
    packaging = read("engine/androidhost/packaging.go")
    desktop = read("app/XDM/src/XDM.Platform/DesktopGoEngineHost.cs")
    android_bridge = read("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidGoEngineBridge.kt")
    android_authority = read("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineProcessAuthority.kt")
    with (ROOT / ".devtool.toml").open("rb") as fh:
        config = tomllib.load(fh)

    metadata = {
        "engine_version": go_const(wire, "EngineVersion"),
        "protocol_major": int(go_const(wire, "ProtocolMajor")),
        "protocol_minor": int(go_const(wire, "ProtocolMinor")),
        "abi_major": int(go_const(wire, "ABIMajor")),
        "schema_version": int(go_const(wire, "SchemaVersion")),
    }

    checks: list[str] = []
    def require(cond: bool, message: str) -> None:
        if not cond:
            raise AuditError(message)
        checks.append(message)

    # Both host bridges must obtain metadata from the same xdmcore ABI, never from
    # app-local shadow version state.
    require("xdm_engine_metadata" in desktop, "desktop reads xdmcore metadata")
    require("fun metadata(): ByteArray" in android_bridge and "native.metadata(handle)" in android_bridge,
            "android reads xdmcore metadata")

    # Desktop rejects every incompatible component of the canonical metadata tuple.
    for needle in ["engine_version", "schema_version", "abi_major", 'GetProperty("protocol")', 'GetProperty("major")', 'GetProperty("minor")']:
        require(needle in desktop, f"desktop validates metadata field {needle}")
    for needle in [
        f'ExpectedEngineVersion = "{metadata["engine_version"]}"',
        f"ExpectedProtocolMajor = {metadata['protocol_major']}",
        f"ExpectedProtocolMinor = {metadata['protocol_minor']}",
        f"ExpectedAbiMajor = {metadata['abi_major']}",
        f"ExpectedSchemaVersion = {metadata['schema_version']}",
    ]:
        require(needle in desktop, f"desktop expectation matches canonical metadata: {needle}")

    # Android startup must validate exactly the same tuple before publishing a
    # process-wide engine identity.
    require("validateEngineMetadata(nextBridge.metadata())" in android_authority,
            "android validates xdmcore metadata before engine publication")
    for needle in [
        f'EXPECTED_ENGINE_VERSION = "{metadata["engine_version"]}"',
        f"EXPECTED_PROTOCOL_MAJOR = {metadata['protocol_major']}",
        f"EXPECTED_PROTOCOL_MINOR = {metadata['protocol_minor']}",
        f"EXPECTED_ABI_MAJOR = {metadata['abi_major']}",
        f"EXPECTED_SCHEMA_VERSION = {metadata['schema_version']}",
    ]:
        require(needle in android_authority, f"android expectation matches canonical metadata: {needle}")

    # Android package metadata must still identify the same wire API generation.
    require('WireAPIVersion    = "xgo.api.v1"' in packaging,
            "android package manifest remains on xgo.api.v1")
    require(metadata["protocol_major"] == 1,
            "canonical protocol major remains compatible with xgo.api.v1 package manifest")

    gate = config["targets"]["xgo_gate_cross_host"]["workflows"]["validate"]
    gate_refs = [node["ref"] for node in gate]
    require(gate_refs == ["target:xgo_android#validate", "target:xgo_desktop#validate", "target:xdm_modern#validate"],
            "GATE-11 composes focused Android, focused Desktop, then the real desktop runner seal")
    modern = config["targets"]["xdm_modern"]
    require(modern.get("runner") == "dotnet", "desktop full-seal target uses the dotnet runner")
    require(modern.get("restore_command") == "dotnet restore app/XDM/XDM.Modern.sln",
            "desktop full seal restores the modern solution")
    require(modern.get("build_command") == "dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore",
            "desktop full seal builds the modern solution in Release")
    require(modern.get("test_command") == "dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build",
            "desktop full seal tests the modern solution in Release")

    report = {
        "schema_version": 1,
        "status": "pass",
        "canonical": metadata,
        "android": metadata,
        "desktop": metadata,
        "check_count": len(checks),
        "checks": checks,
    }
    out = ROOT / ns.output
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AuditError as exc:
        print(json.dumps({"schema_version": 1, "status": "fail", "error": str(exc)}, sort_keys=True))
        raise SystemExit(1)
