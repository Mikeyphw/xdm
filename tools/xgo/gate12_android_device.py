#!/usr/bin/env python3
"""Gate-12 temporary Android device identity resolver.

This exists only for the explicit registry_bypass lane. It collapses multiple ADB
transport aliases that resolve to the same Android device, while preserving the
exactly-one-device safety policy for genuinely distinct devices.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from typing import Iterable


class DeviceResolutionError(RuntimeError):
    pass


@dataclass(frozen=True)
class DeviceRecord:
    serial: str
    identity_key: str
    identity: dict[str, str]
    alias_hint: str


@dataclass(frozen=True)
class ResolvedDevice:
    canonical_serial: str
    aliases: tuple[str, ...]
    identity_key: str
    identity: dict[str, str]


def _run(argv: list[str], *, timeout: int = 8) -> subprocess.CompletedProcess[str]:
    return subprocess.run(argv, text=True, capture_output=True, check=False, timeout=timeout)


def list_authorized_serials(adb: str = "adb") -> list[str]:
    result = _run([adb, "devices", "-l"])
    if result.returncode != 0:
        raise DeviceResolutionError(f"adb devices failed with exit {result.returncode}: {result.stderr.strip()}")
    serials: list[str] = []
    for raw in result.stdout.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def _shell_value(adb: str, serial: str, *args: str) -> str:
    try:
        result = _run([adb, "-s", serial, "shell", *args], timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    if result.returncode != 0:
        return ""
    value = result.stdout.strip().splitlines()
    return value[-1].strip() if value else ""


def _local_emulator_alias_hint(serial: str) -> str:
    match = re.fullmatch(r"emulator-(\d+)", serial)
    if match:
        console_port = int(match.group(1))
        return f"local-emulator-adb:{console_port + 1}"
    match = re.fullmatch(r"(?:localhost|127\.0\.0\.1):(\d+)", serial)
    if match:
        return f"local-emulator-adb:{int(match.group(1))}"
    return ""


def probe_device(adb: str, serial: str) -> DeviceRecord:
    identity = {
        "ro.serialno": _shell_value(adb, serial, "getprop", "ro.serialno"),
        "ro.boot.serialno": _shell_value(adb, serial, "getprop", "ro.boot.serialno"),
        "ro.boot.qemu.avd_name": _shell_value(adb, serial, "getprop", "ro.boot.qemu.avd_name"),
        "ro.product.device": _shell_value(adb, serial, "getprop", "ro.product.device"),
        "ro.product.model": _shell_value(adb, serial, "getprop", "ro.product.model"),
        "ro.build.fingerprint": _shell_value(adb, serial, "getprop", "ro.build.fingerprint"),
        "android_id": _shell_value(adb, serial, "settings", "get", "secure", "android_id"),
    }
    stable = {key: value for key, value in identity.items() if value and value.lower() != "null"}
    alias_hint = _local_emulator_alias_hint(serial)
    if stable:
        encoded = json.dumps(stable, sort_keys=True, separators=(",", ":")).encode("utf-8")
        identity_key = "device:" + hashlib.sha256(encoded).hexdigest()
    elif alias_hint:
        identity_key = alias_hint
    else:
        identity_key = "serial:" + serial
    return DeviceRecord(serial=serial, identity_key=identity_key, identity=stable, alias_hint=alias_hint)


def _preferred_serial(serials: Iterable[str]) -> str:
    def rank(serial: str) -> tuple[int, str]:
        if re.fullmatch(r"emulator-\d+", serial):
            return (0, serial)
        if ":" not in serial:
            return (1, serial)
        if serial.startswith(("localhost:", "127.0.0.1:")):
            return (2, serial)
        return (3, serial)
    return min(serials, key=rank)


def collapse_device_aliases(records: list[DeviceRecord]) -> list[ResolvedDevice]:
    """Collapse records by probed identity, plus the documented local emulator port alias."""
    if not records:
        return []

    parent = list(range(len(records)))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for i, left in enumerate(records):
        for j in range(i + 1, len(records)):
            right = records[j]
            same_probed = left.identity_key.startswith("device:") and left.identity_key == right.identity_key
            same_local_alias = bool(left.alias_hint and left.alias_hint == right.alias_hint)
            if same_probed or same_local_alias:
                union(i, j)

    groups: dict[int, list[DeviceRecord]] = {}
    for index, record in enumerate(records):
        groups.setdefault(find(index), []).append(record)

    resolved: list[ResolvedDevice] = []
    for group in groups.values():
        aliases = tuple(sorted(record.serial for record in group))
        canonical = _preferred_serial(aliases)
        chosen = next(record for record in group if record.serial == canonical)
        merged_identity: dict[str, str] = {}
        for record in group:
            merged_identity.update({k: v for k, v in record.identity.items() if v})
        identity_material = json.dumps(merged_identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
        identity_key = (
            "device:" + hashlib.sha256(identity_material).hexdigest()
            if merged_identity
            else chosen.identity_key
        )
        resolved.append(
            ResolvedDevice(
                canonical_serial=canonical,
                aliases=aliases,
                identity_key=identity_key,
                identity=merged_identity,
            )
        )
    return sorted(resolved, key=lambda item: item.canonical_serial)


def resolve_unique_device(*, adb: str = "adb", required: bool) -> ResolvedDevice | None:
    serials = list_authorized_serials(adb)
    if not serials:
        if required:
            raise DeviceResolutionError("no authorized Android device is available")
        return None
    records = [probe_device(adb, serial) for serial in serials]
    devices = collapse_device_aliases(records)
    if len(devices) != 1:
        detail = "; ".join(f"{d.canonical_serial} aliases={list(d.aliases)}" for d in devices)
        raise DeviceResolutionError(f"expected exactly one physical/emulated device after alias collapse; found {len(devices)}: {detail}")
    return devices[0]


def _main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--policy", choices=["required", "when-device-present"], default="required")
    args = parser.parse_args()
    try:
        device = resolve_unique_device(adb=args.adb, required=args.policy == "required")
    except DeviceResolutionError as exc:
        raise SystemExit(str(exc)) from exc
    payload = {"status": "none"} if device is None else {"status": "selected", **asdict(device)}
    print(json.dumps(payload, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
