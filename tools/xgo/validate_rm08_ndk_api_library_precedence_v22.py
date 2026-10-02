#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "app/XDM.Android/tools/build-aria2-runtime.py"
text = BUILD.read_text(encoding="utf-8")

required = {
    "static libc++ remains driver-owned": '"-fPIE -pie -static-libstdc++ "',
    "dynamic Bionic mode retained": '"-Wl,-Bdynamic -Wl,--no-as-needed -lc -Wl,--as-needed "',
    "NDK runtime inventory remains qualified": 'android_cxx_runtime_link_dir(toolchain)',
    "API precedence rationale": 'version-generic NDK runtime',
    "driver owns NDK search ordering": 'Clang must own search ordering',
    "recipe schema v6": '"schemaVersion": 6,',
}
missing = [label for label, token in required.items() if token not in text]
if missing:
    raise SystemExit("RM08 v22 NDK API-library precedence contract missing: " + ", ".join(missing))

# The regression that produced DT_NEEDED=[] was an explicit -L pointing at the
# version-generic NDK runtime directory before Clang's API-specific directory.
for forbidden in (
    'f"-L{cxx_runtime_link_dir}',
    'f"-L{android_cxx_runtime_link_dir',
):
    if forbidden in text:
        raise SystemExit(
            "RM08 v22 must not prepend the version-generic NDK runtime directory to LDFLAGS: "
            + forbidden
        )

# Keep the source-build proof strict: a dynamic interpreter alone is insufficient;
# the probe/final executable must still expose libc.so via DT_NEEDED.
for token in (
    'if "libc.so" not in needed:',
    'getaddrinfo(host.c_str()',
    '"-###"',
):
    if token not in text:
        raise SystemExit(f"RM08 v22 lost required dynamic-Bionic evidence token: {token}")

print("XGO RM08 NDK API-specific library precedence v22: PASS")
