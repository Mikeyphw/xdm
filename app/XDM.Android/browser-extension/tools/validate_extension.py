#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
errors: list[str] = []
required = {
    "manifest.json", "generated-config.js", "detector-core.js", "candidate-store.js",
    "network-observer.js", "page-sniffer.js", "frame-bridge.js", "handoff.js",
    "fab.js", "popup.html", "popup.js", "extension.css", "icons/icon48.png", "icons/icon96.png",
}
for rel in sorted(required):
    if not (root / rel).is_file(): errors.append(f"missing {rel}")
try:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
except Exception as exc:
    errors.append(f"manifest parse failed: {exc}")
    manifest = {}
if manifest.get("manifest_version") != 2: errors.append("manifest_version must be 2")
gecko = manifest.get("browser_specific_settings", {}).get("gecko", {})
if gecko.get("id") != "xdm-android-media-bridge@mikeyphw": errors.append("stable XDM extension id missing")
if manifest.get("content_scripts", [{}])[0].get("all_frames") is not True: errors.append("all_frames must be true")
popup = (root / "popup.html").read_text(encoding="utf-8") if (root / "popup.html").is_file() else ""
if re.search(r"(?:xdmdownload|idmdownload|1dmdownload|intent):", popup, re.I): errors.append("popup must not contain custom protocol links")
handoff = (root / "handoff.js").read_text(encoding="utf-8") if (root / "handoff.js").is_file() else ""
for token in ("params.set(\"v\"", "params.set(\"url\"", "//capture?", "idmdownload:"):
    if token not in handoff: errors.append(f"handoff missing {token}")
for token in ("extra_cookies", "extra_authorization", "extra_headers"):
    if token in handoff: errors.append(f"credential-bearing XDM handoff token present: {token}")
config = (root / "generated-config.js").read_text(encoding="utf-8") if (root / "generated-config.js").is_file() else ""
if 'xdmScheme: "xdmdownload"' not in config: errors.append("development XDM scheme missing")
if 'defaultTarget: "xdm"' not in config: errors.append("XDM must be the development default target")
if errors:
    print("Firefox extension validation failed:", file=sys.stderr)
    for error in errors: print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("Firefox extension validation passed.")
