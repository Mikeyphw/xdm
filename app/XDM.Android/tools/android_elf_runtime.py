#!/usr/bin/env python3
"""Small dependency-free ELF policy helper for packaged Android runtime executables.

XDM packages executable PIE payloads under lib*.so names so Android extracts them into
nativeLibraryDir.  ABI/header checks alone are insufficient: a Linux/Termux build can still be
AArch64 ELF while carrying DT_NEEDED entries such as libz.so.1 or libc.so.6 that Android's linker
cannot satisfy.  This module extracts PT_INTERP/DT_NEEDED directly and enforces an Android-only
runtime dependency policy without shelling out to host readelf/ldd.
"""
from __future__ import annotations

import re
import struct
from pathlib import Path

PT_LOAD = 1
PT_DYNAMIC = 2
PT_INTERP = 3
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10

ANDROID_INTERPRETERS = {"/system/bin/linker", "/system/bin/linker64"}
FORBIDDEN_NEEDED = {
    "libz.so.1",
    "libc.so.6",
    "libm.so.6",
    "libdl.so.2",
    "libpthread.so.0",
    "librt.so.1",
    "libgcc_s.so.1",
    "libstdc++.so.6",
}
VERSIONED_SONAME = re.compile(r"^lib[^/]+\.so\.\d+(?:\.\d+)*$")
LINUX_LOADER = re.compile(r"^(?:ld-linux|ld-musl|ld64\.so|libc\.musl-)", re.IGNORECASE)


class ElfPolicyError(ValueError):
    pass


def _layout(data: bytes) -> tuple[str, bool, int, int, int]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ElfPolicyError("runtime payload is not ELF")
    elf_class = data[4]
    elf_data = data[5]
    if elf_data not in (1, 2):
        raise ElfPolicyError("runtime payload has unsupported ELF byte order")
    endian = "<" if elf_data == 1 else ">"
    if elf_class == 2:
        if len(data) < 64:
            raise ElfPolicyError("runtime ELF64 header is truncated")
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
        return endian, True, phoff, phentsize, phnum
    if elf_class == 1:
        if len(data) < 52:
            raise ElfPolicyError("runtime ELF32 header is truncated")
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]
        return endian, False, phoff, phentsize, phnum
    raise ElfPolicyError("runtime payload has unsupported ELF class")


def _program_headers(data: bytes) -> tuple[str, bool, list[dict[str, int]]]:
    endian, is64, phoff, phentsize, phnum = _layout(data)
    minimum = 56 if is64 else 32
    if phentsize < minimum:
        raise ElfPolicyError("runtime ELF program header size is invalid")
    headers: list[dict[str, int]] = []
    for index in range(phnum):
        start = phoff + index * phentsize
        if start + phentsize > len(data):
            raise ElfPolicyError("runtime ELF program header table is truncated")
        p_type = struct.unpack_from(endian + "I", data, start)[0]
        if is64:
            p_offset = struct.unpack_from(endian + "Q", data, start + 8)[0]
            p_vaddr = struct.unpack_from(endian + "Q", data, start + 16)[0]
            p_filesz = struct.unpack_from(endian + "Q", data, start + 32)[0]
            p_memsz = struct.unpack_from(endian + "Q", data, start + 40)[0]
        else:
            p_offset = struct.unpack_from(endian + "I", data, start + 4)[0]
            p_vaddr = struct.unpack_from(endian + "I", data, start + 8)[0]
            p_filesz = struct.unpack_from(endian + "I", data, start + 16)[0]
            p_memsz = struct.unpack_from(endian + "I", data, start + 20)[0]
        headers.append({
            "type": p_type,
            "offset": p_offset,
            "vaddr": p_vaddr,
            "filesz": p_filesz,
            "memsz": p_memsz,
        })
    return endian, is64, headers


def _vaddr_to_offset(headers: list[dict[str, int]], address: int) -> int:
    for ph in headers:
        if ph["type"] != PT_LOAD:
            continue
        start = ph["vaddr"]
        end = start + max(ph["filesz"], ph["memsz"])
        if start <= address < end:
            offset = ph["offset"] + (address - start)
            return int(offset)
    raise ElfPolicyError("runtime ELF dynamic string table is outside PT_LOAD segments")


def elf_runtime_metadata(data: bytes) -> dict[str, object]:
    endian, is64, headers = _program_headers(data)
    interpreter: str | None = None
    for ph in headers:
        if ph["type"] == PT_INTERP:
            start = ph["offset"]
            end = start + ph["filesz"]
            if end > len(data):
                raise ElfPolicyError("runtime ELF interpreter segment is truncated")
            interpreter = data[start:end].split(b"\0", 1)[0].decode("utf-8", "replace")
            break

    dynamic = next((ph for ph in headers if ph["type"] == PT_DYNAMIC), None)
    if dynamic is None:
        return {"interpreter": interpreter, "needed": []}
    start = dynamic["offset"]
    end = start + dynamic["filesz"]
    if end > len(data):
        raise ElfPolicyError("runtime ELF dynamic table is truncated")
    entry_size = 16 if is64 else 8
    fmt = endian + ("QQ" if is64 else "II")
    needed_offsets: list[int] = []
    strtab_vaddr: int | None = None
    strsz: int | None = None
    cursor = start
    while cursor + entry_size <= end:
        tag, value = struct.unpack_from(fmt, data, cursor)
        cursor += entry_size
        if tag == DT_NULL:
            break
        if tag == DT_NEEDED:
            needed_offsets.append(int(value))
        elif tag == DT_STRTAB:
            strtab_vaddr = int(value)
        elif tag == DT_STRSZ:
            strsz = int(value)
    if needed_offsets and strtab_vaddr is None:
        raise ElfPolicyError("runtime ELF has DT_NEEDED entries but no DT_STRTAB")
    if strtab_vaddr is None:
        return {"interpreter": interpreter, "needed": []}
    strtab_offset = _vaddr_to_offset(headers, strtab_vaddr)
    strtab_end = min(len(data), strtab_offset + (strsz if strsz is not None else len(data) - strtab_offset))
    needed: list[str] = []
    for rel in needed_offsets:
        name_start = strtab_offset + rel
        if name_start < strtab_offset or name_start >= strtab_end:
            raise ElfPolicyError("runtime ELF DT_NEEDED name is outside the dynamic string table")
        zero = data.find(b"\0", name_start, strtab_end)
        if zero < 0:
            raise ElfPolicyError("runtime ELF DT_NEEDED name is unterminated")
        needed.append(data[name_start:zero].decode("utf-8", "replace"))
    return {"interpreter": interpreter, "needed": needed}


def validate_android_needed_libraries(needed: list[str]) -> None:
    bad: list[str] = []
    for name in needed:
        base = Path(name).name
        if base in FORBIDDEN_NEEDED or VERSIONED_SONAME.match(base) or LINUX_LOADER.match(base):
            bad.append(name)
    if bad:
        raise ElfPolicyError(
            "runtime has non-Android/versioned DT_NEEDED dependencies: " + ", ".join(sorted(set(bad)))
        )


def validate_android_runtime_elf(data: bytes) -> dict[str, object]:
    metadata = elf_runtime_metadata(data)
    interpreter = metadata["interpreter"]
    if interpreter is not None and interpreter not in ANDROID_INTERPRETERS:
        raise ElfPolicyError(f"runtime uses non-Android ELF interpreter: {interpreter}")
    needed = list(metadata["needed"])
    validate_android_needed_libraries(needed)
    return metadata


def validate_android_runtime_file(path: Path) -> dict[str, object]:
    return validate_android_runtime_elf(path.read_bytes())
