#!/usr/bin/env python3
"""Focused regression for aria2 official-download cache reuse; performs no network access."""
from __future__ import annotations

import importlib.util
import io
import tempfile
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("install-aria2-runtime.py")
spec = importlib.util.spec_from_file_location("xdm_install_aria2_runtime", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

manifest = {
    "component": "aria2c",
    "version": "1.37.0",
    "releaseTag": "v1.37.0",
    "officialUrl": "https://example.invalid/aria2c-arm64-v8a",
    "archiveName": "aria2c-arm64-v8a",
    "minimumBinaryBytes": 1024,
    "elfClass": 2,
    "elfData": 1,
    "elfMachine": 183,
}
header = bytearray(64)
header[:4] = b"\x7fELF"
header[4] = 2
header[5] = 1
header[18:20] = (183).to_bytes(2, "little")
payload = bytes(header) + b"X" * 2048

class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self
    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

with tempfile.TemporaryDirectory(prefix="xdm-aria2-cache-test-") as td:
    cache = Path(td)
    with mock.patch.object(module.urllib.request, "urlopen", side_effect=lambda *a, **k: FakeResponse(payload)) as open_mock:
        first = module.download_official(manifest, cache, True)
        second = module.download_official(manifest, cache, True)
        assert first == second
        assert first.read_bytes() == payload
        assert open_mock.call_count == 1, f"expected one network fetch, got {open_mock.call_count}"

        # A corrupt-but-large cache entry must be discarded and fetched again rather than
        # poisoning every subsequent incremental build.
        first.write_bytes(b"Z" * len(payload))
        third = module.download_official(manifest, cache, True)
        assert third == first
        assert third.read_bytes() == payload
        assert open_mock.call_count == 2, f"expected corrupt cache recovery fetch, got {open_mock.call_count}"

print("aria2 runtime installer cache regression passed")
