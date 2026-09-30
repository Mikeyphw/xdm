#!/usr/bin/env python3
"""Fast regressions for XDM's shared Android OpenSSL dependency cache."""
from __future__ import annotations

import importlib.util
import json
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE = HERE / "android_native_openssl.py"
spec = importlib.util.spec_from_file_location("xdm_android_native_openssl", MODULE)
if spec is None or spec.loader is None:
    raise SystemExit("unable to load shared OpenSSL cache module")
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


ROOT = HERE.parent
ff_manifest = json.loads((ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.json").read_text(encoding="utf-8"))
aria_manifest = json.loads((ROOT / "transfer-aria2/runtime/aria2-runtime.json").read_text(encoding="utf-8"))
aria_ssl = aria_manifest["sources"]["openssl"]
require(ff_manifest["opensslVersion"] == aria_ssl["version"] == "3.5.8", "FFmpeg/aria2 OpenSSL versions diverged")
require(ff_manifest["opensslSourceUrl"] == aria_ssl["url"], "FFmpeg/aria2 OpenSSL source URLs diverged")
require(ff_manifest["opensslSourceSha256"] == aria_ssl["sha256"], "FFmpeg/aria2 OpenSSL source digests diverged")
require(ff_manifest["androidApi"] == aria_manifest["androidApi"] and ff_manifest["ndkVersion"] == aria_manifest["ndkVersion"],
        "FFmpeg/aria2 Android API or NDK pins diverged")

def identity(api: int = 26) -> dict:
    return shared.dependency_identity(
        version="3.5.8",
        source_url="https://example.invalid/openssl-3.5.8.tar.gz",
        source_sha256="a" * 64,
        android_api=api,
        ndk_version="29.0.14206865",
        ndk_provenance={"revision": "29.0.14206865", "sourcePropertiesSha256": "b" * 64},
        toolchain_identity={"backend": "termux-native-llvm", "sysrootOwner": "linux-x86_64", "tools": {"clang": {"sha256": "c" * 64}}},
    )


first = identity()
second = identity()
require(first == second, "equivalent consumers must derive identical shared OpenSSL identity")
require(shared.build_key(first) == shared.build_key(second), "equivalent consumers must share one OpenSSL cache key")
require(shared.build_key(first) != shared.build_key(identity(27)), "Android API drift must invalidate shared OpenSSL cache")

with tempfile.TemporaryDirectory(prefix="xdm-shared-openssl-test-") as raw:
    root = Path(raw)
    old = root / "legacy-prefix"
    new = root / "shared-prefix"
    (old / "include/openssl").mkdir(parents=True)
    (old / "lib/pkgconfig").mkdir(parents=True)
    (old / "include/openssl/ssl.h").write_text("/* ssl */\n", encoding="utf-8")
    (old / "lib/libssl.a").write_bytes(b"ssl-static")
    (old / "lib/libcrypto.a").write_bytes(b"crypto-static")
    (old / "lib/libz.a").write_bytes(b"must-not-leak")
    for name in ("libssl.pc", "libcrypto.pc", "openssl.pc"):
        (old / "lib/pkgconfig" / name).write_text(
            f"prefix={old}\nlibdir=${{prefix}}/lib\nincludedir=${{prefix}}/include\n",
            encoding="utf-8",
        )

    shared._copy_shared_install(old, new)
    require((new / "include/openssl/ssl.h").is_file(), "OpenSSL headers were not imported")
    require((new / "lib/libssl.a").read_bytes() == b"ssl-static", "libssl import changed bytes")
    require((new / "lib/libcrypto.a").read_bytes() == b"crypto-static", "libcrypto import changed bytes")
    require(not (new / "lib/libz.a").exists(), "consumer-owned zlib leaked into shared OpenSSL prefix")
    pc = (new / "lib/pkgconfig/libssl.pc").read_text(encoding="utf-8")
    require(str(new) in pc and str(old) not in pc, "pkg-config prefix was not rewritten for shared OpenSSL")

    config = shared.configuration_payload(first, new)
    require(str(new) not in json.dumps(first, sort_keys=True), "cache identity must remain independent of materialization path")
    require(str(new) in json.dumps(config, sort_keys=True), "configuration must bind the canonical shared install prefix")

    prov = shared.provenance(first, new, origin="unit-test")
    require(prov["dependencyIdentitySha256"] == shared.stable_hash(first), "shared identity digest is not deterministic")
    require(len(prov["libsslSha256"]) == 64 and len(prov["libcryptoSha256"]) == 64, "shared library digests are missing")

legacy = shared.legacy_configuration_payload(
    {"opensslVersion": "3.5.8", "opensslSourceSha256": "a" * 64, "androidApi": 26},
    Path("/legacy/prefix"),
    first["toolchainIdentity"],
)
require(shared._legacy_semantic_configuration_matches(legacy, first), "compatible legacy OpenSSL configuration is not adoptable")
legacy["androidApi"] = 27
require(not shared._legacy_semantic_configuration_matches(legacy, first), "legacy API mismatch must not be adopted")

print("Shared Android OpenSSL cache regressions passed")
