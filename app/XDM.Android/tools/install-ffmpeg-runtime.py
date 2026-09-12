#!/usr/bin/env python3
"""Reproducibly build and install XDM's pinned arm64-v8a FFmpeg/FFprobe runtime.

The resulting PIE executables are deliberately packaged under lib*.so names so Android extracts
and exposes them from ApplicationInfo.nativeLibraryDir without relying on Termux or writable-code
locations. FFmpeg is linked statically to its own libraries and pinned OpenSSL while retaining
Android/Bionic dynamic dependencies.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path

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
            out.flush(); os.fsync(out.fileno())
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)


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


def detect_ndk(version: str) -> Path:
    candidates: list[Path] = []
    for key in ("ANDROID_NDK_ROOT", "ANDROID_NDK_HOME"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value))
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value:
            candidates.append(Path(value) / "ndk" / version)
    home = Path.home()
    candidates += [
        home / "Android/Sdk/ndk" / version,
        Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr")) / "share/android-sdk/ndk" / version,
    ]
    for candidate in candidates:
        if (candidate / "toolchains/llvm/prebuilt").is_dir():
            return candidate.resolve()
    raise SystemExit(
        f"Android NDK {version} not found. Set ANDROID_NDK_ROOT/ANDROID_NDK_HOME or install that exact NDK under ANDROID_SDK_ROOT/ndk/{version}."
    )


def host_toolchain(ndk: Path) -> Path:
    prebuilt = ndk / "toolchains/llvm/prebuilt"
    options = [p for p in prebuilt.iterdir() if p.is_dir()]
    if not options:
        raise SystemExit(f"NDK LLVM toolchain missing under {prebuilt}")
    preferred = [p for p in options if p.name.startswith("linux-")]
    return (preferred or options)[0]


def run(command: list[str], cwd: Path, env: dict[str, str]) -> None:
    print("+", " ".join(command))
    subprocess.run(command, cwd=cwd, env=env, check=True)


def ensure_pie(path: Path) -> None:
    data = path.read_bytes()[:64]
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise SystemExit(f"{path.name} is not ELF")
    elf_type = int.from_bytes(data[16:18], "little")
    machine = int.from_bytes(data[18:20], "little")
    if elf_type != 3 or machine != 183:
        raise SystemExit(f"{path.name} is not an AArch64 PIE/ET_DYN executable (type={elf_type}, machine={machine})")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-pinned", action="store_true", help="download, verify, compile and install the pinned runtime")
    parser.add_argument("--jobs", type=int, default=max(1, min(4, os.cpu_count() or 1)))
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if not args.build_pinned:
        parser.error("--build-pinned is required")

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    ndk = detect_ndk(manifest["ndkVersion"])
    toolchain = host_toolchain(ndk)
    api = int(manifest["androidApi"])
    bin_dir = toolchain / "bin"
    cc = bin_dir / f"aarch64-linux-android{api}-clang"
    cxx = bin_dir / f"aarch64-linux-android{api}-clang++"
    if not cc.is_file() or not cxx.is_file():
        raise SystemExit(f"NDK compiler for API {api} is missing: {cc}")

    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    ff_archive = download(manifest["ffmpegSourceUrl"], CACHE_ROOT / f"ffmpeg-{manifest['ffmpegVersion']}.tar.xz", manifest["ffmpegSourceSha256"])
    ssl_archive = download(manifest["opensslSourceUrl"], CACHE_ROOT / f"openssl-{manifest['opensslVersion']}.tar.gz", manifest["opensslSourceSha256"])

    build_key = hashlib.sha256((json.dumps(manifest, sort_keys=True) + str(ndk)).encode()).hexdigest()[:16]
    build_root = CACHE_ROOT / f"build-{build_key}"
    if args.force:
        shutil.rmtree(build_root, ignore_errors=True)
    source_root = build_root / "src"
    openssl_prefix = build_root / "openssl-prefix"
    if not source_root.exists():
        safe_extract(ff_archive, source_root)
        safe_extract(ssl_archive, source_root)
    ff_src = source_root / f"ffmpeg-{manifest['ffmpegVersion']}"
    ssl_src = source_root / f"openssl-{manifest['opensslVersion']}"

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

    ssl_marker = openssl_prefix / "lib/libssl.a"
    if not ssl_marker.is_file():
        openssl_prefix.mkdir(parents=True, exist_ok=True)
        run([
            "perl", "./Configure", "android-arm64", "no-shared", "no-tests", "no-apps", "no-module", "no-legacy",
            f"--prefix={openssl_prefix}", f"--openssldir={openssl_prefix / 'ssl'}", f"-D__ANDROID_API__={api}",
            "-fPIC", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
        ], ssl_src, env)
        run(["make", f"-j{args.jobs}"], ssl_src, env)
        run(["make", "install_sw"], ssl_src, env)

    ffmpeg_bin = ff_src / "ffmpeg"
    ffprobe_bin = ff_src / "ffprobe"
    if not ffmpeg_bin.is_file() or not ffprobe_bin.is_file():
        pkg = openssl_prefix / "lib/pkgconfig"
        env["PKG_CONFIG_PATH"] = str(pkg)
        common_ld = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now"
        configure = [
            "./configure",
            f"--cc={cc}", f"--cxx={cxx}", f"--ar={bin_dir / 'llvm-ar'}", f"--nm={bin_dir / 'llvm-nm'}",
            f"--ranlib={bin_dir / 'llvm-ranlib'}", f"--strip={bin_dir / 'llvm-strip'}", f"--sysroot={toolchain / 'sysroot'}",
            "--enable-cross-compile", "--target-os=android", "--arch=aarch64", "--cpu=armv8-a",
            "--enable-pic", "--enable-static", "--disable-shared", "--disable-debug", "--disable-doc", "--disable-ffplay",
            "--disable-autodetect", "--disable-gpl", "--disable-nonfree", "--disable-version3", "--enable-openssl", "--enable-zlib",
            f"--extra-cflags=-fPIC -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -I{openssl_prefix / 'include'}",
            f"--extra-ldflags=-L{openssl_prefix / 'lib'} {common_ld}",
            "--extra-libs=-ldl -lm -lz",
        ]
        run(configure, ff_src, env)
        run(["make", f"-j{args.jobs}", "ffmpeg", "ffprobe"], ff_src, env)

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
        "schemaVersion": 1,
        "component": manifest["component"],
        "ffmpegVersion": manifest["ffmpegVersion"],
        "opensslVersion": manifest["opensslVersion"],
        "abi": manifest["abi"],
        "androidApi": api,
        "ndkVersion": manifest["ndkVersion"],
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
        "configureFlags": manifest["configureFlags"],
        "ffmpegLicenseSha256": sha256(license_targets["ffmpeg"]),
        "opensslLicenseSha256": sha256(license_targets["openssl"]),
    }
    atomic_json(LOCK_PATH, lock)
    print(f"Installed {targets['ffmpeg'].relative_to(ROOT)}")
    print(f"Installed {targets['ffprobe'].relative_to(ROOT)}")
    print(f"Runtime lock: {LOCK_PATH.relative_to(ROOT)}")

if __name__ == "__main__":
    main()
