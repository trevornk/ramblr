#!/usr/bin/env python3
"""Validate the emitted DEX linkage for the optimized probe runner's known Kotlin facade.

Instrumentation executes in the optimized target process. AndroidTest dependencies can be
de-duplicated against that target, so the gate proves the runner stays in the test APK while its
known pre-entry dependency, kotlin.collections.SetsKt, stays in the isolated target APK.
"""
from __future__ import annotations

import argparse
import json
import struct
import zipfile
from pathlib import Path

RUNNER_DESCRIPTOR = "Lcom/trevornk/ramblr/ProbeInstrumentationRunner;"
SETS_DESCRIPTOR = "Lkotlin/collections/SetsKt;"


def decode_modified_utf8(data: bytes) -> str:
    """Decode DEX's Java modified UTF-8 without rejecting encoded NUL characters."""
    # DEX strings use Java's modified UTF-8: U+0000 is C0 80, while non-BMP characters are
    # represented as UTF-8-encoded surrogate pairs. Python accepts the latter with
    # surrogatepass; a final UTF-16 round-trip combines valid pairs for normal string handling.
    text = data.replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogatepass")
    return text.encode("utf-16", "surrogatepass").decode("utf-16", "surrogatepass")


def read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    """Return one unsigned little-endian base-128 integer and the next offset."""
    value = 0
    for shift in range(0, 35, 7):
        if offset >= len(data):
            raise ValueError("truncated uleb128")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
    raise ValueError("invalid uleb128")


def dex_types_and_definitions(data: bytes) -> tuple[set[str], set[str]]:
    """Read only the DEX tables needed for class-linkage checks."""
    if len(data) < 0x70 or not data.startswith(b"dex\n"):
        raise ValueError("not a DEX file")
    string_count, string_offset = struct.unpack_from("<II", data, 0x38)
    type_count, type_offset = struct.unpack_from("<II", data, 0x40)
    class_count, class_offset = struct.unpack_from("<II", data, 0x60)
    if string_offset + string_count * 4 > len(data):
        raise ValueError("truncated DEX string table")
    if type_offset + type_count * 4 > len(data):
        raise ValueError("truncated DEX type table")
    if class_offset + class_count * 32 > len(data):
        raise ValueError("truncated DEX class table")

    strings: list[str] = []
    for index in range(string_count):
        (offset,) = struct.unpack_from("<I", data, string_offset + index * 4)
        _, offset = read_uleb128(data, offset)  # UTF-16 length; bytes are nul-terminated.
        end = data.index(b"\0", offset)
        strings.append(decode_modified_utf8(data[offset:end]))

    types: list[str] = []
    for index in range(type_count):
        (string_index,) = struct.unpack_from("<I", data, type_offset + index * 4)
        if string_index >= len(strings):
            raise ValueError("DEX type references an invalid string")
        types.append(strings[string_index])

    definitions: set[str] = set()
    for index in range(class_count):
        (type_index,) = struct.unpack_from("<I", data, class_offset + index * 32)
        if type_index >= len(types):
            raise ValueError("DEX class definition references an invalid type")
        definitions.add(types[type_index])
    return set(types), definitions


def apk_types_and_definitions(path: Path) -> tuple[set[str], set[str]]:
    type_references: set[str] = set()
    definitions: set[str] = set()
    with zipfile.ZipFile(path) as apk:
        dex_entries = sorted(name for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex"))
        if not dex_entries:
            raise ValueError(f"{path}: no classes*.dex entries")
        for name in dex_entries:
            references, dex_definitions = dex_types_and_definitions(apk.read(name))
            type_references.update(references)
            definitions.update(dex_definitions)
    return type_references, definitions


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--test", required=True, type=Path)
    args = parser.parse_args()

    for apk in (args.target, args.test):
        if not apk.is_file():
            parser.error(f"APK not found: {apk}")
    _, target_definitions = apk_types_and_definitions(args.target)
    test_references, test_definitions = apk_types_and_definitions(args.test)

    if RUNNER_DESCRIPTOR not in test_definitions:
        raise SystemExit(
            "probe test APK is missing the custom instrumentation runner definition: " + RUNNER_DESCRIPTOR
        )
    if SETS_DESCRIPTOR not in target_definitions:
        raise SystemExit(
            "optimized probe target APK is missing the Kotlin SetsKt facade required by its runner: " + SETS_DESCRIPTOR
        )

    target_api_references = test_references & target_definitions
    if not target_api_references:
        raise SystemExit("probe test APK has no resolved references to the optimized target DEX")

    result = {
        "targetDexClassDefinitions": len(target_definitions),
        "testDexClassDefinitions": len(test_definitions),
        "targetKotlinDefinitions": len({descriptor for descriptor in target_definitions if descriptor.startswith("Lkotlin/")}),
        "testKotlinDefinitions": len({descriptor for descriptor in test_definitions if descriptor.startswith("Lkotlin/")}),
        "resolvedTargetApiReferences": len(target_api_references),
        "requiredDefinitions": [RUNNER_DESCRIPTOR, SETS_DESCRIPTOR],
    }
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
