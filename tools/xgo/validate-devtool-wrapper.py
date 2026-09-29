#!/usr/bin/env python3
"""Validate XGO's repository-local Devtool wrapper contract."""

from __future__ import annotations

import os
from pathlib import Path
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".devtool.toml"
LAUNCHER = ROOT / "devtoolw"

EXPECTED: dict[str, tuple[str, str, str]] = {
    # Repository host targets.
    "android": ("xdm_android", "devtool", "validate"),
    "android-test": ("xdm_android", "devtool", "test"),
    "android-build": ("xdm_android", "devtool", "build"),
    "android-lint": ("xdm_android", "devtool", "lint"),
    "desktop": ("xdm_modern", "devtool", "validate"),
    "desktop-test": ("xdm_modern", "devtool", "test"),
    "desktop-build": ("xdm_modern", "devtool", "build"),
    "desktop-package": ("xdm_modern", "workflow", "package"),

    # XGO implementation/wave boundaries.
    "xgo-foundation": ("xgo_foundation", "workflow", "validate"),
    "xgo-store": ("xgo_store", "workflow", "validate"),
    "xgo-security": ("xgo_security", "workflow", "validate"),
    "xgo-transfer": ("xgo_transfer", "workflow", "validate"),
    "xgo-backends": ("xgo_backends", "workflow", "validate"),
    "xgo-scheduler": ("xgo_scheduler", "workflow", "validate"),
    "xgo-media": ("xgo_media", "workflow", "validate"),
    "xgo-ops": ("xgo_ops", "workflow", "validate"),
    "xgo-android": ("xgo_android", "workflow", "validate"),
    "xgo-desktop": ("xgo_desktop", "workflow", "validate"),
    "xgo-seal": ("xgo_seal", "workflow", "validate"),

    # Authoritative gates.
    "gate-spec": ("xgo_gate_spec", "workflow", "validate"),
    "gate-foundation": ("xgo_gate_foundation", "workflow", "validate"),
    "gate-durable": ("xgo_gate_durable", "workflow", "validate"),
    "gate-security": ("xgo_gate_security", "workflow", "validate"),
    "gate-transfer": ("xgo_gate_transfer", "workflow", "validate"),
    "gate-backends": ("xgo_gate_backends", "workflow", "validate"),
    "gate-scheduler": ("xgo_gate_scheduler", "workflow", "validate"),
    "gate-media-identity": ("xgo_gate_media_identity", "workflow", "validate"),
    "gate-media-exec": ("xgo_gate_media_exec", "workflow", "validate"),
    "gate-ops": ("xgo_gate_ops", "workflow", "validate"),
    "gate-android": ("xgo_gate_android", "workflow", "validate"),
    "gate-cross-host": ("xgo_gate_cross_host", "workflow", "validate"),
    "final-wave12": ("xgo_final_wave12", "workflow", "validate"),
    "gate-final": ("xgo_gate_final", "workflow", "validate"),
    "gate-final-continue": ("xgo_gate_final_continue", "workflow", "validate"),

    # Wrapper's own cheap contract.
    "wrapper-check": ("xgo_wrapper", "workflow", "validate"),
}

DIRECT_WITH_PASSTHROUGH = {
    "android",
    "android-test",
    "android-build",
    "android-lint",
    "desktop",
    "desktop-test",
    "desktop-build",
}


def fail(message: str) -> None:
    raise SystemExit(f"XGO Devtool wrapper contract: FAIL: {message}")


def main() -> None:
    data = tomllib.loads(CONFIG.read_text(encoding="utf-8"))
    wrapper = data.get("wrapper")
    if not isinstance(wrapper, dict):
        fail("missing [wrapper]")
    if wrapper.get("schema") != 1:
        fail("[wrapper].schema must be 1")
    if wrapper.get("default_command") != "android":
        fail("default command must be android")

    commands = wrapper.get("commands")
    if not isinstance(commands, dict):
        fail("missing [wrapper.commands.*] declarations")

    missing = sorted(set(EXPECTED) - set(commands))
    if missing:
        fail("missing commands: " + ", ".join(missing))

    unexpected = sorted(set(commands) - set(EXPECTED))
    if unexpected:
        fail("unexpected commands outside the curated contract: " + ", ".join(unexpected))

    targets = data.get("targets")
    if not isinstance(targets, dict):
        fail("missing targets table")

    aliases: dict[str, str] = {}
    for name, (target_name, mode, action) in EXPECTED.items():
        command = commands[name]
        if command.get("target") != target_name:
            fail(f"{name}: target must be {target_name}")
        if command.get(mode) != action:
            fail(f"{name}: {mode} must be {action}")
        other = "workflow" if mode == "devtool" else "devtool"
        if other in command:
            fail(f"{name}: cannot declare both workflow and devtool actions")
        if target_name not in targets:
            fail(f"{name}: unknown target {target_name}")
        if mode == "workflow":
            workflows = targets[target_name].get("workflows", {})
            if action not in workflows:
                fail(f"{name}: target {target_name} has no workflow {action}")
        if name in DIRECT_WITH_PASSTHROUGH and command.get("allow_passthrough") is not True:
            fail(f"{name}: direct command must allow passthrough")
        for alias in command.get("aliases", []):
            if alias in commands:
                fail(f"{name}: alias {alias!r} collides with command name")
            prior = aliases.setdefault(alias, name)
            if prior != name:
                fail(f"alias {alias!r} is shared by {prior} and {name}")

    launcher = LAUNCHER.read_text(encoding="utf-8")
    required_launcher_fragments = (
        "# DEVTOOL_WRAPPER_LAUNCHER=1",
        'repo_root=$(CDPATH= cd -- "$script_dir"',
        'exec devtool --repo "$repo_root" wrapper exec "$@"',
    )
    for fragment in required_launcher_fragments:
        if fragment not in launcher:
            fail(f"devtoolw missing launcher contract fragment: {fragment}")
    if os.name != "nt" and not os.access(LAUNCHER, os.X_OK):
        fail("devtoolw is not executable")

    print(f"XGO Devtool wrapper contract: PASS ({len(EXPECTED)} commands)")


if __name__ == "__main__":
    main()
