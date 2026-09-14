#!/usr/bin/env python3
"""Build the desktop-shipped Firefox XPI from Android's canonical extension source.

This intentionally does not carry a desktop fork. It invokes the same source renderer used
by the Android browser-extension module, then creates a deterministic XPI from that output.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

CANONICAL_ID = "xdm-android-media-bridge@mikeyphw"


def parse_versions(build_file: Path) -> tuple[str, str]:
    source = build_file.read_text(encoding="utf-8")
    extension = re.search(r'val\s+extensionVersion\s*=\s*"([^"]+)"', source)
    app = re.search(r'val\s+androidAppVersion\s*=\s*"([^"]+)"', source)
    if extension is None or app is None:
        raise SystemExit("could not resolve canonical Firefox extension versions")
    return extension.group(1), app.group(1)


def write_deterministic_xpi(source: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as archive:
            for path in sorted(item for item in source.rglob("*") if item.is_file()):
                relative = path.relative_to(source).as_posix()
                info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--theme", choices=("dark", "amoled"), default="dark")
    parser.add_argument("--channel", choices=("release", "debug"), default="release")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[3]
    module = repo_root / "app/XDM.Android/browser-extension"
    canonical_source = module / "src/main/extension/xdm-firefox"
    renderer = module / "tools/prepare_extension.py"
    build_file = module / "build.gradle.kts"
    extension_version, app_version = parse_versions(build_file)

    with tempfile.TemporaryDirectory(prefix="xdm-firefox-") as temporary_dir:
        rendered = Path(temporary_dir) / "rendered"
        command = [
            sys.executable,
            str(renderer),
            "--source", str(canonical_source),
            "--output", str(rendered),
            "--extension-version", extension_version,
            "--app-version", app_version,
            "--channel", args.channel,
            "--xdm-scheme", "xdmdownload" if args.channel == "release" else "xdmdownload-debug",
            "--default-target", "xdm",
            "--theme", args.theme,
        ]
        subprocess.run(command, cwd=repo_root, check=True)
        manifest = (rendered / "manifest.json").read_text(encoding="utf-8")
        if CANONICAL_ID not in manifest:
            raise SystemExit("rendered Firefox extension identity diverged from the canonical Android extension")
        write_deterministic_xpi(rendered, args.output.resolve())

    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
