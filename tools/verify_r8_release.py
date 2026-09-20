#!/usr/bin/env python3
"""Verify Ramblr's optimized release configuration and APK policy boundaries."""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPDATER_DESCRIPTORS = (
    "Lcom/trevornk/ramblr/SelfUpdatePrefs;",
    "Lcom/trevornk/ramblr/SelfUpdateCheckWorker;",
    "Lcom/trevornk/ramblr/SelfUpdateSettingsActivity;",
)
JNI_SURFACES = {
    "Lcom/k2fsa/sherpa/onnx/OfflineRecognizer;": (
        "delete", "createStream", "createStreamWithHotwords", "setConfig",
        "newFromAsset", "newFromFile", "decode", "getResult", "prependAdspLibraryPath",
    ),
    "Lcom/k2fsa/sherpa/onnx/OfflineStream;": (
        "delete", "acceptWaveform", "setOption", "getOption",
    ),
    "Lcom/k2fsa/sherpa/onnx/OnlineRecognizer;": (
        "delete", "newFromAsset", "newFromFile", "createStream", "reset", "decode",
        "isEndpoint", "isReady", "getResult", "prependAdspLibraryPath",
    ),
    "Lcom/k2fsa/sherpa/onnx/OnlineStream;": (
        "delete", "acceptWaveform", "inputFinished", "setOption", "getOption",
    ),
    "Lcom/k2fsa/sherpa/onnx/Vad;": (
        "delete", "newFromAsset", "newFromFile", "acceptWaveform", "compute", "empty",
        "pop", "clear", "front", "isSpeechDetected", "reset", "flush",
    ),
    "Lcom/trevornk/ramblr/LlamaCppInference;": (
        "loadModel", "addChatMessage", "setInferenceBudgetMs", "startCompletion",
        "completionLoop", "stopCompletion", "close",
    ),
}
JNI_FIELD_CONTRACT = {
    "FeatureConfig": {"sampleRate": "I", "featureDim": "I", "dither": "F"},
    "OfflineRecognizerConfig": {
        "featConfig": "Lcom/k2fsa/sherpa/onnx/FeatureConfig;",
        "modelConfig": "Lcom/k2fsa/sherpa/onnx/OfflineModelConfig;",
        "hr": "Lcom/k2fsa/sherpa/onnx/HomophoneReplacerConfig;",
    },
    "VadModelConfig": {
        "sileroVadModelConfig": "Lcom/k2fsa/sherpa/onnx/SileroVadModelConfig;",
        "tenVadModelConfig": "Lcom/k2fsa/sherpa/onnx/TenVadModelConfig;",
        "sampleRate": "I", "numThreads": "I", "provider": "Ljava/lang/String;", "debug": "Z",
    },
    "SileroVadModelConfig": {
        "model": "Ljava/lang/String;", "threshold": "F", "minSilenceDuration": "F",
        "minSpeechDuration": "F", "windowSize": "I", "maxSpeechDuration": "F",
    },
    "TenVadModelConfig": {
        "model": "Ljava/lang/String;", "threshold": "F", "minSilenceDuration": "F",
        "minSpeechDuration": "F", "windowSize": "I", "maxSpeechDuration": "F",
    },
}
JNI_RESULT_CONSTRUCTORS = {
    "OfflineRecognizerResult": "(Ljava/lang/String;[Ljava/lang/String;[FLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[F)V",
    "OnlineRecognizerResult": "(Ljava/lang/String;[Ljava/lang/String;[F[F)V",
    "SpeechSegment": "(I[F)V",
}
REFLECTION_CONTRACT = {
    "SelfUpdatePrefs": {
        "INSTANCE": "Lcom/trevornk/ramblr/SelfUpdatePrefs;",
        "isNotifyEnabled": "(Landroid/content/Context;)Z",
    },
    "SelfUpdateCheckWorker": {
        "Companion": "Lcom/trevornk/ramblr/SelfUpdateCheckWorker$Companion;",
    },
    "SelfUpdateCheckWorker$Companion": {"schedule": "(Landroid/content/Context;)V"},
}


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    raise SystemExit(1)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def source_checks() -> None:
    build = (ROOT / "app/build.gradle.kts").read_text()
    props = (ROOT / "gradle.properties").read_text()
    rules = (ROOT / "app/proguard-rules.pro").read_text()
    release_workflow = (ROOT / ".github/workflows/reproducible-build.yml").read_text()
    require("isMinifyEnabled = true" in build, "release minification is not enabled")
    require("isShrinkResources = true" in build, "release resource shrinking is not enabled")
    require('getDefaultProguardFile("proguard-android-optimize.txt")' in build,
            "release does not use the optimized Android R8 baseline")
    require('"proguard-rules.pro"' in build, "release does not load app R8 rules")
    require("android.r8.optimizedResourceShrinking=true" in props,
            "optimized resource shrinking is not enabled")
    require("app/build/outputs/mapping/*Release/mapping.txt" in release_workflow,
            "release workflow does not retain the R8 mapping output")
    for descriptor in JNI_SURFACES:
        class_name = descriptor[1:-1].replace("/", ".")
        require(class_name in rules, f"missing JNI keep rule for {class_name}")
    for name in ("SelfUpdatePrefs", "SelfUpdateCheckWorker", "SelfUpdateSettingsActivity"):
        require(name in rules, f"missing reflection keep rule for {name}")
    print("source R8 configuration: PASS")


def dex_bytes(apk: Path) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        dexes = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        require(bool(dexes), f"{apk}: no DEX files")
        return b"".join(archive.read(name) for name in dexes)


def archive_bytes(apk: Path, name: str) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        try:
            return archive.read(name)
        except KeyError:
            fail(f"{apk}: missing {name}")
    raise AssertionError("unreachable")


def strings(data: bytes) -> set[str]:
    return set(re.findall(rb"[ -~]{3,}", data))


def permissions(apk: Path) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates: list[Path] = []
    if sdk:
        candidates.extend(Path(sdk).glob("build-tools/*/aapt"))
    candidates.extend(Path.home().glob("Library/Android/sdk/build-tools/*/aapt"))
    aapt = next((p for p in sorted(candidates, reverse=True) if p.is_file() and os.access(p, os.X_OK)), None)
    require(aapt is not None, "aapt is unavailable; cannot verify APK permissions")
    return subprocess.run([str(aapt), "dump", "permissions", str(apk)], check=True,
                          text=True, capture_output=True).stdout


def verify_jni(apk: Path) -> None:
    dex = dex_bytes(apk)
    dex_text = strings(dex)
    sherpa = archive_bytes(apk, "lib/arm64-v8a/libsherpa-onnx-jni.so")
    llama = archive_bytes(apk, "lib/arm64-v8a/libllama-cleanup-jni.so")
    for descriptor, members in JNI_SURFACES.items():
        require(descriptor.encode() in dex, f"{apk}: missing JNI class {descriptor}")
        native = llama if "LlamaCppInference" in descriptor else sherpa
        class_symbol = descriptor[1:-1].replace("/", "_").encode()
        for member in members:
            require(member.encode() in dex_text, f"{apk}: missing JNI member string {member}")
            require(b"Java_" + class_symbol + b"_" + member.encode() in native,
                    f"{apk}: native symbol missing for {descriptor}.{member}")


def mapping_class_block(text: str, class_name: str) -> str:
    match = re.search(
        rf"^{re.escape(class_name)} -> {re.escape(class_name)}:\n(.*?)(?=^[^ \t].* -> .*:$|\Z)",
        text,
        re.M | re.S,
    )
    if match is None:
        fail(f"mapping: class was renamed or removed: {class_name}")
    return match.group(1)


def verify_mapping(mapping: Path) -> None:
    text = mapping.read_text()
    require("# compiler: R8" in text, f"{mapping}: R8 marker absent")
    for descriptor in JNI_SURFACES:
        class_name = descriptor[1:-1].replace("/", ".")
        mapping_class_block(text, class_name)


def dexdump_classes(apk: Path) -> dict[str, str]:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    roots = [Path(sdk)] if sdk else []
    roots.append(Path.home() / "Library/Android/sdk")
    candidates = [p for root in roots for p in root.glob("build-tools/*/dexdump")]
    dexdump = next((p for p in sorted(candidates, reverse=True) if p.is_file() and os.access(p, os.X_OK)), None)
    require(dexdump is not None, "dexdump is unavailable; cannot verify JNI owners and descriptors")
    with tempfile.TemporaryDirectory() as raw:
        directory = Path(raw)
        with zipfile.ZipFile(apk) as archive:
            dexes = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
            for name in dexes:
                (directory / name).write_bytes(archive.read(name))
        output = "".join(
            subprocess.run([str(dexdump), "-d", str(path)], check=True, text=True,
                           errors="replace", capture_output=True).stdout
            for path in directory.iterdir()
        )
    classes: dict[str, str] = {}
    for block in output.split("Class descriptor  : ")[1:]:
        match = re.match(r"'([^']+)'", block)
        if match:
            classes[match.group(1)] = block
    return classes


def dexdump_members(block: str) -> dict[str, set[str]]:
    """Return exact DEX member names and descriptors from one dexdump class block."""
    pairs = re.findall(r"name\s+: '([^']+)'\s+type\s+: '([^']+)'", block)
    return {name: {descriptor for candidate, descriptor in pairs if candidate == name}
            for name, _ in pairs}


def verify_dex_contract_from_classes(classes: dict[str, str], github: bool) -> None:
    # GetFieldID binds the owning class, field name, and descriptor. dexdump is the authoritative
    # post-R8 view; mapping omits unchanged fields, so it cannot prove this contract on its own.
    for class_name, fields in JNI_FIELD_CONTRACT.items():
        descriptor = f"Lcom/k2fsa/sherpa/onnx/{class_name};"
        block = classes.get(descriptor)
        if block is None:
            fail(f"JNI config class missing: {descriptor}")
        assert block is not None
        members = dexdump_members(block)
        for field, field_descriptor in fields.items():
            require(field_descriptor in members.get(field, set()),
                    f"JNI field missing, renamed, or retyped in owner {class_name}: "
                    f"{field} {field_descriptor}")
    for class_name, signature in JNI_RESULT_CONSTRUCTORS.items():
        descriptor = f"Lcom/k2fsa/sherpa/onnx/{class_name};"
        block = classes.get(descriptor)
        require(block is not None and signature in dexdump_members(block).get("<init>", set()),
                f"JNI constructor missing or descriptor changed: {class_name}{signature}")
    if github:
        for owner, required_members in REFLECTION_CONTRACT.items():
            block = classes.get(f"Lcom/trevornk/ramblr/{owner};")
            members = dexdump_members(block) if block is not None else {}
            for member, descriptor in required_members.items():
                require(descriptor in members.get(member, set()),
                        f"reflected member missing, renamed, or retyped: "
                        f"{owner}.{member} {descriptor}")


def verify_dex_contract(apk: Path, github: bool) -> None:
    verify_dex_contract_from_classes(dexdump_classes(apk), github=github)


def verify_native_libraries(storefront: Path, github: Path) -> None:
    with zipfile.ZipFile(storefront) as store_archive, zipfile.ZipFile(github) as github_archive:
        store_libs = {name for name in store_archive.namelist() if name.startswith("lib/arm64-v8a/") and name.endswith(".so")}
        github_libs = {name for name in github_archive.namelist() if name.startswith("lib/arm64-v8a/") and name.endswith(".so")}
        require(store_libs == github_libs and bool(store_libs), "release flavors package different native library sets")
        changed = [name for name in sorted(store_libs) if store_archive.read(name) != github_archive.read(name)]
        require(not changed, "release flavors have unexpected native library differences: " + ", ".join(changed))
    print("release flavor native libraries: byte-identical")


def verify_variant(apk: Path, mapping: Path, github: bool) -> None:
    require(apk.is_file(), f"APK not found: {apk}")
    require(mapping.is_file(), f"mapping not found: {mapping}")
    dex = dex_bytes(apk)
    for descriptor in UPDATER_DESCRIPTORS:
        present = descriptor.encode() in dex
        require(present == github, f"{apk}: updater descriptor policy failure for {descriptor}")
    permission_dump = permissions(apk)
    for permission in ("android.permission.REQUEST_INSTALL_PACKAGES",
                       "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"):
        require((permission in permission_dump) == github,
                f"{apk}: permission policy failure for {permission}")
    verify_jni(apk)
    verify_dex_contract(apk, github=github)
    verify_mapping(mapping)
    print(f"{'github' if github else 'storefront'} artifact policy and JNI surface: PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", action="store_true")
    parser.add_argument("--storefront-apk", type=Path)
    parser.add_argument("--github-apk", type=Path)
    parser.add_argument("--storefront-mapping", type=Path)
    parser.add_argument("--github-mapping", type=Path)
    args = parser.parse_args()
    if args.source:
        source_checks()
    supplied = (args.storefront_apk, args.github_apk, args.storefront_mapping, args.github_mapping)
    if any(value is not None for value in supplied):
        require(all(value is not None for value in supplied), "supply both APKs and both mapping files")
        verify_variant(args.storefront_apk, args.storefront_mapping, github=False)
        verify_variant(args.github_apk, args.github_mapping, github=True)
        verify_native_libraries(args.storefront_apk, args.github_apk)


if __name__ == "__main__":
    main()
