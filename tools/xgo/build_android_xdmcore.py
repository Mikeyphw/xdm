#!/usr/bin/env python3
"""Plan/build libxdmcore.so for Android with release provenance.

The default mode is a deterministic dry run used by XGO validation. Passing
--execute resolves a runnable Android/arm64 cgo compiler, builds the shared
library with 16 KiB page alignment, writes provenance, and verifies that the
result is an AArch64 Android shared object with no external libsqlite3 runtime.

On Termux, prefer the native Termux clang exactly as chrootctl does for its
SQLite-bearing Android/arm64 runtime. On ordinary Linux CI/build hosts, fall
back to the configured Android NDK target clang. A Termux+NDK sysroot shim is a
last-resort compatibility lane and is accepted only if a real Go/cgo probe
passes.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVENANCE_REL = "app/XDM.Android/app/src/main/assets/xdmcore/xdmcore-provenance.json"
ANDROID_API = 26
ANDROID_PAGE_SIZE = 16384
REQUIRED_NDK_REVISION = "29.0.14206865"
ANDROID_ABI = "arm64-v8a"
ANDROID_TARGET = f"aarch64-linux-android{ANDROID_API}"
ANDROID_OUTPUT = f"app/XDM.Android/app/src/main/jniLibs/{ANDROID_ABI}/libxdmcore.so"
ANDROID_HEADER = "app/XDM.Android/app/src/main/cpp/xdm_core.h"
GENERATED_ANDROID_HEADER = f"app/XDM.Android/app/src/main/jniLibs/{ANDROID_ABI}/libxdmcore.h"
STALE_SQLITE_RUNTIME = f"app/XDM.Android/app/src/main/jniLibs/{ANDROID_ABI}/libsqlite3.so"

ABIS = [
    {
        "name": ANDROID_ABI,
        "goos": "android",
        "goarch": "arm64",
        "min_api": ANDROID_API,
        "cc": f"{ANDROID_TARGET}-clang",
    }
]
SYMBOLS = [
    "xdm_buffer_free",
    "xdm_engine_command",
    "xdm_engine_create",
    "xdm_engine_metadata",
    "xdm_engine_next_frame",
    "xdm_engine_platform_reply",
    "xdm_engine_shutdown",
]

JNI_SYMBOLS = [
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeCreate",
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeCommand",
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeNextFrame",
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativePlatformReply",
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeMetadata",
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeShutdown",
    "Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_releaseBuffer",
]


@dataclass(frozen=True)
class CompilerLane:
    cc: str
    lane: str
    extra_ldflags: tuple[str, ...] = ()


def git_revision() -> str:
    return subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()


def git_modified() -> bool:
    output = subprocess.check_output(
        ["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"], text=True
    )
    return bool(output.strip())


def current_go_version() -> str:
    return subprocess.check_output(["go", "env", "GOVERSION"], text=True).strip()


def plan(go_version: str, source_revision: str, source_modified: bool = False) -> dict:
    artifacts = []
    modified = "true" if source_modified else "false"
    for abi in ABIS:
        ldflags = (
            f"-X github.com/subhra74/xdm/engine/api/v1.BuildRevision={source_revision} "
            f"-X github.com/subhra74/xdm/engine/api/v1.BuildModified={modified} "
            "-X github.com/subhra74/xdm/engine/api/v1.BuildMode=c-shared"
        )
        command = [
            "go",
            "build",
            "-trimpath",
            "-buildvcs=false",
            "-buildmode=c-shared",
            "-ldflags",
            ldflags,
            "-o",
            ANDROID_OUTPUT,
            "./engine/bridge/cabi",
        ]
        artifacts.append(
            {
                **abi,
                "output": ANDROID_OUTPUT,
                "header": ANDROID_HEADER,
                "generated_header": GENERATED_ANDROID_HEADER,
                "provenance_output": PROVENANCE_REL,
                "build_mode": "c-shared",
                "sqlite_linkage": "embedded-mattn-go-sqlite3-amalgamation",
                "page_size": ANDROID_PAGE_SIZE,
                "command": command,
            }
        )
    return {
        "schema_version": 3,
        "library_name": "libxdmcore.so",
        "provenance_name": "xdmcore-provenance.json",
        "abi_version": "xgo.android.abi.v1",
        "wire_api_version": "xgo.api.v1",
        "engine_version": "0.1.0-dev",
        "protocol": {"major": 1, "minor": 0},
        "engine_schema_version": 1,
        "build_mode": "c-shared",
        "go_version": go_version,
        "source_revision": source_revision,
        "source_modified": source_modified,
        "sqlite_linkage": "embedded-mattn-go-sqlite3-amalgamation",
        "required_page_size": ANDROID_PAGE_SIZE,
        "required_ndk_revision": REQUIRED_NDK_REVISION,
        "required_symbols": SYMBOLS,
        "required_jni_symbols": JNI_SYMBOLS,
        "artifacts": artifacts,
    }


def write_provenance(data: dict) -> None:
    artifact = data["artifacts"][0]
    command = [
        "go",
        "run",
        "./engine/cmd/xgo-provenance",
        "--revision",
        data["source_revision"],
        f"--modified={'true' if data['source_modified'] else 'false'}",
        "--go-version",
        data["go_version"],
        "--goos",
        artifact["goos"],
        "--goarch",
        artifact["goarch"],
        "--build-mode",
        data["build_mode"],
        "--output",
        artifact["provenance_output"],
    ]
    subprocess.run(command, cwd=ROOT, check=True)


def _termux_prefix() -> Path | None:
    value = os.environ.get("PREFIX", "").strip()
    if value:
        path = Path(value)
        if path.is_dir() and (path / "bin/clang").exists():
            return path
    fallback = Path("/data/data/com.termux/files/usr")
    if fallback.is_dir() and (fallback / "bin/clang").exists():
        return fallback
    return None


def _sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key, "").strip()
        if value:
            roots.append(Path(value))
    roots.extend(
        [
            Path.home() / "android-sdk",
            Path.home() / "Android/Sdk",
        ]
    )
    prefix = _termux_prefix()
    if prefix:
        roots.extend([prefix / "share/android-sdk", prefix / "opt/android-sdk"])
    result: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        try:
            resolved = root.expanduser().resolve()
        except OSError:
            continue
        if resolved in seen:
            continue
        seen.add(resolved)
        result.append(resolved)
    return result


def _ndk_roots() -> list[Path]:
    roots: list[Path] = []
    for key in ("ANDROID_NDK_ROOT", "ANDROID_NDK_HOME"):
        value = os.environ.get(key, "").strip()
        if value:
            roots.append(Path(value))
    for sdk in _sdk_roots():
        roots.append(sdk / "ndk" / REQUIRED_NDK_REVISION)
    result: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        try:
            resolved = root.expanduser().resolve()
        except OSError:
            continue
        if resolved in seen or not resolved.is_dir():
            continue
        seen.add(resolved)
        result.append(resolved)
    return result



def _jni_include_dirs() -> tuple[Path, ...]:
    candidates: list[Path] = []
    for ndk in _ndk_roots():
        prebuilt = ndk / "toolchains/llvm/prebuilt"
        candidates.extend(sorted(prebuilt.glob("*/sysroot/usr/include")))

    prefix = _termux_prefix()
    if prefix:
        candidates.append(prefix / "include")

    java_home = os.environ.get("JAVA_HOME", "").strip()
    if java_home:
        candidates.append(Path(java_home) / "include")

    seen: set[Path] = set()
    for base in candidates:
        try:
            resolved = base.expanduser().resolve()
        except OSError:
            continue
        if resolved in seen or not (resolved / "jni.h").is_file():
            continue
        seen.add(resolved)
        result = [resolved]
        for child in ("android", "linux"):
            subdir = resolved / child
            if (subdir / "jni_md.h").is_file():
                result.append(subdir)
        return tuple(result)

    raise RuntimeError(
        "jni.h was not found in the configured Android NDK, Termux prefix, "
        "or JAVA_HOME; Android JNI adapter compilation cannot proceed"
    )


def _ndk_revision(root: Path) -> str:
    props = root / "source.properties"
    if not props.is_file():
        return ""
    for raw in props.read_text(encoding="utf-8", errors="replace").splitlines():
        if raw.strip().startswith("Pkg.Revision") and "=" in raw:
            return raw.split("=", 1)[1].strip()
    return ""


def _make_sysroot_shim(native_clang: Path, sysroot: Path) -> tuple[Path, tuple[str, ...]]:
    directory = ROOT / ".devtool/tmp/xgo-android-toolchain"
    directory.mkdir(parents=True, exist_ok=True)
    shim = directory / f"{ANDROID_TARGET}-clang"
    prefix = _termux_prefix()
    extra = () if prefix is None else (f"-L{prefix / 'lib'}",)
    script = [str(native_clang), f"--target={ANDROID_TARGET}", f"--sysroot={sysroot}"]
    shim.write_text(
        "#!/bin/sh\nexec " + " ".join(shlex.quote(value) for value in script) + ' "$@"\n',
        encoding="utf-8",
    )
    shim.chmod(0o700)
    # The Termux lib directory is intentionally linker-only. It is not placed in
    # the compiler wrapper because cgo probes compile under -Werror.
    return shim, extra


def _base_ldflags(extra: tuple[str, ...] = ()) -> str:
    values = [
        "-fuse-ld=lld",
        f"-Wl,-z,max-page-size={ANDROID_PAGE_SIZE}",
        f"-Wl,-z,common-page-size={ANDROID_PAGE_SIZE}",
        *extra,
    ]
    return " ".join(values)


def _probe_compiler(lane: CompilerLane) -> tuple[bool, str]:
    with tempfile.TemporaryDirectory(prefix="xgo-android-cgo-probe-") as tmp:
        tmpdir = Path(tmp)
        source = tmpdir / "probe.go"
        output = tmpdir / "probe.so"
        source.write_text(
            'package main\n/* static int xgo_probe(void) { return 42; } */\nimport "C"\nfunc main() { _ = C.xgo_probe() }\n',
            encoding="utf-8",
        )
        env = os.environ.copy()
        env.update(
            {
                "GOOS": "android",
                "GOARCH": "arm64",
                "CGO_ENABLED": "1",
                "CC": lane.cc,
                "CC_FOR_TARGET": lane.cc,
                "CGO_CFLAGS": "-O2 -fPIC",
                "CGO_LDFLAGS": _base_ldflags(lane.extra_ldflags),
                "GO111MODULE": "off",
                "GOWORK": "off",
            }
        )
        completed = subprocess.run(
            ["go", "build", "-trimpath", "-buildvcs=false", "-buildmode=c-shared", "-o", str(output), str(source)],
            cwd=tmpdir,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        return completed.returncode == 0 and output.is_file(), completed.stdout


def resolve_compiler() -> CompilerLane:
    candidates: list[CompilerLane] = []
    explicit = os.environ.get("XGO_ANDROID_CC", "").strip()
    if explicit:
        path = Path(explicit).expanduser()
        if not path.is_absolute():
            resolved = shutil.which(explicit)
            if resolved:
                path = Path(resolved)
        candidates.append(CompilerLane(str(path), "override"))

    prefix = _termux_prefix()
    native_clang = prefix / "bin/clang" if prefix else None
    if native_clang and native_clang.is_file():
        candidates.append(CompilerLane(str(native_clang), "termux-native-clang"))

    ndk_roots = _ndk_roots()
    for ndk in ndk_roots:
        revision = _ndk_revision(ndk)
        if revision and revision != REQUIRED_NDK_REVISION:
            continue
        prebuilt = ndk / "toolchains/llvm/prebuilt"
        for clang in sorted(prebuilt.glob(f"*/bin/{ANDROID_TARGET}-clang")):
            candidates.append(CompilerLane(str(clang), f"ndk-direct:{ndk.name}"))

    if native_clang and native_clang.is_file():
        for ndk in ndk_roots:
            prebuilt = ndk / "toolchains/llvm/prebuilt"
            sysroots = []
            if (prebuilt / "sysroot").is_dir():
                sysroots.append(prebuilt / "sysroot")
            sysroots.extend(sorted(prebuilt.glob("*/sysroot")))
            for sysroot in sysroots:
                shim, extra = _make_sysroot_shim(native_clang, sysroot)
                candidates.append(CompilerLane(str(shim), f"termux-native-clang-ndk-sysroot:{ndk.name}", extra))

    diagnostics: list[str] = []
    seen: set[tuple[str, tuple[str, ...]]] = set()
    for lane in candidates:
        key = (lane.cc, lane.extra_ldflags)
        if key in seen:
            continue
        seen.add(key)
        path = Path(lane.cc)
        if not path.exists() or not os.access(path, os.X_OK):
            diagnostics.append(f"{lane.lane}: compiler missing or not executable: {lane.cc}")
            continue
        ok, log = _probe_compiler(lane)
        if ok:
            return lane
        tail = "\n".join(log.rstrip().splitlines()[-10:])
        diagnostics.append(f"{lane.lane}: Go/cgo probe failed\n{tail}")

    detail = "\n\n".join(diagnostics) if diagnostics else "no compiler candidates were discovered"
    raise RuntimeError(
        "no Android/arm64 compiler lane passed the Go/cgo + 16 KiB probe; "
        "set XGO_ANDROID_CC to an explicit runnable compiler if needed\n\n" + detail
    )


def _readelf_tool(lane: CompilerLane) -> str | None:
    candidates = [shutil.which("llvm-readelf"), shutil.which("readelf")]
    cc = Path(lane.cc)
    candidates.extend([str(cc.with_name("llvm-readelf")), str(cc.with_name("readelf"))])
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    return None


def verify_native_output(path: Path, lane: CompilerLane) -> dict:
    if not path.is_file() or path.stat().st_size <= 0:
        raise RuntimeError(f"Android native output is missing or empty: {path}")
    tool = _readelf_tool(lane)
    if tool is None:
        raise RuntimeError("llvm-readelf/readelf is required to verify Android native output")

    header = subprocess.check_output([tool, "-h", str(path)], text=True, stderr=subprocess.STDOUT)
    if "AArch64" not in header and "ARM aarch64" not in header:
        raise RuntimeError(f"libxdmcore.so is not AArch64 according to {tool}")

    dynamic = subprocess.check_output([tool, "-d", str(path)], text=True, stderr=subprocess.STDOUT)
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic)
    forbidden_needed = sorted(
        name for name in needed
        if name == "libsqlite3.so" or ".so." in name
    )
    if forbidden_needed:
        raise RuntimeError(
            "libxdmcore.so has non-Android/app-owned dynamic dependencies: "
            + ", ".join(forbidden_needed)
        )

    symbol_table = subprocess.check_output([tool, "-Ws", str(path)], text=True, stderr=subprocess.STDOUT)
    missing_jni = [name for name in JNI_SYMBOLS if name not in symbol_table]
    if missing_jni:
        raise RuntimeError(
            "libxdmcore.so is missing required Android JNI entry points: "
            + ", ".join(missing_jni)
        )

    program_headers = subprocess.check_output([tool, "-lW", str(path)], text=True, stderr=subprocess.STDOUT)
    alignments: list[int] = []
    for raw in program_headers.splitlines():
        line = raw.strip()
        if not line.startswith("LOAD"):
            continue
        token = line.split()[-1]
        try:
            alignments.append(int(token, 0))
        except ValueError:
            continue
    if not alignments or min(alignments) < ANDROID_PAGE_SIZE:
        raise RuntimeError(
            f"libxdmcore.so PT_LOAD alignment is below {ANDROID_PAGE_SIZE}: {alignments or 'unavailable'}"
        )
    return {
        "elf": "aarch64",
        "sqlite_dynamic_dependency": False,
        "needed_libraries": needed,
        "load_alignments": alignments,
        "jni_exports": JNI_SYMBOLS,
        "inspector": tool,
    }


def execute(data: dict) -> dict:
    lane = resolve_compiler()
    jni_include_dirs = _jni_include_dirs()
    stale_sqlite = ROOT / STALE_SQLITE_RUNTIME
    if stale_sqlite.exists():
        stale_sqlite.unlink()

    verification: list[dict] = []
    for artifact in data["artifacts"]:
        env = os.environ.copy()
        env.update(
            {
                "GOOS": artifact["goos"],
                "GOARCH": artifact["goarch"],
                "CGO_ENABLED": "1",
                "CC": lane.cc,
                "CC_FOR_TARGET": lane.cc,
                "CGO_CFLAGS": " ".join(
                    ["-O2", "-fPIC", *(f"-isystem {path}" for path in jni_include_dirs)]
                ),
                "CGO_LDFLAGS": _base_ldflags(lane.extra_ldflags),
            }
        )
        target = ROOT / artifact["output"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.unlink(missing_ok=True)
        generated_header = ROOT / artifact["generated_header"]
        generated_header.unlink(missing_ok=True)
        subprocess.run(artifact["command"], cwd=ROOT, env=env, check=True)
        verification.append(verify_native_output(target, lane))

    write_provenance(data)
    return {
        "compiler": lane.cc,
        "compiler_lane": lane.lane,
        "cgo_ldflags": _base_ldflags(lane.extra_ldflags),
        "jni_include_dirs": [str(path) for path in jni_include_dirs],
        "verification": verification,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--go-version")
    parser.add_argument("--source-revision")
    parser.add_argument("--source-modified", choices=("true", "false"))
    parser.add_argument("--output", default=".devtool/reports/xgo/android/android-engine-build-plan.json")
    args = parser.parse_args()

    resolved_go = args.go_version or current_go_version()
    resolved_revision = args.source_revision or git_revision()
    resolved_modified = git_modified() if args.source_modified is None else args.source_modified == "true"
    data = plan(resolved_go, resolved_revision, resolved_modified)

    if args.execute:
        data["execution"] = execute(data)

    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(data, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
