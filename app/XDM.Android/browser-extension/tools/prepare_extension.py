#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

TOKENS = {
    "@@EXTENSION_VERSION@@": "extension_version",
    "@@APPLICATION_ID@@": "application_id",
    "@@CHANNEL@@": "channel",
    "@@XDM_SCHEME@@": "xdm_scheme",
    "@@DEFAULT_TARGET@@": "default_target",
}


def render(text: str, values: dict[str, str]) -> str:
    for token, key in TOKENS.items():
        text = text.replace(token, values[key])
    unresolved = [token for token in TOKENS if token in text]
    if unresolved:
        raise SystemExit(f"unresolved template tokens: {', '.join(unresolved)}")
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--extension-version", default="1.0.0")
    parser.add_argument("--application-id", default="com.mikeyphw.xdm.android")
    parser.add_argument("--channel", choices=("release", "beta", "debug"), default="release")
    parser.add_argument("--xdm-scheme", default="xdmdownload")
    parser.add_argument("--default-target", choices=("xdm", "1dm", "ask"), default="xdm")
    args = parser.parse_args()

    source = args.source.resolve()
    output = args.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    values = vars(args)

    for item in sorted(source.rglob("*")):
        if not item.is_file():
            continue
        rel = item.relative_to(source)
        if rel.name == "manifest.template.json":
            target = output / rel.with_name("manifest.json")
            target.write_text(render(item.read_text(encoding="utf-8"), values), encoding="utf-8")
        elif rel.name == "generated-config.template.js":
            target = output / rel.with_name("generated-config.js")
            target.write_text(render(item.read_text(encoding="utf-8"), values), encoding="utf-8")
        else:
            target = output / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(item, target)

    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    if manifest["browser_specific_settings"]["gecko"]["id"] != "xdm-android-media-bridge@mikeyphw":
        raise SystemExit("unexpected extension id")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
