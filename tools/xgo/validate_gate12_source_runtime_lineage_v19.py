#!/usr/bin/env python3
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".devtool.toml"

def fail(msg: str) -> None:
    raise SystemExit(f"XGO Gate-12 RM08 source-runtime lineage v19: FAIL: {msg}")

def main() -> None:
    data = tomllib.loads(CONFIG.read_text(encoding="utf-8"))
    targets = data.get("targets") or {}
    cont = targets.get("xgo_gate_final_continue") or {}
    nodes = ((cont.get("workflows") or {}).get("registry_bypass") or [])
    ids = [str(n.get("id")) for n in nodes]
    refs = [str(n.get("ref")) for n in nodes]
    for required in ("aria2_source_runtime", "android_pair_refresh", "package_attestation"):
        if required not in ids:
            fail(f"missing {required}")
    si, ri, ai = ids.index("aria2_source_runtime"), ids.index("android_pair_refresh"), ids.index("package_attestation")
    if not (si < ri < ai):
        fail("required order is source runtime -> APK pair refresh -> package attestation")
    if refs[si] != "target:xgo_rm08_aria2_source#validate":
        fail("source-runtime node does not own xgo_rm08_aria2_source#validate")
    if nodes[ri].get("depends_on") != ["aria2_source_runtime"]:
        fail("APK refresh is not causally bound to source-runtime materialization")
    source = targets.get("xgo_rm08_aria2_source") or {}
    outputs = set(source.get("outputs") or [])
    required_outputs = {
        "transfer-aria2/src/main/jniLibs/arm64-v8a/libaria2c.so",
        "transfer-aria2/runtime/aria2-runtime.lock.json",
    }
    if not required_outputs.issubset(outputs):
        fail("source target does not publish both runtime ELF and lock")
    builder = (ROOT / "app/XDM.Android/tools/build-aria2-runtime.py").read_text(encoding="utf-8")
    verifier = (ROOT / "app/XDM.Android/tools/verify-aria2-runtime.py").read_text(encoding="utf-8")
    if "require_dynamic_bionic_linkage" not in builder or '"libc.so" not in needed' not in builder:
        fail("source builder is not the v18 dynamic-Bionic contract")
    if "require_dynamic_bionic_linkage" not in verifier or '"libc.so" not in needed' not in verifier:
        fail("release verifier is not the v18 dynamic-Bionic contract")
    print("XGO Gate-12 RM08 source-runtime lineage v19: PASS")

if __name__ == "__main__":
    main()
