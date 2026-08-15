#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
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
    "@@CAPTURE_KEY_ID@@": "capture_key_id",
    "@@CAPTURE_PUBLIC_KEY_SPKI@@": "capture_public_key_spki",
    "@@CAPTURE_OAEP_HASH@@": "capture_oaep_hash",
    "@@THEME_MODE@@": "theme",
}

COLOR_FIELDS = {
    "@@BACKGROUND@@": "background",
    "@@SURFACE@@": "surface",
    "@@RAISED@@": "raisedSurface",
    "@@STRONG_SURFACE@@": "strongSurface",
    "@@TEXT@@": "text",
    "@@MUTED@@": "mutedText",
    "@@PRIMARY@@": "primary",
    "@@ON_PRIMARY@@": "onPrimary",
    "@@PRIMARY_CONTAINER@@": "primaryContainer",
    "@@ON_PRIMARY_CONTAINER@@": "onPrimaryContainer",
    "@@OUTLINE@@": "outline",
    "@@OUTLINE_VARIANT@@": "outlineVariant",
    "@@SEPARATOR@@": "separator",
    "@@SUCCESS@@": "success",
    "@@SUCCESS_CONTAINER@@": "successContainer",
    "@@ERROR@@": "error",
    "@@ERROR_CONTAINER@@": "errorContainer",
}

NUMBER_FIELDS = {
    "@@FAB_SIZE@@": "fabSizePx",
    "@@FAB_RADIUS@@": "fabCornerRadiusPx",
    "@@FAB_EDGE_INSET@@": "fabEdgeInsetPx",
    "@@FAB_ACTION_GAP@@": "fabActionGapPx",
    "@@MOTION_FAST@@": "motionFastMs",
    "@@MOTION_STANDARD@@": "motionStandardMs",
}


def render(text: str, values: dict[str, str]) -> str:
    for token, key in TOKENS.items():
        text = text.replace(token, str(values[key]))
    unresolved = [token for token in TOKENS if token in text]
    if unresolved:
        raise SystemExit(f"unresolved template tokens: {', '.join(unresolved)}")
    return text


def parse_theme_contract(path: Path, theme: str) -> dict[str, str]:
    source = path.read_text(encoding="utf-8")
    object_name = "Dark" if theme == "dark" else "Amoled"
    match = re.search(
        rf"val\s+{object_name}:\s*XdmThemeTokens\s*=\s*XdmThemeTokens\((.*?)\n\s*\)",
        source,
        re.DOTALL,
    )
    if not match:
        raise SystemExit(f"could not read {object_name} from shared XDM theme contract")
    block = match.group(1)
    colors = {name: int(value, 16) for name, value in re.findall(r"(\w+)\s*=\s*0x([0-9A-Fa-f]{8})", block)}
    numbers = {name: int(value) for name, value in re.findall(r"(\w+)\s*=\s*(\d+)", block)}
    replacements: dict[str, str] = {"@@THEME_MODE@@": theme}
    for token, field in COLOR_FIELDS.items():
        if field not in colors:
            raise SystemExit(f"shared XDM theme contract is missing {field} for {theme}")
        replacements[token] = f"#{colors[field] & 0xFFFFFF:06X}"
    for token, field in NUMBER_FIELDS.items():
        if field not in numbers:
            raise SystemExit(f"shared XDM theme contract is missing {field} for {theme}")
        replacements[token] = str(numbers[field])
    return replacements


def render_theme(text: str, replacements: dict[str, str]) -> str:
    for token, value in replacements.items():
        text = text.replace(token, value)
    if "@@" in text:
        raise SystemExit("unresolved generated theme token")
    return text



def capture_key_id_for_spki(spki_base64url: str) -> str:
    encoded = spki_base64url.strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{128,2048}", encoded):
        raise SystemExit("capture public key must be unpadded base64url SPKI data")
    try:
        der = base64.urlsafe_b64decode(encoded + "=" * ((4 - len(encoded) % 4) % 4))
    except Exception as exc:
        raise SystemExit("capture public key is not valid base64url SPKI data") from exc
    if not der:
        raise SystemExit("capture public key is empty")
    return hashlib.sha256(der).hexdigest()[:24]


def require_capture_key_binding(key_id: str, spki_base64url: str) -> None:
    if bool(key_id.strip()) != bool(spki_base64url.strip()):
        raise SystemExit("capture key id and public key must be supplied together")
    if spki_base64url.strip():
        derived = capture_key_id_for_spki(spki_base64url)
        if key_id.strip() != derived:
            raise SystemExit(f"capture key id does not match SHA-256(SPKI DER).take(24); expected {derived}")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--theme-contract", type=Path)
    parser.add_argument("--contract-version", default="2")
    parser.add_argument("--extension-version", default="1.2.0")
    parser.add_argument("--app-version", default="0.21.0")
    parser.add_argument("--application-id", default="com.mikeyphw.xdm.android")
    parser.add_argument("--channel", choices=("release", "debug"), default="release")
    parser.add_argument("--xdm-scheme", default="xdmdownload")
    parser.add_argument("--default-target", choices=("xdm", "1dm", "ask"), default="xdm")
    parser.add_argument("--capture-key-id", default="")
    parser.add_argument("--capture-public-key-spki", default="")
    parser.add_argument("--capture-oaep-hash", choices=("SHA-1", "SHA-256"), default=None)
    parser.add_argument("--theme", choices=("dark", "amoled"), default="dark")
    args = parser.parse_args()

    if args.channel == "release":
        if not args.capture_key_id or not args.capture_public_key_spki or not args.capture_oaep_hash:
            raise SystemExit("release packaging requires --capture-key-id, --capture-public-key-spki, and --capture-oaep-hash for every default target")
    require_capture_key_binding(args.capture_key_id, args.capture_public_key_spki)
    if not args.capture_oaep_hash:
        if args.channel == "debug" and not args.capture_key_id and not args.capture_public_key_spki:
            args.capture_oaep_hash = "SHA-256"
        else:
            raise SystemExit("--capture-oaep-hash is required outside keyless debug rendering")

    source = args.source.resolve()
    output = args.output.resolve()
    module_root = Path(__file__).resolve().parents[1]
    theme_contract = (args.theme_contract or (
        module_root / "src/main/kotlin/com/mikeyphw/xdm/android/browserextension/XdmThemeTokens.kt"
    )).resolve()
    theme_replacements = parse_theme_contract(theme_contract, args.theme)

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
            base = render(item.read_text(encoding="utf-8"), values)
            target.write_text(render_theme(base, theme_replacements), encoding="utf-8")
        elif rel.name == "generated-theme.template.css":
            target = output / rel.with_name("generated-theme.css")
            target.write_text(render_theme(item.read_text(encoding="utf-8"), theme_replacements), encoding="utf-8")
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
