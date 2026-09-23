#!/usr/bin/env python3
"""Reproducibly build and install XDM's pinned arm64-v8a FFmpeg/FFprobe runtime.

The resulting PIE executables are deliberately packaged under lib*.so names so Android extracts
and exposes them from ApplicationInfo.nativeLibraryDir without relying on Termux or writable-code
locations. FFmpeg is linked statically to its own libraries and pinned OpenSSL while retaining
Android/Bionic dynamic dependencies.

FF04 v7 keeps the expensive native source build restart-safe and preflights the exact pinned FFmpeg profile through the source configure parser: a deterministic build cache is
serialized across callers, successful configure phases are fingerprinted, make output is quiet by
default, and interrupted OpenSSL/FFmpeg compilations resume from the existing object tree.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import stat
import subprocess
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Iterator

from android_elf_runtime import ElfPolicyError, validate_android_runtime_file

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.json"
LOCK_PATH = ROOT / "media-ffmpeg/runtime/ffmpeg-runtime.lock.json"
CACHE_ROOT = Path(os.environ.get("XDM_FFMPEG_BUILD_CACHE", Path.home() / ".cache/xdm/ffmpeg-runtime"))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def atomic_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, raw = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    tmp = Path(raw)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(payload, out, indent=2, sort_keys=True)
            out.write("\n")
            out.flush()
            os.fsync(out.fileno())
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)


def marker_matches(path: Path, payload: dict) -> bool:
    if not path.is_file():
        return False
    try:
        return json.loads(path.read_text(encoding="utf-8")) == payload
    except (OSError, json.JSONDecodeError):
        return False


def download(url: str, target: Path, expected: str) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and sha256(target) == expected:
        return target
    tmp = target.with_suffix(target.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "XDM-Android-FFmpeg-runtime-builder/1"})
    with urllib.request.urlopen(request, timeout=180) as response, tmp.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(tmp)
    if actual != expected:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"source digest mismatch for {url}: expected {expected}, got {actual}")
    tmp.replace(target)
    return target


def safe_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with tarfile.open(archive, "r:*") as tf:
        for member in tf.getmembers():
            resolved = (destination / member.name).resolve()
            if root != resolved and root not in resolved.parents:
                raise SystemExit(f"unsafe archive member: {member.name}")
        tf.extractall(destination, filter="data")


def local_properties_sdk_roots() -> list[Path]:
    roots: list[Path] = []
    local_properties = ROOT / "local.properties"
    if not local_properties.is_file():
        return roots
    for raw_line in local_properties.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() != "sdk.dir":
            continue
        # Android's local.properties commonly escapes backslashes/colons on Windows;
        # harmlessly normalize those forms while preserving normal Unix/Termux paths.
        decoded = value.strip().replace("\\:", ":").replace("\\\\", "\\")
        if decoded:
            roots.append(Path(decoded))
    return roots


def read_ndk_revision(ndk: Path) -> str:
    source_properties = ndk / "source.properties"
    if not source_properties.is_file():
        raise SystemExit(f"Android NDK source.properties is missing: {source_properties}")
    match = re.search(r"(?m)^Pkg\.Revision\s*=\s*([^\r\n]+)$", source_properties.read_text(encoding="utf-8", errors="replace"))
    if not match:
        raise SystemExit(f"Android NDK revision is missing from {source_properties}")
    return match.group(1).strip()


def ndk_provenance(ndk: Path, expected_version: str) -> dict:
    revision = read_ndk_revision(ndk)
    if revision != expected_version:
        raise SystemExit(f"Android NDK revision mismatch: requested {expected_version}, found {revision} at {ndk}")
    source_properties = ndk / "source.properties"
    return {
        "revision": revision,
        "sourcePropertiesSha256": sha256(source_properties),
    }


def detect_ndk(version: str) -> Path:
    candidates: list[Path] = []
    for key in ("ANDROID_NDK_ROOT", "ANDROID_NDK_HOME"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value))
    sdk_roots: list[Path] = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value:
            sdk_roots.append(Path(value))
    sdk_roots += local_properties_sdk_roots()
    for sdk_root in sdk_roots:
        candidates.append(sdk_root / "ndk" / version)
    home = Path.home()
    candidates += [
        home / "Android/Sdk/ndk" / version,
        Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr")) / "share/android-sdk/ndk" / version,
    ]
    seen: set[Path] = set()
    rejected: list[str] = []
    for candidate in candidates:
        candidate = candidate.expanduser()
        if candidate in seen:
            continue
        seen.add(candidate)
        if not (candidate / "toolchains/llvm/prebuilt").is_dir():
            continue
        try:
            actual = read_ndk_revision(candidate)
        except SystemExit as error:
            rejected.append(f"{candidate}: {error}")
            continue
        if actual != version:
            rejected.append(f"{candidate}: revision {actual} != {version}")
            continue
        return candidate.resolve()
    searched = ", ".join(str(path) for path in candidates)
    suffix = f" Rejected candidates: {'; '.join(rejected)}." if rejected else ""
    raise SystemExit(
        f"Android NDK {version} not found. Checked: {searched}." + suffix +
        " Set ANDROID_NDK_ROOT/ANDROID_NDK_HOME or sdk.dir in local.properties."
    )

def ndk_prebuilt_toolchains(ndk: Path) -> list[Path]:
    prebuilt = ndk / "toolchains/llvm/prebuilt"
    options = sorted((p for p in prebuilt.iterdir() if p.is_dir()), key=lambda p: p.name)
    if not options:
        raise SystemExit(f"NDK LLVM toolchain missing under {prebuilt}")
    return options


def command_runs(command: Path) -> bool:
    try:
        result = subprocess.run(
            [str(command), "--version"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=15,
            check=False,
        )
        return result.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def write_exec_wrapper(path: Path, executable: Path, prefix_args: list[str]) -> None:
    command = " ".join(shlex.quote(str(part)) for part in [executable, *prefix_args])
    path.write_text(f"#!/bin/sh\nexec {command} \"$@\"\n", encoding="utf-8")
    path.chmod(0o755)


def tool_fingerprint(path: Path) -> dict:
    resolved = path.resolve()
    if not resolved.is_file():
        raise SystemExit(f"required tool is missing: {resolved}")
    try:
        result = subprocess.run(
            [str(resolved), "--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise SystemExit(f"could not fingerprint tool {resolved}: {error}") from error
    if result.returncode != 0:
        raise SystemExit(f"tool does not execute successfully: {resolved}")
    version_line = next((line.strip() for line in result.stdout.splitlines() if line.strip()), "<no-version-output>")
    return {
        "name": resolved.name,
        "sha256": sha256(resolved),
        "version": version_line,
    }


def select_toolchain_identity(ndk: Path, api: int) -> dict:
    options = ndk_prebuilt_toolchains(ndk)
    preferred = sorted(options, key=lambda p: (not p.name.startswith("linux-"), p.name))
    target = f"aarch64-linux-android{api}"
    for toolchain in preferred:
        tools = {
            "clang": toolchain / "bin" / f"{target}-clang",
            "clang++": toolchain / "bin" / f"{target}-clang++",
            "llvm-ar": toolchain / "bin/llvm-ar",
            "llvm-ranlib": toolchain / "bin/llvm-ranlib",
            "llvm-strip": toolchain / "bin/llvm-strip",
            "llvm-nm": toolchain / "bin/llvm-nm",
        }
        # Some NDK host prebuilts can expose a runnable clang driver while a sibling
        # tool (notably clang++) is not executable on the current host. Selecting that
        # backend makes provenance fingerprinting fail before the existing Termux LLVM
        # fallback gets a chance to run. Accept an NDK backend only when the complete
        # toolset required by the native build is present and executable.
        if all(path.is_file() and command_runs(path) for path in tools.values()):
            return {
                "backend": f"ndk:{toolchain.name}",
                "sysrootOwner": toolchain.name,
                "tools": {name: tool_fingerprint(path) for name, path in tools.items()},
            }

    machine = platform.machine().lower()
    if machine not in {"aarch64", "arm64"}:
        raise SystemExit("No runnable Android NDK clang was found for this host")
    paths = {}
    for name in ("clang", "clang++", "llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm"):
        resolved = shutil.which(name)
        if not resolved:
            raise SystemExit(f"ARM64 Termux fallback requires {name} in PATH")
        paths[name] = Path(resolved).resolve()
    return {
        "backend": "termux-native-llvm",
        "sysrootOwner": options[0].name,
        "tools": {name: tool_fingerprint(path) for name, path in paths.items()},
    }


def resolve_toolchain(ndk: Path, api: int, build_root: Path, identity: dict) -> tuple[Path, Path, str]:
    options = ndk_prebuilt_toolchains(ndk)
    backend = str(identity["backend"])
    if backend.startswith("ndk:"):
        name = backend.split(":", 1)[1]
        toolchain = next((item for item in options if item.name == name), None)
        if toolchain is None:
            raise SystemExit(f"attested NDK toolchain disappeared: {name}")
        return toolchain, toolchain / "bin", backend

    if backend != "termux-native-llvm":
        raise SystemExit(f"unsupported toolchain backend: {backend}")
    termux_tools: dict[str, Path] = {}
    for name in ("clang", "clang++", "llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm"):
        resolved = shutil.which(name)
        if not resolved:
            raise SystemExit(f"ARM64 Termux fallback requires {name} in PATH")
        path = Path(resolved).resolve()
        if tool_fingerprint(path) != identity["tools"][name]:
            raise SystemExit(f"Termux tool changed after provenance was measured: {name}")
        termux_tools[name] = path
    sysroot_owner = next((item for item in options if item.name == identity["sysrootOwner"]), None)
    if sysroot_owner is None:
        raise SystemExit("attested NDK sysroot owner disappeared")
    sysroot = sysroot_owner / "sysroot"
    if not sysroot.is_dir():
        raise SystemExit(f"NDK sysroot missing: {sysroot}")
    wrapper_bin = build_root / "termux-llvm-bin"
    wrapper_bin.mkdir(parents=True, exist_ok=True)
    target = f"aarch64-linux-android{api}"
    write_exec_wrapper(wrapper_bin / f"{target}-clang", termux_tools["clang"], [f"--target={target}", f"--sysroot={sysroot}", "-fuse-ld=lld"])
    write_exec_wrapper(wrapper_bin / f"{target}-clang++", termux_tools["clang++"], [f"--target={target}", f"--sysroot={sysroot}", "-fuse-ld=lld"])
    for name in ("llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm"):
        write_exec_wrapper(wrapper_bin / name, termux_tools[name], [])
    return sysroot_owner, wrapper_bin, backend

def run(
    command: list[str],
    cwd: Path,
    env: dict[str, str],
    *,
    label: str | None = None,
    heartbeat_seconds: int = 0,
) -> None:
    print("+", shlex.join(command), flush=True)
    if not label or heartbeat_seconds <= 0:
        subprocess.run(command, cwd=cwd, env=env, check=True)
        return

    started = time.monotonic()
    process = subprocess.Popen(command, cwd=cwd, env=env)
    while True:
        try:
            return_code = process.wait(timeout=heartbeat_seconds)
            break
        except subprocess.TimeoutExpired:
            elapsed = int(time.monotonic() - started)
            print(f"{label}: still running ({elapsed}s elapsed); existing native objects are being reused when possible.", flush=True)
    if return_code != 0:
        raise subprocess.CalledProcessError(return_code, command)


def env_flag(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", ""}


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return max(0, int(raw))
    except ValueError as exc:
        raise SystemExit(f"{name} must be an integer, got {raw!r}") from exc


@contextmanager
def build_cache_lock(lock_path: Path, heartbeat_seconds: int) -> Iterator[None]:
    """Serialize writers of the deterministic native cache across Gradle/process callers."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+")
    try:
        try:
            import fcntl
        except ImportError:
            print("warning: fcntl unavailable; native build cache cannot be process-locked on this host", flush=True)
            yield
            return

        started = time.monotonic()
        next_notice = started
        while True:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                now = time.monotonic()
                if now >= next_notice:
                    elapsed = int(now - started)
                    print(f"Waiting for another XDM FFmpeg runtime builder to release {lock_path.name} ({elapsed}s).", flush=True)
                    next_notice = now + max(5, heartbeat_seconds or 30)
                time.sleep(1)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    finally:
        handle.close()


def ensure_host_build_tools() -> None:
    """Ensure source-build host tools exist, bootstrapping them on native Termux.

    OpenSSL's Configure frontend is Perl, while FFmpeg/OpenSSL also require make and
    pkg-config. Devtool invokes this builder with system dependency/bootstrap support
    enabled, but native Termux installations are intentionally minimal and may not have
    Perl yet. Keep the bootstrap explicit, bounded to Termux, and disable-able for
    hermetic builders with XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS=0.
    """
    required = {
        "perl": "perl",
        "make": "make",
        "pkg-config": "pkg-config",
    }
    missing = [tool for tool in required if shutil.which(tool) is None]
    if not missing:
        return

    auto = os.environ.get("XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS", "1").strip().lower() not in {"0", "false", "no"}
    prefix = os.environ.get("PREFIX", "")
    pkg = shutil.which("pkg")
    is_termux = "com.termux" in prefix or (pkg is not None and "/com.termux/" in pkg)
    if auto and is_termux and pkg:
        packages = sorted({required[tool] for tool in missing})
        print("Bootstrapping missing Termux FFmpeg build tools: " + ", ".join(packages), flush=True)
        subprocess.run([pkg, "install", "-y", *packages], check=True)
        missing = [tool for tool in required if shutil.which(tool) is None]
        if not missing:
            return

    names = ", ".join(missing)
    raise SystemExit(
        f"Missing FFmpeg host build tool(s): {names}. "
        "On Termux run `pkg install perl make pkg-config`, or leave "
        "XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS enabled so the runtime builder installs them automatically."
    )


def ensure_pie(path: Path) -> None:
    data = path.read_bytes()[:64]
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise SystemExit(f"{path.name} is not ELF")
    elf_type = int.from_bytes(data[16:18], "little")
    machine = int.from_bytes(data[18:20], "little")
    if elf_type != 3 or machine != 183:
        raise SystemExit(f"{path.name} is not an AArch64 PIE/ET_DYN executable (type={elf_type}, machine={machine})")


def openssl_configuration_payload(manifest: dict, openssl_prefix: Path, toolchain_identity: dict) -> dict:
    # Do NOT pass -D__ANDROID_API__. The NDK clang driver already defines it from
    # aarch64-linux-android<api>, and redefining it caused one warning per translation unit.
    options = [
        "android-arm64",
        "no-shared",
        "no-tests",
        "no-apps",
        "no-module",
        "no-legacy",
        f"--prefix={openssl_prefix}",
        f"--openssldir={openssl_prefix / 'ssl'}",
        "-fPIC",
        "-fstack-protector-strong",
        "-D_FORTIFY_SOURCE=2",
    ]
    return {
        "schemaVersion": 1,
        "component": "openssl-configure",
        "opensslVersion": manifest["opensslVersion"],
        "opensslSourceSha256": manifest["opensslSourceSha256"],
        "androidApi": int(manifest["androidApi"]),
        "target": "android-arm64",
        "options": options,
        "toolchainIdentity": toolchain_identity,
    }


def openssl_install_payload(configuration: dict, openssl_prefix: Path) -> dict:
    artifacts = {
        "lib/libssl.a": openssl_prefix / "lib/libssl.a",
        "lib/libcrypto.a": openssl_prefix / "lib/libcrypto.a",
        "include/openssl/ssl.h": openssl_prefix / "include/openssl/ssl.h",
        "lib/pkgconfig/libssl.pc": openssl_prefix / "lib/pkgconfig/libssl.pc",
    }
    return {
        "schemaVersion": 2,
        "component": "openssl-install",
        "configuration": configuration,
        "artifactSha256": {name: sha256(path) for name, path in artifacts.items()},
    }


def openssl_install_complete(openssl_prefix: Path) -> bool:
    required = (
        openssl_prefix / "lib/libssl.a",
        openssl_prefix / "lib/libcrypto.a",
        openssl_prefix / "include/openssl/ssl.h",
        openssl_prefix / "lib/pkgconfig/libssl.pc",
    )
    return all(path.is_file() and path.stat().st_size > 0 for path in required)


def ffmpeg_configuration_payload(configure: list[str], manifest: dict, toolchain_identity: dict) -> dict:
    return {
        "schemaVersion": 1,
        "component": "ffmpeg-configure",
        "ffmpegVersion": manifest["ffmpegVersion"],
        "ffmpegSourceSha256": manifest["ffmpegSourceSha256"],
        "androidApi": int(manifest["androidApi"]),
        "configure": configure,
        "toolchainIdentity": toolchain_identity,
    }


def validate_ffmpeg_profile_flags(ff_src: Path, env: dict[str, str], flags: list[str]) -> None:
    """Ask the exact pinned configure parser to accept the profile without configuring.

    FFmpeg supports some generic --enable/--disable inversions that are intentionally not
    printed by --help. Appending --help *after* the complete profile makes configure parse
    every requested option, reject genuinely unknown/removed ones, then exit before toolchain
    checks or generated build files. This catches FFmpeg 9's removed --disable-postproc while
    preserving valid hidden inverse options such as --enable-static/--disable-shared.
    """
    result = subprocess.run(
        ["./configure", *flags, "--help"],
        cwd=ff_src,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if result.returncode != 0:
        output = result.stdout.strip()
        detail = f"\n{output}" if output else ""
        raise SystemExit(
            f"FFmpeg {manifest_version_from_source(ff_src)} rejected the pinned configure "
            f"profile before native compilation (exit {result.returncode}).{detail}"
        )

def manifest_version_from_source(ff_src: Path) -> str:
    # The extracted source directory is ffmpeg-<version>; use it only for diagnostics.
    return ff_src.name.removeprefix("ffmpeg-") or "pinned source"


def build_cache_manifest_payload(manifest: dict) -> dict:
    """Return the exact pinned manifest; provenance changes intentionally invalidate old caches."""
    return json.loads(json.dumps(manifest))


def archive_tree_matches(archive: Path, destination: Path) -> bool:
    root = destination.resolve()
    try:
        with tarfile.open(archive, "r:*") as source:
            for member in source.getmembers():
                if not member.isfile():
                    continue
                target = (destination / member.name).resolve()
                if root != target and root not in target.parents:
                    return False
                if not target.is_file() or target.stat().st_size != member.size:
                    return False
                extracted = source.extractfile(member)
                if extracted is None:
                    return False
                expected = hashlib.sha256()
                for chunk in iter(lambda: extracted.read(1024 * 1024), b""):
                    expected.update(chunk)
                if sha256(target) != expected.hexdigest():
                    return False
    except (OSError, tarfile.TarError):
        return False
    return True


def ensure_verified_source_tree(archive: Path, source_root: Path, expected_dir: Path, required_file: str) -> None:
    if expected_dir.is_dir() and not archive_tree_matches(archive, source_root):
        print(f"Discarding modified extracted source tree: {expected_dir}", flush=True)
        shutil.rmtree(expected_dir, ignore_errors=True)
    if not expected_dir.is_dir():
        safe_extract(archive, source_root)
    if not archive_tree_matches(archive, source_root):
        raise SystemExit(f"extracted source tree does not match pinned archive: {expected_dir}")
    if not (expected_dir / required_file).is_file():
        raise SystemExit(f"pinned source tree is incomplete: {expected_dir / required_file}")


def ffmpeg_build_payload(configuration: dict, ffmpeg_bin: Path, ffprobe_bin: Path) -> dict:
    return {
        "schemaVersion": 2,
        "component": "ffmpeg-build",
        "configuration": configuration,
        "ffmpegSha256": sha256(ffmpeg_bin),
        "ffprobeSha256": sha256(ffprobe_bin),
    }

def quiet_make_prefix(verbose_make: bool) -> list[str]:
    # GNU make -s suppresses echoing every compiler command but leaves compiler/linker
    # diagnostics visible. XDM_FFMPEG_VERBOSE_MAKE=1 restores the full command stream.
    return ["make"] if verbose_make else ["make", "-s"]


def build_openssl(
    *,
    manifest: dict,
    ssl_src: Path,
    openssl_prefix: Path,
    env: dict[str, str],
    jobs: int,
    heartbeat_seconds: int,
    verbose_make: bool,
    toolchain_identity: dict,
) -> None:
    configuration = openssl_configuration_payload(manifest, openssl_prefix, toolchain_identity)
    configured_marker = ssl_src / ".xdm-openssl-configured.json"
    installed_marker = ssl_src / ".xdm-openssl-installed.json"

    if openssl_install_complete(openssl_prefix):
        current_install = openssl_install_payload(configuration, openssl_prefix)
        if marker_matches(configured_marker, configuration) and marker_matches(installed_marker, current_install):
            print("OpenSSL: reusing provenance-verified deterministic cache.", flush=True)
            return
        print("OpenSSL cache provenance mismatch; rebuilding instead of self-attesting existing bytes.", flush=True)
        shutil.rmtree(openssl_prefix, ignore_errors=True)

    if not marker_matches(configured_marker, configuration):
        if (ssl_src / "Makefile").is_file():
            subprocess.run(["make", "clean"], cwd=ssl_src, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        print("OpenSSL: configuring pinned Android profile.", flush=True)
        run(["perl", "./Configure", *configuration["options"]], ssl_src, env)
        # Persist immediately after Configure succeeds. An interrupted compile therefore
        # resumes with make and does not repeat Configure merely because libssl is absent.
        atomic_json(configured_marker, configuration)
    else:
        print("OpenSSL: configuration marker matches; resuming existing object tree.", flush=True)

    make = quiet_make_prefix(verbose_make)
    # build_libs is sufficient for XDM's static TLS client dependency and avoids spending
    # time on OpenSSL command-line applications/tests, which are already disabled.
    run(
        [*make, f"-j{jobs}", "build_libs"],
        ssl_src,
        env,
        label="OpenSSL library build",
        heartbeat_seconds=heartbeat_seconds,
    )
    run(
        [*make, "install_sw"],
        ssl_src,
        env,
        label="OpenSSL install_sw",
        heartbeat_seconds=heartbeat_seconds,
    )
    if not openssl_install_complete(openssl_prefix):
        raise SystemExit("OpenSSL install_sw completed but required static libraries/headers/pkg-config metadata are missing")
    atomic_json(installed_marker, openssl_install_payload(configuration, openssl_prefix))


def build_ffmpeg(
    *,
    manifest: dict,
    ff_src: Path,
    openssl_prefix: Path,
    toolchain: Path,
    bin_dir: Path,
    cc: Path,
    cxx: Path,
    env: dict[str, str],
    jobs: int,
    heartbeat_seconds: int,
    verbose_make: bool,
    toolchain_identity: dict,
) -> tuple[Path, Path]:
    ffmpeg_bin = ff_src / "ffmpeg"
    ffprobe_bin = ff_src / "ffprobe"

    pkg = openssl_prefix / "lib/pkgconfig"
    # Never allow the host/Termux pkg-config search path to leak into a cross Android build.
    # In particular, host zlib metadata can cause an otherwise AArch64 PIE to carry
    # DT_NEEDED=libz.so.1, which Android's linker cannot satisfy. OpenSSL is the only
    # pkg-config package this pinned profile intentionally exposes; zlib then resolves
    # against the NDK sysroot through the compiler/linker fallback check.
    env["PKG_CONFIG_PATH"] = ""
    env["PKG_CONFIG_LIBDIR"] = str(pkg)
    common_ld = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now"
    android_lib_dir = toolchain / "sysroot/usr/lib/aarch64-linux-android" / str(manifest["androidApi"])
    if not android_lib_dir.is_dir():
        raise SystemExit(f"Android API sysroot libraries are missing: {android_lib_dir}")
    profile_flags = list(manifest["configureFlags"])
    validate_ffmpeg_profile_flags(ff_src, env, profile_flags)
    configure = [
        "./configure",
        f"--cc={cc}",
        f"--cxx={cxx}",
        f"--ar={bin_dir / 'llvm-ar'}",
        f"--nm={bin_dir / 'llvm-nm'}",
        f"--ranlib={bin_dir / 'llvm-ranlib'}",
        f"--strip={bin_dir / 'llvm-strip'}",
        f"--sysroot={toolchain / 'sysroot'}",
        *profile_flags,
        f"--extra-cflags=-fPIC -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -I{openssl_prefix / 'include'}",
        f"--extra-ldflags=-L{android_lib_dir} -L{openssl_prefix / 'lib'} {common_ld}",
        "--extra-libs=-ldl -lm -lz",
    ]
    configuration = ffmpeg_configuration_payload(configure, manifest, toolchain_identity)
    configured_marker = ff_src / ".xdm-ffmpeg-configured.json"
    built_marker = ff_src / ".xdm-ffmpeg-built.json"
    if ffmpeg_bin.is_file() and ffprobe_bin.is_file():
        current_build = ffmpeg_build_payload(configuration, ffmpeg_bin, ffprobe_bin)
        if marker_matches(configured_marker, configuration) and marker_matches(built_marker, current_build):
            print("FFmpeg/FFprobe: reusing provenance-verified deterministic cache.", flush=True)
            return ffmpeg_bin, ffprobe_bin
        print("FFmpeg cache provenance mismatch; relinking instead of self-attesting existing bytes.", flush=True)
        ffmpeg_bin.unlink(missing_ok=True)
        ffprobe_bin.unlink(missing_ok=True)

    if not marker_matches(configured_marker, configuration):
        if (ff_src / "Makefile").is_file():
            subprocess.run(["make", "clean"], cwd=ff_src, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        print("FFmpeg: configuring pinned Android profile.", flush=True)
        run(configure, ff_src, env)
        atomic_json(configured_marker, configuration)
    else:
        print("FFmpeg: configuration marker matches; resuming existing object tree.", flush=True)

    make = quiet_make_prefix(verbose_make)
    run(
        [*make, f"-j{jobs}", "ffmpeg", "ffprobe"],
        ff_src,
        env,
        label="FFmpeg/FFprobe build",
        heartbeat_seconds=heartbeat_seconds,
    )
    if not ffmpeg_bin.is_file() or not ffprobe_bin.is_file():
        raise SystemExit("FFmpeg build finished without producing both ffmpeg and ffprobe")
    atomic_json(built_marker, ffmpeg_build_payload(configuration, ffmpeg_bin, ffprobe_bin))
    return ffmpeg_bin, ffprobe_bin


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-pinned", action="store_true", help="download, verify, compile and install the pinned runtime")
    parser.add_argument("--jobs", type=int, default=max(1, min(4, os.cpu_count() or 1)))
    parser.add_argument("--force", action="store_true", help="discard the deterministic native object cache before rebuilding")
    parser.add_argument(
        "--heartbeat-seconds",
        type=int,
        default=env_int("XDM_FFMPEG_BUILD_HEARTBEAT_SECONDS", 30),
        help="emit concise native-build progress while make is quiet (0 disables)",
    )
    parser.add_argument(
        "--verbose-make",
        action="store_true",
        default=env_flag("XDM_FFMPEG_VERBOSE_MAKE"),
        help="show full make/compiler command lines instead of quiet resumable output",
    )
    args = parser.parse_args()
    if not args.build_pinned:
        parser.error("--build-pinned is required")
    if args.jobs < 1:
        parser.error("--jobs must be >= 1")
    if args.heartbeat_seconds < 0:
        parser.error("--heartbeat-seconds must be >= 0")

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    ndk = detect_ndk(manifest["ndkVersion"])
    api = int(manifest["androidApi"])
    measured_ndk = ndk_provenance(ndk, manifest["ndkVersion"])
    toolchain_identity = select_toolchain_identity(ndk, api)

    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    cache_identity = {
        "schemaVersion": 2,
        "manifest": build_cache_manifest_payload(manifest),
        "ndk": measured_ndk,
        "toolchain": toolchain_identity,
        "hostMachine": platform.machine().lower(),
    }
    build_key = hashlib.sha256(json.dumps(cache_identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:16]
    build_root = CACHE_ROOT / f"build-{build_key}"
    lock_path = CACHE_ROOT / f"build-{build_key}.lock"

    with build_cache_lock(lock_path, args.heartbeat_seconds):
        if args.force:
            shutil.rmtree(build_root, ignore_errors=True)

        toolchain, bin_dir, toolchain_backend = resolve_toolchain(ndk, api, build_root, toolchain_identity)
        cc = bin_dir / f"aarch64-linux-android{api}-clang"
        cxx = bin_dir / f"aarch64-linux-android{api}-clang++"
        if not cc.is_file() or not cxx.is_file():
            raise SystemExit(f"Android compiler for API {api} is missing: {cc}")
        print(f"Android NDK: {ndk}")
        print(f"FFmpeg host toolchain backend: {toolchain_backend}")
        print(f"Native build cache: {build_root}")
        ensure_host_build_tools()

        ff_archive = download(manifest["ffmpegSourceUrl"], CACHE_ROOT / f"ffmpeg-{manifest['ffmpegVersion']}.tar.xz", manifest["ffmpegSourceSha256"])
        ssl_archive = download(manifest["opensslSourceUrl"], CACHE_ROOT / f"openssl-{manifest['opensslVersion']}.tar.gz", manifest["opensslSourceSha256"])

        source_root = build_root / "src"
        openssl_prefix = build_root / "openssl-prefix"
        ff_src = source_root / f"ffmpeg-{manifest['ffmpegVersion']}"
        ssl_src = source_root / f"openssl-{manifest['opensslVersion']}"
        # Reuse only source trees whose original archive members still match the pinned
        # source archives. Generated build products are allowed, modified source inputs are not.
        ensure_verified_source_tree(ff_archive, source_root, ff_src, "configure")
        ensure_verified_source_tree(ssl_archive, source_root, ssl_src, "Configure")

        env = os.environ.copy()
        env.update({
            "ANDROID_NDK_ROOT": str(ndk),
            "PATH": f"{bin_dir}:{env.get('PATH', '')}",
            "CC": str(cc),
            "CXX": str(cxx),
            "AR": str(bin_dir / "llvm-ar"),
            "RANLIB": str(bin_dir / "llvm-ranlib"),
            "STRIP": str(bin_dir / "llvm-strip"),
        })

        build_openssl(
            manifest=manifest,
            ssl_src=ssl_src,
            openssl_prefix=openssl_prefix,
            env=env,
            jobs=args.jobs,
            heartbeat_seconds=args.heartbeat_seconds,
            verbose_make=args.verbose_make,
            toolchain_identity=toolchain_identity,
        )
        ffmpeg_bin, ffprobe_bin = build_ffmpeg(
            manifest=manifest,
            ff_src=ff_src,
            openssl_prefix=openssl_prefix,
            toolchain=toolchain,
            bin_dir=bin_dir,
            cc=cc,
            cxx=cxx,
            env=env,
            jobs=args.jobs,
            heartbeat_seconds=args.heartbeat_seconds,
            verbose_make=args.verbose_make,
            toolchain_identity=toolchain_identity,
        )

        targets = {
            "ffmpeg": ROOT / manifest["ffmpegPackagedPath"],
            "ffprobe": ROOT / manifest["ffprobePackagedPath"],
        }
        binaries = {"ffmpeg": ffmpeg_bin, "ffprobe": ffprobe_bin}
        for name, source in binaries.items():
            ensure_pie(source)
            target = targets[name]
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_suffix(target.suffix + ".part")
            shutil.copy2(source, tmp)
            tmp.chmod(tmp.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            run([str(bin_dir / "llvm-strip"), "--strip-unneeded", str(tmp)], build_root, env)
            ensure_pie(tmp)
            try:
                metadata = validate_android_runtime_file(tmp)
            except ElfPolicyError as error:
                raise SystemExit(f"{name} runtime dependency policy failed: {error}") from error
            tmp.replace(target)

        license_targets = {
            "ffmpeg": ROOT / manifest["ffmpegLicenseAsset"],
            "openssl": ROOT / manifest["opensslLicenseAsset"],
        }
        license_sources = {
            "ffmpeg": ff_src / "COPYING.LGPLv2.1",
            "openssl": ssl_src / "LICENSE.txt",
        }
        for name, source in license_sources.items():
            if not source.is_file():
                raise SystemExit(f"required {name} license file is missing from pinned source: {source}")
            target = license_targets[name]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

        lock = {
            "schemaVersion": 2,
            "component": manifest["component"],
            "ffmpegVersion": manifest["ffmpegVersion"],
            "opensslVersion": manifest["opensslVersion"],
            "abi": manifest["abi"],
            "androidApi": api,
            "ndkVersion": manifest["ndkVersion"],
            "ndkRevision": measured_ndk["revision"],
            "ndkSourcePropertiesSha256": measured_ndk["sourcePropertiesSha256"],
            "toolchainBackend": toolchain_backend,
            "toolchainIdentity": toolchain_identity,
            "buildCacheIdentitySha256": hashlib.sha256(json.dumps(cache_identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
            "ffmpegSourceSha256": manifest["ffmpegSourceSha256"],
            "opensslSourceSha256": manifest["opensslSourceSha256"],
            "ffmpegBinarySha256": sha256(targets["ffmpeg"]),
            "ffprobeBinarySha256": sha256(targets["ffprobe"]),
            "ffmpegBinaryBytes": targets["ffmpeg"].stat().st_size,
            "ffprobeBinaryBytes": targets["ffprobe"].stat().st_size,
            "requiredLoadAlignment": manifest["requiredLoadAlignment"],
            "gplEnabled": False,
            "nonfreeEnabled": False,
            "httpsProvider": f"OpenSSL {manifest['opensslVersion']}",
            "buildProfile": manifest["buildProfile"],
            "configureFlags": manifest["configureFlags"],
            "dynamicDependencyPolicy": manifest.get("dynamicDependencyPolicy", "android-unversioned-sonames-v1"),
            "ffmpegNeededLibraries": list(validate_android_runtime_file(targets["ffmpeg"])["needed"]),
            "ffprobeNeededLibraries": list(validate_android_runtime_file(targets["ffprobe"])["needed"]),
            "ffmpegLicenseSha256": sha256(license_targets["ffmpeg"]),
            "opensslLicenseSha256": sha256(license_targets["openssl"]),
        }
        atomic_json(LOCK_PATH, lock)
        print(f"Installed {targets['ffmpeg'].relative_to(ROOT)}")
        print(f"Installed {targets['ffprobe'].relative_to(ROOT)}")
        print(f"Runtime lock: {LOCK_PATH.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
