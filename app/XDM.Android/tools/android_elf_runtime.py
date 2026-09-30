#!/usr/bin/env python3
"""Dependency-free Android ELF policy/qualification helpers.

Packaged executable PIE payloads live under lib*.so names so Android extracts them into
nativeLibraryDir.  Header/ABI checks are not enough: release qualification must also prove
Android-only dependencies/interpreter, 16 KiB PT_LOAD compatibility, PIE, GNU RELRO,
immediate binding, and a non-executable stack.
"""
from __future__ import annotations

import re
import struct
from pathlib import Path

PT_LOAD = 1
PT_DYNAMIC = 2
PT_INTERP = 3
PT_GNU_STACK = 0x6474E551
PT_GNU_RELRO = 0x6474E552
PF_X = 0x1
ET_DYN = 3
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
DT_FLAGS = 30
DT_FLAGS_1 = 0x6FFFFFFB
DF_BIND_NOW = 0x8
DF_1_NOW = 0x1

ANDROID_INTERPRETERS = {"/system/bin/linker", "/system/bin/linker64"}
FORBIDDEN_NEEDED = {
    "libz.so.1", "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0",
    "librt.so.1", "libgcc_s.so.1", "libstdc++.so.6",
}
VERSIONED_SONAME = re.compile(r"^lib[^/]+\.so\.\d+(?:\.\d+)*$")
LINUX_LOADER = re.compile(r"^(?:ld-linux|ld-musl|ld64\.so|libc\.musl-)", re.IGNORECASE)


class ElfPolicyError(ValueError):
    pass


def _layout(data: bytes) -> tuple[str, bool, int, int, int, int]:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ElfPolicyError("runtime payload is not ELF")
    elf_class, elf_data = data[4], data[5]
    if elf_data not in (1, 2):
        raise ElfPolicyError("runtime payload has unsupported ELF byte order")
    endian = "<" if elf_data == 1 else ">"
    elf_type = struct.unpack_from(endian + "H", data, 16)[0]
    if elf_class == 2:
        if len(data) < 64:
            raise ElfPolicyError("runtime ELF64 header is truncated")
        return endian, True, struct.unpack_from(endian + "Q", data, 32)[0], struct.unpack_from(endian + "H", data, 54)[0], struct.unpack_from(endian + "H", data, 56)[0], elf_type
    if elf_class == 1:
        return endian, False, struct.unpack_from(endian + "I", data, 28)[0], struct.unpack_from(endian + "H", data, 42)[0], struct.unpack_from(endian + "H", data, 44)[0], elf_type
    raise ElfPolicyError("runtime payload has unsupported ELF class")


def _program_headers(data: bytes) -> tuple[str, bool, int, list[dict[str, int]]]:
    endian, is64, phoff, phentsize, phnum, elf_type = _layout(data)
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
            p_flags = struct.unpack_from(endian + "I", data, start + 4)[0]
            p_offset = struct.unpack_from(endian + "Q", data, start + 8)[0]
            p_vaddr = struct.unpack_from(endian + "Q", data, start + 16)[0]
            p_filesz = struct.unpack_from(endian + "Q", data, start + 32)[0]
            p_memsz = struct.unpack_from(endian + "Q", data, start + 40)[0]
            p_align = struct.unpack_from(endian + "Q", data, start + 48)[0]
        else:
            p_offset = struct.unpack_from(endian + "I", data, start + 4)[0]
            p_vaddr = struct.unpack_from(endian + "I", data, start + 8)[0]
            p_filesz = struct.unpack_from(endian + "I", data, start + 16)[0]
            p_memsz = struct.unpack_from(endian + "I", data, start + 20)[0]
            p_flags = struct.unpack_from(endian + "I", data, start + 24)[0]
            p_align = struct.unpack_from(endian + "I", data, start + 28)[0]
        headers.append({"index": index, "type": p_type, "flags": p_flags, "offset": p_offset, "vaddr": p_vaddr, "filesz": p_filesz, "memsz": p_memsz, "align": p_align})
    return endian, is64, elf_type, headers


def _vaddr_to_offset(headers: list[dict[str, int]], address: int) -> int:
    for ph in headers:
        if ph["type"] != PT_LOAD:
            continue
        start, end = ph["vaddr"], ph["vaddr"] + max(ph["filesz"], ph["memsz"])
        if start <= address < end:
            return int(ph["offset"] + (address - start))
    raise ElfPolicyError("runtime ELF dynamic string table is outside PT_LOAD segments")


def elf_runtime_metadata(data: bytes) -> dict[str, object]:
    endian, is64, elf_type, headers = _program_headers(data)
    interpreter: str | None = None
    for ph in headers:
        if ph["type"] == PT_INTERP:
            start, end = ph["offset"], ph["offset"] + ph["filesz"]
            if end > len(data):
                raise ElfPolicyError("runtime ELF interpreter segment is truncated")
            interpreter = data[start:end].split(b"\0", 1)[0].decode("utf-8", "replace")
            break

    dynamic = next((ph for ph in headers if ph["type"] == PT_DYNAMIC), None)
    needed_offsets: list[int] = []
    strtab_vaddr: int | None = None
    strsz: int | None = None
    dt_flags = 0
    dt_flags_1 = 0
    if dynamic is not None:
        start, end = dynamic["offset"], dynamic["offset"] + dynamic["filesz"]
        if end > len(data):
            raise ElfPolicyError("runtime ELF dynamic table is truncated")
        entry_size = 16 if is64 else 8
        fmt = endian + ("QQ" if is64 else "II")
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
            elif tag == DT_FLAGS:
                dt_flags = int(value)
            elif tag == DT_FLAGS_1:
                dt_flags_1 = int(value)

    needed: list[str] = []
    if needed_offsets:
        if strtab_vaddr is None:
            raise ElfPolicyError("runtime ELF has DT_NEEDED entries but no DT_STRTAB")
        strtab_offset = _vaddr_to_offset(headers, strtab_vaddr)
        strtab_end = min(len(data), strtab_offset + (strsz if strsz is not None else len(data) - strtab_offset))
        for rel in needed_offsets:
            name_start = strtab_offset + rel
            if name_start < strtab_offset or name_start >= strtab_end:
                raise ElfPolicyError("runtime ELF DT_NEEDED name is outside the dynamic string table")
            zero = data.find(b"\0", name_start, strtab_end)
            if zero < 0:
                raise ElfPolicyError("runtime ELF DT_NEEDED name is unterminated")
            needed.append(data[name_start:zero].decode("utf-8", "replace"))

    loads = [{"index": ph["index"], "offset": ph["offset"], "vaddr": ph["vaddr"], "align": ph["align"]} for ph in headers if ph["type"] == PT_LOAD]
    stack = next((ph for ph in headers if ph["type"] == PT_GNU_STACK), None)
    relro = next((ph for ph in headers if ph["type"] == PT_GNU_RELRO and ph["memsz"] > 0), None)
    return {
        "elfType": elf_type,
        "interpreter": interpreter,
        "needed": needed,
        "loadSegments": loads,
        "gnuRelro": relro is not None,
        "bindNow": bool((dt_flags & DF_BIND_NOW) or (dt_flags_1 & DF_1_NOW)),
        "nonExecutableStack": stack is not None and not bool(stack["flags"] & PF_X),
    }


def validate_android_needed_libraries(needed: list[str]) -> None:
    bad: list[str] = []
    for name in needed:
        base = Path(name).name
        if base in FORBIDDEN_NEEDED or VERSIONED_SONAME.match(base) or LINUX_LOADER.match(base):
            bad.append(name)
    if bad:
        raise ElfPolicyError("runtime has non-Android/versioned DT_NEEDED dependencies: " + ", ".join(sorted(set(bad))))


def validate_android_runtime_elf(
    data: bytes,
    *,
    required_load_alignment: int | None = None,
    require_pie: bool = False,
    require_gnu_relro: bool = False,
    require_bind_now: bool = False,
    require_non_executable_stack: bool = False,
    expected_interpreter: str | None = None,
) -> dict[str, object]:
    metadata = elf_runtime_metadata(data)
    interpreter = metadata["interpreter"]
    if interpreter is not None and interpreter not in ANDROID_INTERPRETERS:
        raise ElfPolicyError(f"runtime uses non-Android ELF interpreter: {interpreter}")
    if expected_interpreter is not None and interpreter != expected_interpreter:
        raise ElfPolicyError(f"runtime interpreter {interpreter!r} != required {expected_interpreter!r}")
    needed = list(metadata["needed"])
    validate_android_needed_libraries(needed)
    if require_pie and metadata["elfType"] != ET_DYN:
        raise ElfPolicyError(f"runtime ELF type {metadata['elfType']} is not ET_DYN/PIE")
    if required_load_alignment:
        bad: list[str] = []
        for ph in metadata["loadSegments"]:
            if ph["align"] < required_load_alignment or ph["offset"] % required_load_alignment != ph["vaddr"] % required_load_alignment:
                bad.append(f"PT_LOAD[{ph['index']}] align=0x{ph['align']:x} offset=0x{ph['offset']:x} vaddr=0x{ph['vaddr']:x}")
        if bad:
            raise ElfPolicyError(f"runtime is not {required_load_alignment // 1024} KiB ELF-page compatible: " + "; ".join(bad))
    if require_gnu_relro and not metadata["gnuRelro"]:
        raise ElfPolicyError("runtime has no PT_GNU_RELRO segment")
    if require_bind_now and not metadata["bindNow"]:
        raise ElfPolicyError("runtime does not request immediate dynamic binding (BIND_NOW)")
    if require_non_executable_stack and not metadata["nonExecutableStack"]:
        raise ElfPolicyError("runtime has no explicit non-executable PT_GNU_STACK")
    return metadata


def validate_android_runtime_file(path: Path, **kwargs) -> dict[str, object]:
    return validate_android_runtime_elf(path.read_bytes(), **kwargs)
