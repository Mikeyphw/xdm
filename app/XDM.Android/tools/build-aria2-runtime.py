#!/usr/bin/env python3
"""Build and attest the pinned ARM64 Android aria2 runtime from source.

The Android/Termux NDK discovery and OpenSSL adapter deliberately reuse XDM's already-sealed
FFmpeg native toolchain authority.  aria2 owns its source pins, dependency graph, build recipe,
cache identity, final ELF policy and lock independently.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

from android_elf_runtime import ElfPolicyError, validate_android_runtime_file

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.json"
LOCK_PATH = ROOT / "transfer-aria2/runtime/aria2-runtime.lock.json"
CACHE_ROOT = Path(os.environ.get("XDM_ARIA2_SOURCE_CACHE_DIR", Path.home() / ".cache/xdm/aria2-source-build")).expanduser()
TOOLCHAIN_AUTHORITY = ROOT / "tools/install-ffmpeg-runtime.py"


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


def load_toolchain_authority():
    spec = importlib.util.spec_from_file_location("xdm_native_toolchain_authority", TOOLCHAIN_AUTHORITY)
    if spec is None or spec.loader is None:
        raise SystemExit("could not load XDM native toolchain authority")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def download(source: dict, target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    expected = source["sha256"].lower()
    if target.is_file() and sha256(target).lower() == expected:
        print(f"Reusing pinned source: {target}", flush=True)
        return target
    target.unlink(missing_ok=True)
    fd, name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    os.close(fd)
    tmp = Path(name)
    try:
        request = urllib.request.Request(source["url"], headers={"User-Agent": "XDM-Android-aria2-source-builder/1"})
        with urllib.request.urlopen(request, timeout=180) as response, tmp.open("wb") as output:
            shutil.copyfileobj(response, output)
        actual = sha256(tmp)
        if actual.lower() != expected:
            raise SystemExit(f"source SHA-256 mismatch for {source['url']}: {actual}")
        tmp.replace(target)
    finally:
        tmp.unlink(missing_ok=True)
    return target


def safe_extract(archive: Path, destination: Path) -> Path:
    shutil.rmtree(destination, ignore_errors=True)
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:*") as tf:
        root = destination.resolve()
        members = tf.getmembers()
        for member in members:
            candidate = (destination / member.name).resolve()
            if root != candidate and root not in candidate.parents:
                raise SystemExit(f"unsafe source archive member: {member.name}")
        tf.extractall(destination)
    roots = [p for p in destination.iterdir() if p.is_dir()]
    if len(roots) != 1:
        raise SystemExit(f"expected one source root in {archive.name}, found {[p.name for p in roots]}")
    return roots[0]


def run(command: list[str], cwd: Path, env: dict[str, str], *, label: str) -> None:
    print(f"{label}: {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def configure_make_install(src: Path, prefix: Path, env: dict[str, str], args: list[str], jobs: int, *, label: str, required_rel: str) -> None:
    marker = src / ".xdm-aria2-dependency-installed.json"
    recipe = {"label": label, "args": args, "prefix": str(prefix), "cc": env.get("CC"), "cxx": env.get("CXX")}
    if marker.is_file():
        try:
            if json.loads(marker.read_text(encoding="utf-8")) == recipe and (prefix / required_rel).is_file():
                print(f"{label}: reusing deterministic build cache", flush=True)
                return
        except (json.JSONDecodeError, OSError):
            pass
    if (src / "Makefile").is_file():
        subprocess.run(["make", "distclean"], cwd=src, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    run(["./configure", *args], src, env, label=f"{label} configure")
    run(["make", f"-j{jobs}"], src, env, label=f"{label} build")
    run(["make", "install"], src, env, label=f"{label} install")
    atomic_json(marker, recipe)


def build_sources(manifest: dict, build_root: Path, jobs: int) -> tuple[Path, dict, dict, dict]:
    authority = load_toolchain_authority()
    ndk = authority.detect_ndk(manifest["ndkVersion"])
    ndk_info = authority.ndk_provenance(ndk, manifest["ndkVersion"])
    api = int(manifest["androidApi"])
    toolchain_identity = authority.select_toolchain_identity(ndk, api)
    toolchain, bin_dir, backend = authority.resolve_toolchain(ndk, api, build_root, toolchain_identity)
    authority.ensure_host_build_tools()
    cc, cxx, compiler_identity = authority.prepare_ffmpeg_compilers(
        toolchain=toolchain, bin_dir=bin_dir, build_root=build_root, api=api, backend=backend,
    )

    archives: dict[str, Path] = {}
    source_roots: dict[str, Path] = {}
    source_root = build_root / "src"
    source_root.mkdir(parents=True, exist_ok=True)
    for name, source in manifest["sources"].items():
        suffix = ".tar.xz" if source["url"].endswith(".tar.xz") else ".tar.gz"
        archive = download(source, CACHE_ROOT / "downloads" / f"{name}-{source['version']}{suffix}")
        archives[name] = archive
        expected = source_root / source["sourceDir"]
        authority.ensure_verified_source_tree(archive, source_root, expected, source["requiredFile"])
        source_roots[name] = expected

    prefix = build_root / "prefix"
    prefix.mkdir(parents=True, exist_ok=True)
    target = f"aarch64-linux-android{api}"
    common_cflags = "-O2 -fPIC -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2"
    common_ldflags = "-fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now"
    env = os.environ.copy()
    env.update({
        "ANDROID_NDK_ROOT": str(ndk),
        "PATH": f"{bin_dir}:{env.get('PATH', '')}",
        "CC": str(cc), "CXX": str(cxx),
        "AR": str(bin_dir / "llvm-ar"), "RANLIB": str(bin_dir / "llvm-ranlib"),
        "STRIP": str(bin_dir / "llvm-strip"), "NM": str(bin_dir / "llvm-nm"),
        "CFLAGS": common_cflags, "CXXFLAGS": common_cflags,
        "CPPFLAGS": f"-I{prefix / 'include'}",
        "LDFLAGS": f"-L{prefix / 'lib'} {common_ldflags}",
        "PKG_CONFIG_PATH": "", "PKG_CONFIG_LIBDIR": str(prefix / "lib/pkgconfig"),
    })

    openssl_manifest = {
        "opensslVersion": manifest["sources"]["openssl"]["version"],
        "opensslSourceSha256": manifest["sources"]["openssl"]["sha256"],
        "androidApi": api,
    }
    openssl_env = authority.prepare_openssl_environment(
        env=env, build_root=build_root, toolchain=toolchain, bin_dir=bin_dir, api=api, backend=backend,
    )
    authority.build_openssl(
        manifest=openssl_manifest, ssl_src=source_roots["openssl"], openssl_prefix=prefix,
        env=openssl_env, jobs=jobs, heartbeat_seconds=30, verbose_make=False,
        toolchain_identity=toolchain_identity,
    )

    # zlib uses its compact configure script rather than autoconf --host.
    zlib = source_roots["zlib"]
    zmarker = zlib / ".xdm-aria2-dependency-installed.json"
    zrecipe = {"label": "zlib", "prefix": str(prefix), "cc": str(cc)}
    if not zmarker.is_file() or json.loads(zmarker.read_text(encoding="utf-8")) != zrecipe or not (prefix / "lib/libz.a").is_file():
        if (zlib / "Makefile").is_file():
            subprocess.run(["make", "distclean"], cwd=zlib, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        zenv = env.copy(); zenv["CHOST"] = "aarch64-linux-android"
        run(["./configure", "--static", f"--prefix={prefix}"], zlib, zenv, label="zlib configure")
        run(["make", f"-j{jobs}"], zlib, zenv, label="zlib build")
        run(["make", "install"], zlib, zenv, label="zlib install")
        atomic_json(zmarker, zrecipe)

    host = "aarch64-linux-android"
    configure_make_install(source_roots["expat"], prefix, env, [f"--host={host}", f"--prefix={prefix}", "--disable-shared", "--enable-static", "--without-xmlwf", "--without-docbook", "--without-tests", "--without-examples"], jobs, label="Expat", required_rel="lib/libexpat.a")
    configure_make_install(source_roots["cares"], prefix, env, [f"--host={host}", f"--prefix={prefix}", "--disable-shared", "--enable-static", "--disable-tests"], jobs, label="c-ares", required_rel="lib/libcares.a")
    configure_make_install(source_roots["libssh2"], prefix, env, [f"--host={host}", f"--prefix={prefix}", "--disable-shared", "--enable-static", "--with-openssl", "--with-libz", "--disable-examples-build"], jobs, label="libssh2", required_rel="lib/libssh2.a")

    aria = source_roots["aria2"]
    aria_marker = aria / ".xdm-aria2-built.json"
    configure = [
        f"--host={host}", f"--prefix={prefix}", *manifest["configureFlags"],
    ]
    recipe = {"schemaVersion": 1, "configure": configure, "cflags": env["CFLAGS"], "ldflags": env["LDFLAGS"], "toolchain": toolchain_identity, "compiler": compiler_identity}
    binary = aria / "src/aria2c"
    if not (binary.is_file() and aria_marker.is_file() and json.loads(aria_marker.read_text(encoding="utf-8")) == recipe):
        if (aria / "Makefile").is_file():
            subprocess.run(["make", "distclean"], cwd=aria, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        aria_env = env.copy()
        aria_env["ARIA2_STATIC"] = "yes"
        aria_env["LIBS"] = "-ldl -lm"
        run(["./configure", *configure], aria, aria_env, label="aria2 configure")
        run(["make", f"-j{jobs}", "aria2c"], aria, aria_env, label="aria2 build")
        if not binary.is_file():
            raise SystemExit("aria2 source build completed without src/aria2c")
        atomic_json(aria_marker, recipe)

    strip = bin_dir / "llvm-strip"
    stripped = build_root / "libaria2c.so"
    shutil.copyfile(binary, stripped)
    subprocess.run([str(strip), "--strip-unneeded", str(stripped)], check=True)
    return stripped, ndk_info, toolchain_identity, compiler_identity


def elf_requirements(manifest: dict) -> dict:
    return {
        "required_load_alignment": int(manifest["requiredLoadAlignment"]),
        "require_pie": True,
        "require_gnu_relro": bool(manifest["requireGnuRelro"]),
        "require_bind_now": bool(manifest["requireBindNow"]),
        "require_non_executable_stack": bool(manifest["requireNonExecutableStack"]),
        "expected_interpreter": manifest["expectedInterpreter"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-pinned", action="store_true")
    parser.add_argument("--plan", action="store_true", help="validate and print the pinned recipe without downloading/building")
    parser.add_argument("--jobs", type=int, default=max(1, min(4, os.cpu_count() or 1)))
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.build_pinned == args.plan:
        parser.error("select exactly one of --build-pinned or --plan")
    if args.jobs < 1:
        parser.error("--jobs must be >= 1")

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 3 or manifest.get("buildProfile") != "android-arm64-source-v1":
        raise SystemExit("aria2 schema-v3 source build manifest is required")
    for name, source in manifest["sources"].items():
        if len(source.get("sha256", "")) != 64 or not source["url"].startswith("https://"):
            raise SystemExit(f"invalid pinned source contract: {name}")
    if args.plan:
        print(json.dumps({"buildProfile": manifest["buildProfile"], "ndkVersion": manifest["ndkVersion"], "androidApi": manifest["androidApi"], "sources": manifest["sources"], "elfPolicy": elf_requirements(manifest)}, indent=2, sort_keys=True))
        return

    authority = load_toolchain_authority()
    ndk = authority.detect_ndk(manifest["ndkVersion"])
    measured_ndk = authority.ndk_provenance(ndk, manifest["ndkVersion"])
    toolchain_identity = authority.select_toolchain_identity(ndk, int(manifest["androidApi"]))
    cache_identity = {
        "schemaVersion": 1,
        "buildProfile": manifest["buildProfile"],
        "androidApi": manifest["androidApi"],
        "ndk": measured_ndk,
        "toolchain": toolchain_identity,
        "sources": manifest["sources"],
        "configureFlags": manifest["configureFlags"],
        "recipeSha256": sha256(Path(__file__)),
        "toolchainAuthoritySha256": sha256(TOOLCHAIN_AUTHORITY),
    }
    build_key = stable_hash(cache_identity)[:16]
    build_root = CACHE_ROOT / f"build-{build_key}"
    if args.force:
        shutil.rmtree(build_root, ignore_errors=True)
    build_root.mkdir(parents=True, exist_ok=True)

    built, ndk_info, toolchain_identity, compiler_identity = build_sources(manifest, build_root, args.jobs)
    if built.stat().st_size < manifest["minimumBinaryBytes"]:
        raise SystemExit("source-built aria2 runtime is unexpectedly small")
    try:
        metadata = validate_android_runtime_file(built, **elf_requirements(manifest))
    except ElfPolicyError as error:
        raise SystemExit(f"source-built aria2 ELF qualification failed: {error}") from error

    target = ROOT / manifest["packagedPath"]
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".libaria2c.", dir=target.parent)
    os.close(fd)
    tmp = Path(tmp_name)
    try:
        shutil.copyfile(built, tmp)
        tmp.chmod(0o755)
        tmp.replace(target)
    finally:
        tmp.unlink(missing_ok=True)

    license_path = ROOT / manifest["licenseAsset"]
    notice_path = ROOT / manifest["sourceNoticeAsset"]
    lock = {
        "schemaVersion": 3,
        "component": manifest["component"], "version": manifest["version"], "releaseTag": manifest["releaseTag"],
        "abi": manifest["abi"], "androidApi": manifest["androidApi"], "ndkVersion": manifest["ndkVersion"],
        "buildProfile": manifest["buildProfile"], "sourceKind": "source-build",
        "sources": manifest["sources"], "configureFlags": manifest["configureFlags"],
        "buildRecipeSha256": sha256(Path(__file__)), "toolchainAuthoritySha256": sha256(TOOLCHAIN_AUTHORITY),
        "buildCacheIdentitySha256": stable_hash(cache_identity),
        "ndkRevision": ndk_info["revision"], "ndkSourcePropertiesSha256": ndk_info["sourcePropertiesSha256"],
        "toolchainIdentity": toolchain_identity, "compilerIdentity": compiler_identity,
        "binarySha256": sha256(target), "binarySize": target.stat().st_size,
        "dynamicDependencyPolicy": manifest["dynamicDependencyPolicy"], "neededLibraries": list(metadata["needed"]),
        "elfInterpreter": metadata["interpreter"],
        "elfSecurity": {"elfType": metadata["elfType"], "requiredLoadAlignment": manifest["requiredLoadAlignment"], "gnuRelro": metadata["gnuRelro"], "bindNow": metadata["bindNow"], "nonExecutableStack": metadata["nonExecutableStack"]},
        "licenseSha256": sha256(license_path), "sourceNoticeSha256": sha256(notice_path),
    }
    atomic_json(LOCK_PATH, lock)
    print(f"Installed source-built {target.relative_to(ROOT)}")
    print(f"Binary SHA-256: {lock['binarySha256']}")
    print(f"Build cache identity: {lock['buildCacheIdentitySha256']}")


if __name__ == "__main__":
    main()
