#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

TOKENS = {
    "@@CONTRACT_VERSION@@": "contract_version",
    "@@EXTENSION_VERSION@@": "extension_version",
    "@@APP_VERSION@@": "app_version",
    "@@APPLICATION_ID@@": "application_id",
    "@@CHANNEL@@": "channel",
    "@@XDM_SCHEME@@": "xdm_scheme",
    "@@DEFAULT_TARGET@@": "default_target",
    "@@THEME_MODE@@": "theme",
}

THEMES = {
    "dark": {
        "@@THEME_MODE@@": "dark", "@@BACKGROUND@@": "#0c0f13", "@@SURFACE@@": "#151a20",
        "@@RAISED@@": "#1b222b", "@@TEXT@@": "#e9eef5", "@@MUTED@@": "#9aa6b5",
        "@@PRIMARY@@": "#9fd2ff", "@@PRIMARY_CONTAINER@@": "#214b68", "@@OUTLINE@@": "#313a46",
    },
    "amoled": {
        "@@THEME_MODE@@": "amoled", "@@BACKGROUND@@": "#000000", "@@SURFACE@@": "#000000",
        "@@RAISED@@": "#0b0e12", "@@TEXT@@": "#f0f4f8", "@@MUTED@@": "#a4afbd",
        "@@PRIMARY@@": "#9fd2ff", "@@PRIMARY_CONTAINER@@": "#173d58", "@@OUTLINE@@": "#29313b",
    },
}


def render(text: str, values: dict[str, str]) -> str:
    for token, key in TOKENS.items():
        text = text.replace(token, str(values[key]))
    unresolved = [token for token in TOKENS if token in text]
    if unresolved:
        raise SystemExit(f"unresolved template tokens: {', '.join(unresolved)}")
    return text


def render_theme(text: str, theme: str) -> str:
    for token, value in THEMES[theme].items():
        text = text.replace(token, value)
    if "@@" in text:
        raise SystemExit("unresolved generated theme token")
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--contract-version", default="1")
    parser.add_argument("--extension-version", default="1.0.0")
    parser.add_argument("--app-version", default="0.20.0-rc08")
    parser.add_argument("--application-id", default="com.mikeyphw.xdm.android")
    parser.add_argument("--channel", choices=("release", "beta", "debug"), default="release")
    parser.add_argument("--xdm-scheme", default="xdmdownload")
    parser.add_argument("--default-target", choices=("xdm", "1dm", "ask"), default="xdm")
    parser.add_argument("--theme", choices=("dark", "amoled"), default="dark")
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
        elif rel.name == "generated-theme.template.css":
            target = output / rel.with_name("generated-theme.css")
            target.write_text(render_theme(item.read_text(encoding="utf-8"), args.theme), encoding="utf-8")
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
