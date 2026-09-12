#!/usr/bin/env python3
"""Fast FF04 v7 regression tests for resumable/quiet native-builder state handling."""
from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTALLER = HERE / "install-ffmpeg-runtime.py"
spec = importlib.util.spec_from_file_location("xdm_ffmpeg_runtime_installer", INSTALLER)
if spec is None or spec.loader is None:
    raise SystemExit("unable to load install-ffmpeg-runtime.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)

manifest = {
    "opensslVersion": "3.5.8",
    "opensslSourceSha256": "a" * 64,
    "ffmpegVersion": "9.0.1",
    "ffmpegSourceSha256": "b" * 64,
    "androidApi": 26,
    "buildProfile": "stream-copy-downloader-v1",
    "configureFlags": ["--enable-cross-compile", "--disable-avdevice"],
}

with tempfile.TemporaryDirectory(prefix="xdm-ffmpeg-builder-test-") as raw:
    root = Path(raw)
    prefix = root / "openssl-prefix"
    config = installer.openssl_configuration_payload(manifest, prefix)

    assert config["target"] == "android-arm64"
    assert all("__ANDROID_API__" not in option for option in config["options"]), config["options"]
    assert "no-tests" in config["options"] and "no-apps" in config["options"] and "no-module" in config["options"]

    marker = root / ".configured.json"
    assert not installer.marker_matches(marker, config)
    installer.atomic_json(marker, config)
    assert installer.marker_matches(marker, config)
    changed = dict(config)
    changed["androidApi"] = 27
    assert not installer.marker_matches(marker, changed)

    required = (
        prefix / "lib/libssl.a",
        prefix / "lib/libcrypto.a",
        prefix / "include/openssl/ssl.h",
        prefix / "lib/pkgconfig/libssl.pc",
    )
    assert not installer.openssl_install_complete(prefix)
    for path in required:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"x")
    assert installer.openssl_install_complete(prefix)

    assert installer.quiet_make_prefix(False) == ["make", "-s"]
    assert installer.quiet_make_prefix(True) == ["make"]

    ffmpeg_config = installer.ffmpeg_configuration_payload(["./configure", "--enable-openssl"], manifest)
    assert ffmpeg_config["configure"][-1] == "--enable-openssl"
    ff_marker = root / ".ffmpeg-configured.json"
    installer.atomic_json(ff_marker, ffmpeg_config)
    assert installer.marker_matches(ff_marker, ffmpeg_config)

    fake_ffmpeg = root / "ffmpeg-9.0.1"
    fake_ffmpeg.mkdir()
    fake_configure = fake_ffmpeg / "configure"
    fake_configure.write_text(
        "#!/bin/sh\n"
        "for arg in \"$@\"; do\n"
        "  if [ \"$arg\" = \"--disable-postproc\" ]; then\n"
        "    echo \"Unknown option --disable-postproc\"\n"
        "    exit 1\n"
        "  fi\n"
        "done\n"
        "exit 0\n"
    )
    fake_configure.chmod(0o755)
    installer.validate_ffmpeg_profile_flags(
        fake_ffmpeg, {}, ["--enable-static", "--disable-shared", "--disable-avdevice"]
    )
    try:
        installer.validate_ffmpeg_profile_flags(fake_ffmpeg, {}, ["--disable-postproc"])
    except SystemExit as exc:
        assert "rejected the pinned configure profile" in str(exc)
        assert "Unknown option --disable-postproc" in str(exc)
    else:
        raise AssertionError("removed postproc option must fail configure-parser preflight")

    current = {
        "ffmpegVersion": "9.0.1",
        "buildProfile": "stream-copy-downloader-v1",
        "configureFlags": ["--enable-zlib", "--disable-avdevice"],
    }
    legacy = installer.build_cache_manifest_payload(current)
    assert current["configureFlags"] == ["--enable-zlib", "--disable-avdevice"]
    assert legacy["configureFlags"] == ["--enable-zlib", "--disable-avdevice", "--disable-postproc"]

print("FF04 v7 runtime-builder resume/configure-surface regression tests passed")
