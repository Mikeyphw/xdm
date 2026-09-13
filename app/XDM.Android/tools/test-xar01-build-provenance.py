#!/usr/bin/env python3
"""Focused behavioral regressions for XAR01 build/runtime provenance hardening."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import os
import stat
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ff = load("xdm_xar01_ffmpeg_installer", "tools/install-ffmpeg-runtime.py")
aria = load("xdm_xar01_aria_installer", "tools/install-aria2-runtime.py")
bundle = load("xdm_xar01_bundletool", "tools/ensure-bundletool.py")


class Xar01BuildProvenanceTest(unittest.TestCase):
    def test_wrong_ndk_revision_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            ndk = Path(raw)
            (ndk / "source.properties").write_text("Pkg.Revision = 28.2.13676358\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                ff.ndk_provenance(ndk, "29.0.14206865")

    def test_exact_ndk_revision_is_attested(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            ndk = Path(raw)
            source = ndk / "source.properties"
            source.write_text("Pkg.Desc = Android NDK\nPkg.Revision = 29.0.14206865\n", encoding="utf-8")
            evidence = ff.ndk_provenance(ndk, "29.0.14206865")
            self.assertEqual("29.0.14206865", evidence["revision"])
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), evidence["sourcePropertiesSha256"])

    def test_tool_fingerprint_changes_when_compiler_bytes_change(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            tool = Path(raw) / "clang"
            tool.write_text("#!/bin/sh\necho clang-xar01-v1\n", encoding="utf-8")
            tool.chmod(tool.stat().st_mode | stat.S_IXUSR)
            first = ff.tool_fingerprint(tool)
            tool.write_text("#!/bin/sh\necho clang-xar01-v2\n", encoding="utf-8")
            tool.chmod(tool.stat().st_mode | stat.S_IXUSR)
            second = ff.tool_fingerprint(tool)
            self.assertNotEqual(first["sha256"], second["sha256"])
            self.assertNotEqual(first["version"], second["version"])

    def test_modified_extracted_source_tree_is_not_reusable(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "source.tar.gz"
            payload = b"pinned source bytes\n"
            with tarfile.open(archive, "w:gz") as tf:
                info = tarfile.TarInfo("source-1/configure")
                info.size = len(payload)
                info.mode = 0o755
                tf.addfile(info, io.BytesIO(payload))
            extracted = root / "tree"
            ff.safe_extract(archive, extracted)
            self.assertTrue(ff.archive_tree_matches(archive, extracted))
            (extracted / "source-1/configure").write_bytes(b"tampered\n")
            self.assertFalse(ff.archive_tree_matches(archive, extracted))

    def test_aria_cache_identity_is_bound_to_trusted_digest(self) -> None:
        manifest = {
            "component": "aria2c",
            "version": "1.37.0",
            "releaseTag": "v1.37.0",
            "officialUrl": "https://example.invalid/aria2c-arm64-v8a",
            "archiveName": "aria2c-arm64-v8a",
        }
        with tempfile.TemporaryDirectory() as raw:
            cache = Path(raw)
            a = aria.cached_download_path(manifest, cache, "a" * 64)
            b = aria.cached_download_path(manifest, cache, "b" * 64)
            self.assertNotEqual(a, b)

    def test_aria_cached_payload_must_match_explicit_trusted_digest(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            payload = Path(raw) / "aria2c"
            payload.write_bytes(b"x")
            manifest = {"minimumBinaryBytes": 1, "officialUrl": "https://example.invalid/aria2c", "archiveName": "aria2c"}
            actual = hashlib.sha256(b"x").hexdigest()
            self.assertTrue(aria.cached_payload_usable(payload, manifest, actual))
            self.assertFalse(aria.cached_payload_usable(payload, manifest, "0" * 64))

    def test_bundletool_pin_is_content_addressed(self) -> None:
        self.assertEqual("1.18.3", bundle.VERSION)
        self.assertRegex(bundle.SHA256, r"^[0-9a-f]{64}$")
        with tempfile.TemporaryDirectory() as raw:
            bad = Path(raw) / "bundletool.jar"
            bad.write_bytes(b"not the pinned jar")
            with self.assertRaises(SystemExit):
                bundle.verify(bad)


if __name__ == "__main__":
    unittest.main(verbosity=2)
