from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "build_android_xdmcore.py"
spec = importlib.util.spec_from_file_location("build_android_xdmcore", MODULE_PATH)
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def test_plan_declares_embedded_android_sqlite_and_16k_alignment():
    result = mod.plan("go1.25.0", "abc123", False)
    assert result["schema_version"] == 3
    assert result["sqlite_linkage"] == "embedded-mattn-go-sqlite3-amalgamation"
    assert result["required_page_size"] == 16384
    artifact = result["artifacts"][0]
    assert artifact["output"].endswith("jniLibs/arm64-v8a/libxdmcore.so")
    assert artifact["header"] == "app/XDM.Android/app/src/main/cpp/xdm_core.h"
    assert artifact["generated_header"].endswith("jniLibs/arm64-v8a/libxdmcore.h")
    assert artifact["sqlite_linkage"] == "embedded-mattn-go-sqlite3-amalgamation"
    assert "-buildvcs=false" in artifact["command"]


def test_linker_only_flags_are_not_in_compiler_wrapper():
    flags = mod._base_ldflags(("-L/test/prefix/lib",))
    assert "-fuse-ld=lld" in flags
    assert "max-page-size=16384" in flags
    assert "common-page-size=16384" in flags
    assert "-L/test/prefix/lib" in flags


def test_android_sqlite_binding_uses_embedded_amalgamation():
    root = MODULE_PATH.parents[2]
    cgo = (root / "engine/store/sqlite/db_cgo.go").read_text(encoding="utf-8")
    android = (root / "engine/store/sqlite/db_android_amalgamation.go").read_text(encoding="utf-8")
    gomod = (root / "engine/go.mod").read_text(encoding="utf-8")
    assert "#cgo android LDFLAGS: -lsqlite3" not in cgo
    # cgo treats Android as satisfying the linux condition as well. The
    # system-SQLite linker directive must therefore explicitly exclude Android.
    assert "#cgo linux,!android LDFLAGS: -lsqlite3" in cgo
    assert "#cgo linux LDFLAGS: -lsqlite3" not in cgo
    assert '_ "github.com/mattn/go-sqlite3"' in android
    assert "github.com/mattn/go-sqlite3 v1.14.50" in gomod


def test_stale_external_android_sqlite_runtime_has_canonical_path():
    assert mod.STALE_SQLITE_RUNTIME == "app/XDM.Android/app/src/main/jniLibs/arm64-v8a/libsqlite3.so"


def test_dynamic_dependency_policy_rejects_versioned_termux_sonames():
    import re
    sample = """
 0x0000000000000001 (NEEDED)             Shared library: [libc.so]
 0x0000000000000001 (NEEDED)             Shared library: [libz.so.1]
"""
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", sample)
    forbidden = sorted(name for name in needed if name == "libsqlite3.so" or ".so." in name)
    assert forbidden == ["libz.so.1"]

def test_gradle_declares_native_builder_dependency_for_all_generated_source_consumers():
    root = MODULE_PATH.parents[2]
    gradle = (root / "app/XDM.Android/app/build.gradle.kts").read_text(encoding="utf-8")

    # buildXgoNativeCore writes into src/main/jniLibs and src/main/assets.
    # Gradle 9.7+ validates ownership at the source-set merge tasks themselves,
    # not only at the later merge*NativeLibs task.
    assert 'taskName.endsWith("JniLibFolders")' in gradle
    assert 'taskName.endsWith("Assets")' in gradle
    assert 'taskName.endsWith("NativeLibs")' in gradle
    assert "dependsOn(buildXgoNativeCore)" in gradle

    native_block = gradle[gradle.index("// buildXgoNativeCore writes generated JNI"):]
    native_block = native_block[:native_block.index("fun validationEvidence")]
    assert native_block.count("dependsOn(buildXgoNativeCore)") == 1

