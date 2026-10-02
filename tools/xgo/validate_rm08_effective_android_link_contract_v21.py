#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "app/XDM.Android/tools/build-aria2-runtime.py"
text = BUILD.read_text(encoding="utf-8")

required = {
    "effective compiler default-config isolation": 'isolate_android_compiler_default_config',
    "no-default-config compiler wrapper": '"--no-default-config"',
    "dynamic linker mode restore after static libc++": '"-Wl,-Bdynamic',
    "explicit retained dynamic libc": '-Wl,--no-as-needed -lc -Wl,--as-needed',
    "getaddrinfo link-model probe": 'getaddrinfo(host.c_str()',
    "libc.so DT_NEEDED release invariant": 'if "libc.so" not in needed:',
    "Clang effective link-plan trace": '"-###"',
    "recipe schema v5": '"schemaVersion": 5,',
}
missing = [label for label, token in required.items() if token not in text]
if missing:
    raise SystemExit("RM08 v21 compiler/link contract missing: " + ", ".join(missing))

for forbidden in ('"-static"', '"-Wl,-Bstatic"', "libc.a"):
    if forbidden in text:
        raise SystemExit(f"RM08 v21 must not reintroduce full/static Bionic linkage token: {forbidden}")

isolate_pos = text.index("cc = isolate_android_compiler_default_config")
link_pos = text.index("common_ldflags =")
probe_pos = text.index("prove_android_bionic_link_model(cxx, env, build_root)")
if not isolate_pos < link_pos < probe_pos:
    raise SystemExit("RM08 v21 compiler isolation/link/probe ordering regressed")

print("XGO RM08 effective Android compiler + dynamic Bionic link contract v21: PASS")
