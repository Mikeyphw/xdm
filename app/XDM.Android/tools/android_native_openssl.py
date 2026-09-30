#!/usr/bin/env python3
"""Shared, provenance-qualified OpenSSL build cache for XDM Android native runtimes.

FFmpeg and aria2 intentionally consume the same static OpenSSL build when their
source, Android API, NDK and measured toolchain identities are identical.  The
shared cache is consumer-neutral and process-locked.  It can adopt a completed
legacy FFmpeg/aria2 OpenSSL cache and can resume the in-progress in-tree OpenSSL
build produced by pre-RM08-v4 builders before importing it into the shared cache.

Fresh shared builds use OpenSSL's supported out-of-tree build mode so the pinned
source extraction remains byte-for-byte verifiable while generated files and
objects live in a separate work directory.
"""
from __future__ import annotations

from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Callable, Iterator

SCHEMA_VERSION = 1
DEFAULT_CACHE_ROOT = Path.home() / ".cache/xdm/android-native-deps"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def stable_hash(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def atomic_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    tmp = Path(name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
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


def safe_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with tarfile.open(archive, "r:*") as source:
        members = source.getmembers()
        for member in members:
            target = (destination / member.name).resolve()
            if root != target and root not in target.parents:
                raise SystemExit(f"unsafe OpenSSL source archive member: {member.name}")
        try:
            source.extractall(destination, filter="data")
        except TypeError:  # Python < 3.12 compatibility
            source.extractall(destination)


def _download(url: str, target: Path, expected: str) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and sha256(target).lower() == expected.lower():
        return target
    target.unlink(missing_ok=True)
    fd, name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    os.close(fd)
    tmp = Path(name)
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "XDM-Android-shared-OpenSSL/1"})
        with urllib.request.urlopen(request, timeout=180) as response, tmp.open("wb") as output:
            shutil.copyfileobj(response, output)
        actual = sha256(tmp)
        if actual.lower() != expected.lower():
            raise SystemExit(f"OpenSSL source SHA-256 mismatch: expected {expected}, got {actual}")
        tmp.replace(target)
    finally:
        tmp.unlink(missing_ok=True)
    return target


def cache_root() -> Path:
    return Path(os.environ.get("XDM_ANDROID_NATIVE_DEPS_CACHE", DEFAULT_CACHE_ROOT)).expanduser()


def _configure_profile() -> list[str]:
    return [
        "android-arm64",
        "no-shared",
        "no-tests",
        "no-apps",
        "no-module",
        "no-legacy",
        "-fPIC",
        "-fstack-protector-strong",
        "-D_FORTIFY_SOURCE=2",
    ]


def dependency_identity(
    *,
    version: str,
    source_url: str,
    source_sha256: str,
    android_api: int,
    ndk_version: str,
    ndk_provenance: dict,
    toolchain_identity: dict,
) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "component": "xdm-android-shared-openssl",
        "version": version,
        "sourceUrl": source_url,
        "sourceSha256": source_sha256.lower(),
        "abi": "arm64-v8a",
        "androidApi": int(android_api),
        "ndkVersion": ndk_version,
        "ndkRevision": ndk_provenance.get("revision"),
        "ndkSourcePropertiesSha256": ndk_provenance.get("sourcePropertiesSha256"),
        "target": "android-arm64",
        "configureProfile": _configure_profile(),
        "toolchainIdentity": toolchain_identity,
    }


def build_key(identity: dict) -> str:
    return stable_hash(identity)[:16]


def layout(identity: dict) -> dict[str, Path]:
    root = cache_root() / "openssl" / f"{identity['version']}-{build_key(identity)}"
    return {
        "root": root,
        "archive": cache_root() / "downloads" / f"openssl-{identity['version']}-{identity['sourceSha256'][:16]}.tar.gz",
        "source_root": root / "source",
        "source": root / "source" / f"openssl-{identity['version']}",
        "build": root / "build",
        "prefix": root / "prefix",
        "configured": root / "build" / ".xdm-openssl-configured.json",
        "installed": root / "build" / ".xdm-openssl-installed.json",
        "provenance": root / "provenance.json",
        "lock": cache_root() / "locks" / f"openssl-{build_key(identity)}.lock",
    }


def configuration_payload(identity: dict, prefix: Path) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "component": "xdm-android-shared-openssl-configure",
        "dependencyIdentity": identity,
        "options": [
            identity["target"],
            "no-shared",
            "no-tests",
            "no-apps",
            "no-module",
            "no-legacy",
            f"--prefix={prefix}",
            f"--openssldir={prefix / 'ssl'}",
            "-fPIC",
            "-fstack-protector-strong",
            "-D_FORTIFY_SOURCE=2",
        ],
    }


def install_complete(prefix: Path) -> bool:
    required = (
        prefix / "lib/libssl.a",
        prefix / "lib/libcrypto.a",
        prefix / "include/openssl/ssl.h",
        prefix / "lib/pkgconfig/libssl.pc",
    )
    return all(path.is_file() and path.stat().st_size > 0 for path in required)


def install_payload(identity: dict, configuration: dict, prefix: Path, *, origin: str) -> dict:
    artifacts = {
        "lib/libssl.a": prefix / "lib/libssl.a",
        "lib/libcrypto.a": prefix / "lib/libcrypto.a",
        "include/openssl/ssl.h": prefix / "include/openssl/ssl.h",
        "lib/pkgconfig/libssl.pc": prefix / "lib/pkgconfig/libssl.pc",
    }
    return {
        "schemaVersion": SCHEMA_VERSION,
        "component": "xdm-android-shared-openssl-install",
        "dependencyIdentity": identity,
        "configuration": configuration,
        "origin": origin,
        "artifactSha256": {name: sha256(path) for name, path in artifacts.items()},
    }


def _installed_marker_valid(marker: Path, prefix: Path, expected_identity: dict | None = None) -> bool:
    if not marker.is_file() or not install_complete(prefix):
        return False
    try:
        payload = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    artifacts = payload.get("artifactSha256") or {}
    for rel in ("lib/libssl.a", "lib/libcrypto.a", "include/openssl/ssl.h", "lib/pkgconfig/libssl.pc"):
        path = prefix / rel
        if artifacts.get(rel) != sha256(path):
            return False
    if expected_identity is not None and payload.get("dependencyIdentity") != expected_identity:
        return False
    return True


def provenance(identity: dict, prefix: Path, *, origin: str) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "component": "xdm-android-shared-openssl",
        "dependencyIdentity": identity,
        "dependencyIdentitySha256": stable_hash(identity),
        "cacheKey": build_key(identity),
        "cacheRoot": str(layout(identity)["root"]),
        "origin": origin,
        "libsslSha256": sha256(prefix / "lib/libssl.a"),
        "libcryptoSha256": sha256(prefix / "lib/libcrypto.a"),
        "sslHeaderSha256": sha256(prefix / "include/openssl/ssl.h"),
    }


@contextmanager
def cache_lock(path: Path, heartbeat_seconds: int) -> Iterator[None]:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("a+")
    try:
        started = time.monotonic()
        next_notice = started
        while True:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                now = time.monotonic()
                if now >= next_notice:
                    print(f"Waiting for shared OpenSSL cache lock {path.name} ({int(now-started)}s).", flush=True)
                    next_notice = now + max(5, heartbeat_seconds or 30)
                time.sleep(1)
        yield
    finally:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()


def _run(command: list[str], cwd: Path, env: dict[str, str], *, label: str, heartbeat_seconds: int) -> None:
    print("+ " + " ".join(str(item) for item in command), flush=True)
    process = subprocess.Popen(command, cwd=cwd, env=env)
    started = time.monotonic()
    while True:
        try:
            code = process.wait(timeout=heartbeat_seconds if heartbeat_seconds > 0 else None)
            break
        except subprocess.TimeoutExpired:
            print(f"{label}: still running ({int(time.monotonic()-started)}s elapsed); shared objects are retained for resume.", flush=True)
    if code != 0:
        raise subprocess.CalledProcessError(code, command)


def _legacy_semantic_configuration_matches(raw: dict, identity: dict) -> bool:
    if raw.get("opensslVersion") != identity["version"]:
        return False
    if str(raw.get("opensslSourceSha256", "")).lower() != identity["sourceSha256"]:
        return False
    if int(raw.get("androidApi", -1)) != identity["androidApi"]:
        return False
    if raw.get("target") != identity["target"]:
        return False
    if raw.get("toolchainIdentity") != identity["toolchainIdentity"]:
        return False
    options = [item for item in raw.get("options", []) if not str(item).startswith(("--prefix=", "--openssldir="))]
    return options == _configure_profile()


def _seed_archive_from_legacy(version: str, expected_sha256: str, target: Path) -> None:
    home = Path.home()
    candidates = [
        Path(os.environ.get("XDM_FFMPEG_BUILD_CACHE", home / ".cache/xdm/ffmpeg-runtime")).expanduser() / f"openssl-{version}.tar.gz",
        Path(os.environ.get("XDM_ARIA2_SOURCE_CACHE_DIR", home / ".cache/xdm/aria2-source-build")).expanduser() / "downloads" / f"openssl-{version}.tar.gz",
    ]
    for source in candidates:
        if source.is_file() and sha256(source).lower() == expected_sha256.lower():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            print(f"Shared OpenSSL: seeded pinned source archive from {source}.", flush=True)
            return


def _legacy_candidates(version: str) -> list[tuple[str, Path, Path, Path]]:
    home = Path.home()
    roots = [
        ("legacy-ffmpeg", Path(os.environ.get("XDM_FFMPEG_BUILD_CACHE", home / ".cache/xdm/ffmpeg-runtime")).expanduser(), "openssl-prefix"),
        ("legacy-aria2", Path(os.environ.get("XDM_ARIA2_SOURCE_CACHE_DIR", home / ".cache/xdm/aria2-source-build")).expanduser(), "prefix"),
    ]
    result: list[tuple[str, Path, Path, Path]] = []
    for origin, root, prefix_name in roots:
        if not root.is_dir():
            continue
        for build in sorted(root.glob("build-*"), key=lambda item: item.stat().st_mtime if item.exists() else 0, reverse=True):
            source = build / "src" / f"openssl-{version}"
            prefix = build / prefix_name
            configured = source / ".xdm-openssl-configured.json"
            if source.is_dir() and configured.is_file():
                result.append((origin, source, prefix, configured))
    return result


def _copy_shared_install(source_prefix: Path, target_prefix: Path) -> None:
    shutil.rmtree(target_prefix, ignore_errors=True)
    (target_prefix / "lib/pkgconfig").mkdir(parents=True, exist_ok=True)
    (target_prefix / "include").mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_prefix / "include/openssl", target_prefix / "include/openssl")
    for name in ("libssl.a", "libcrypto.a"):
        shutil.copy2(source_prefix / "lib" / name, target_prefix / "lib" / name)
    for name in ("libssl.pc", "libcrypto.pc", "openssl.pc"):
        src = source_prefix / "lib/pkgconfig" / name
        if not src.is_file():
            continue
        content = src.read_text(encoding="utf-8")
        content = content.replace(str(source_prefix), str(target_prefix))
        (target_prefix / "lib/pkgconfig" / name).write_text(content, encoding="utf-8")


def _legacy_installed_valid(source: Path, prefix: Path, configuration: dict) -> bool:
    marker = source / ".xdm-openssl-installed.json"
    if not marker.is_file() or not install_complete(prefix):
        return False
    try:
        payload = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if payload.get("configuration") != configuration:
        return False
    expected = payload.get("artifactSha256") or {}
    return all(expected.get(rel) == sha256(prefix / rel) for rel in (
        "lib/libssl.a", "lib/libcrypto.a", "include/openssl/ssl.h", "lib/pkgconfig/libssl.pc"
    ))


def _try_adopt_legacy(identity: dict, shared_prefix: Path, env: dict[str, str], jobs: int, heartbeat_seconds: int, verbose_make: bool) -> str | None:
    # Prefer a completed cache. This normally lets an already-qualified FFmpeg OpenSSL
    # build seed aria2 without compiling OpenSSL at all.
    candidates = _legacy_candidates(identity["version"])
    parsed: list[tuple[str, Path, Path, dict]] = []
    for origin, source, prefix, marker in candidates:
        try:
            configuration = json.loads(marker.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if _legacy_semantic_configuration_matches(configuration, identity):
            parsed.append((origin, source, prefix, configuration))
    for origin, source, prefix, configuration in parsed:
        if _legacy_installed_valid(source, prefix, configuration):
            _copy_shared_install(prefix, shared_prefix)
            print(f"Shared OpenSSL: adopted completed {origin} cache from {prefix.parent}.", flush=True)
            return f"adopted-{origin}"

    # If no complete legacy build exists, preserve work from an interrupted in-tree
    # build (notably the pre-v4 aria2 OpenSSL compile) and finish it in place once.
    for origin, source, prefix, configuration in parsed:
        if not (source / "Makefile").is_file():
            continue
        make = ["make"] if verbose_make else ["make", "-s"]
        print(f"Shared OpenSSL: resuming compatible in-progress {origin} object tree at {source}.", flush=True)
        _run([*make, f"-j{jobs}", "build_libs"], source, env, label="Legacy OpenSSL library build", heartbeat_seconds=heartbeat_seconds)
        _run([*make, "install_sw"], source, env, label="Legacy OpenSSL install_sw", heartbeat_seconds=heartbeat_seconds)
        if not install_complete(prefix):
            raise SystemExit(f"resumed legacy OpenSSL build did not produce a complete install: {prefix}")
        # The old builder normally writes its installed marker only after install_sw.
        # An interrupted migration owns that final qualification here instead.
        artifacts = {
            rel: sha256(prefix / rel) for rel in (
                "lib/libssl.a", "lib/libcrypto.a", "include/openssl/ssl.h", "lib/pkgconfig/libssl.pc"
            )
        }
        atomic_json(source / ".xdm-openssl-installed.json", {
            "schemaVersion": 2,
            "component": "openssl-install",
            "configuration": configuration,
            "artifactSha256": artifacts,
        })
        _copy_shared_install(prefix, shared_prefix)
        print(f"Shared OpenSSL: resumed and adopted {origin} cache.", flush=True)
        return f"resumed-{origin}"
    return None


def build_or_reuse_shared_openssl(
    *,
    version: str,
    source_url: str,
    source_sha256: str,
    android_api: int,
    ndk_version: str,
    ndk_provenance: dict,
    toolchain_identity: dict,
    env: dict[str, str],
    jobs: int,
    heartbeat_seconds: int = 30,
    verbose_make: bool = False,
) -> tuple[Path, Path, dict]:
    """Return (prefix, immutable_source_dir, shared provenance)."""
    identity = dependency_identity(
        version=version,
        source_url=source_url,
        source_sha256=source_sha256,
        android_api=android_api,
        ndk_version=ndk_version,
        ndk_provenance=ndk_provenance,
        toolchain_identity=toolchain_identity,
    )
    paths = layout(identity)
    paths["root"].mkdir(parents=True, exist_ok=True)
    configuration = configuration_payload(identity, paths["prefix"])

    with cache_lock(paths["lock"], heartbeat_seconds):
        if _installed_marker_valid(paths["installed"], paths["prefix"], identity):
            result = provenance(identity, paths["prefix"], origin="shared-cache")
            atomic_json(paths["provenance"], result)
            print(f"Shared OpenSSL: reusing qualified cache {paths['root']}.", flush=True)
            return paths["prefix"], paths["source"], result

        origin = _try_adopt_legacy(identity, paths["prefix"], env, jobs, heartbeat_seconds, verbose_make)
        if origin:
            # Materialize an immutable pinned source tree for licensing and future
            # provenance checks even when the static libraries came from legacy cache.
            _seed_archive_from_legacy(version, source_sha256, paths["archive"])
            archive = _download(source_url, paths["archive"], source_sha256)
            if paths["source"].is_dir() and not archive_tree_matches(archive, paths["source_root"]):
                shutil.rmtree(paths["source_root"], ignore_errors=True)
            if not paths["source"].is_dir():
                paths["source_root"].mkdir(parents=True, exist_ok=True)
                safe_extract(archive, paths["source_root"])
            if not archive_tree_matches(archive, paths["source_root"]):
                raise SystemExit("shared OpenSSL immutable source extraction does not match pinned archive")
            atomic_json(paths["configured"], configuration)
            atomic_json(paths["installed"], install_payload(identity, configuration, paths["prefix"], origin=origin))
            result = provenance(identity, paths["prefix"], origin=origin)
            atomic_json(paths["provenance"], result)
            return paths["prefix"], paths["source"], result

        _seed_archive_from_legacy(version, source_sha256, paths["archive"])
        archive = _download(source_url, paths["archive"], source_sha256)
        if paths["source"].is_dir() and not archive_tree_matches(archive, paths["source_root"]):
            print("Shared OpenSSL: immutable source tree drifted; re-extracting pinned archive.", flush=True)
            shutil.rmtree(paths["source_root"], ignore_errors=True)
        if not paths["source"].is_dir():
            paths["source_root"].mkdir(parents=True, exist_ok=True)
            safe_extract(archive, paths["source_root"])
        if not archive_tree_matches(archive, paths["source_root"]):
            raise SystemExit("shared OpenSSL source tree does not match pinned archive")
        if not (paths["source"] / "Configure").is_file():
            raise SystemExit("shared OpenSSL source tree is incomplete")

        if not marker_matches(paths["configured"], configuration):
            shutil.rmtree(paths["build"], ignore_errors=True)
            paths["build"].mkdir(parents=True, exist_ok=True)
            print("Shared OpenSSL: configuring fresh out-of-tree Android build.", flush=True)
            _run(
                ["perl", str(paths["source"] / "Configure"), *configuration["options"]],
                paths["build"], env, label="Shared OpenSSL Configure", heartbeat_seconds=heartbeat_seconds,
            )
            atomic_json(paths["configured"], configuration)
        else:
            print("Shared OpenSSL: configuration marker matches; resuming shared object tree.", flush=True)

        make = ["make"] if verbose_make else ["make", "-s"]
        _run([*make, f"-j{jobs}", "build_libs"], paths["build"], env, label="Shared OpenSSL library build", heartbeat_seconds=heartbeat_seconds)
        _run([*make, "install_sw"], paths["build"], env, label="Shared OpenSSL install_sw", heartbeat_seconds=heartbeat_seconds)
        if not install_complete(paths["prefix"]):
            raise SystemExit("shared OpenSSL install_sw completed without required static libraries/headers/pkg-config metadata")
        atomic_json(paths["installed"], install_payload(identity, configuration, paths["prefix"], origin="shared-build"))
        result = provenance(identity, paths["prefix"], origin="shared-build")
        atomic_json(paths["provenance"], result)
        return paths["prefix"], paths["source"], result


# Compatibility helpers retained for focused tests and callers that inspect the
# old FFmpeg builder API. They describe the legacy in-tree configuration only;
# production FFmpeg/aria2 construction uses build_or_reuse_shared_openssl().
def legacy_configuration_payload(manifest: dict, prefix: Path, toolchain_identity: dict) -> dict:
    return {
        "schemaVersion": 2,
        "component": "openssl-configure",
        "opensslVersion": manifest["opensslVersion"],
        "opensslSourceSha256": manifest["opensslSourceSha256"],
        "androidApi": int(manifest["androidApi"]),
        "target": "android-arm64",
        "options": [
            "android-arm64", "no-shared", "no-tests", "no-apps", "no-module", "no-legacy",
            f"--prefix={prefix}", f"--openssldir={prefix / 'ssl'}",
            "-fPIC", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
        ],
        "toolchainIdentity": toolchain_identity,
        "toolchainAdapter": "termux-standalone-v1" if toolchain_identity.get("backend") == "termux-native-llvm" else "ndk-native",
    }
