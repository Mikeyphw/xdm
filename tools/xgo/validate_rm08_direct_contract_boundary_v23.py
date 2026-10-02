#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".devtool.toml"
CANON = ROOT / "app/XDM.Android/tools/validate-rm08-aria2-packaged-execution.py"


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit(f"XGO RM08 direct contract boundary v23: FAIL: {msg}")


def main() -> int:
    data = tomllib.loads(CONFIG.read_text(encoding="utf-8"))
    targets = data.get("targets") or {}
    contract = targets.get("xgo_rm08_aria2_contract") or {}
    require(contract.get("runner") == "command", "contract target must use command runner")
    require(contract.get("platform") == "android", "contract target must remain Android-scoped")
    require(not (contract.get("tasks") or {}), "contract target must not contain Gradle tasks")
    require(not (contract.get("android") or {}), "contract target must not contain Android runner config")
    require(not (contract.get("gradle") or {}), "contract target must not contain Gradle runner config")
    require(not (contract.get("outputs") or []), "contract target must be output-free")
    job = (contract.get("jobs") or {}).get("rm08_contract") or {}
    require(job.get("command") == ["python3", "tools/validate-rm08-aria2-packaged-execution.py"],
            "contract job must execute canonical Python validator directly")
    workflow = ((contract.get("workflows") or {}).get("validate") or [])
    require(workflow == [{"id": "rm08_contract", "ref": "job:rm08_contract", "depends_on": []}],
            "contract workflow must have one direct command node")

    source = targets.get("xgo_rm08_aria2_source") or {}
    nodes = ((source.get("workflows") or {}).get("validate") or [])
    require([n.get("id") for n in nodes] == ["rm08_contract", "build_pinned"],
            "source workflow ordering must be contract -> native build")
    require(nodes[0].get("ref") == "target:xgo_rm08_aria2_contract#validate",
            "source workflow must consume canonical RM08 contract target")
    require(nodes[1].get("ref") == "job:build_pinned" and nodes[1].get("depends_on") == ["rm08_contract"],
            "native build must depend on direct contract qualification")

    builder = (ROOT / "app/XDM.Android/tools/build-aria2-runtime.py").read_text(encoding="utf-8")
    for token in (
        "authority.detect_ndk(manifest[\"ndkVersion\"])",
        "authority.ndk_provenance(ndk, manifest[\"ndkVersion\"])",
        "authority.resolve_toolchain(ndk, api, build_root, toolchain_identity)",
        "authority.ensure_host_build_tools()",
    ):
        require(token in builder, f"native builder lost self-owned Android toolchain discovery/preflight: {token}")

    canon_text = CANON.read_text(encoding="utf-8")
    for forbidden in (
        'contract.get("runner") == "android"',
        '"android_toolchain_bootstrap"',
        '"RM08 source workflow must bootstrap Android first',
    ):
        require(forbidden not in canon_text, f"canonical validator retains stale Gradle-bootstrap assertion: {forbidden}")

    proc = subprocess.run([sys.executable, str(CANON)], cwd=ROOT, text=True, capture_output=True)
    require(proc.returncode == 0, "canonical verifier failed: " + (proc.stderr or proc.stdout).strip())
    print("XGO RM08 direct contract boundary v23: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
