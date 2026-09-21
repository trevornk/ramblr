#!/usr/bin/env python3
"""Hermetic control-flow regressions for CI-artifact release preparation."""
from __future__ import annotations

import os
import shlex
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/prepare-release-from-ci-artifact.sh"
SOURCE_SHA = subprocess.run(
    ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, check=True,
    capture_output=True).stdout.strip()
EXPECTED_SIGNER = "ab" * 32


def make_executable(path: Path, text: str) -> None:
    path.write_text(text)
    path.chmod(0o755)


def mapping() -> str:
    verify = ROOT / "tools/verify_r8_release.py"
    namespace: dict[str, object] = {"__name__": "verify_r8_release_contracts", "__file__": str(verify)}
    exec(compile(verify.read_text(), str(verify), "exec"), namespace)
    surfaces = namespace["JNI_SURFACES"]
    assert isinstance(surfaces, dict)
    return "# compiler: R8\n" + "".join(
        f"{descriptor[1:-1].replace('/', '.')} -> {descriptor[1:-1].replace('/', '.')}:\n"
        for descriptor in surfaces
    )


def dex_and_native(github: bool) -> tuple[bytes, bytes, bytes]:
    verify = ROOT / "tools/verify_r8_release.py"
    namespace: dict[str, object] = {"__name__": "verify_r8_release_contracts", "__file__": str(verify)}
    exec(compile(verify.read_text(), str(verify), "exec"), namespace)
    updater = namespace["UPDATER_DESCRIPTORS"]
    surfaces = namespace["JNI_SURFACES"]
    assert isinstance(updater, tuple) and isinstance(surfaces, dict)
    dex = b"\x00".join(
        [descriptor.encode() for descriptor in (updater if github else ())] +
        [descriptor.encode() for descriptor in surfaces] +
        [member.encode() for members in surfaces.values() for member in members])
    sherpa = b" ".join(
        b"Java_" + descriptor[1:-1].replace("/", "_").encode() + b"_" + member.encode()
        for descriptor, members in surfaces.items()
        if "LlamaCppInference" not in descriptor
        for member in members
    )
    llama = b" ".join(
        b"Java_" + descriptor[1:-1].replace("/", "_").encode() + b"_" + member.encode()
        for descriptor, members in surfaces.items()
        if "LlamaCppInference" in descriptor
        for member in members
    )
    return dex, sherpa, llama


def make_apk(path: Path, github: bool) -> None:
    dex, sherpa, llama = dex_and_native(github)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", dex)
        archive.writestr("lib/arm64-v8a/libsherpa-onnx-jni.so", sherpa)
        archive.writestr("lib/arm64-v8a/libllama-cleanup-jni.so", llama)


def dexdump_output() -> str:
    verify = ROOT / "tools/verify_r8_release.py"
    namespace: dict[str, object] = {"__name__": "verify_r8_release_contracts", "__file__": str(verify)}
    exec(compile(verify.read_text(), str(verify), "exec"), namespace)
    fields = namespace["JNI_FIELD_CONTRACT"]
    constructors = namespace["JNI_RESULT_CONSTRUCTORS"]
    reflections = namespace["REFLECTION_CONTRACT"]
    assert isinstance(fields, dict) and isinstance(constructors, dict) and isinstance(reflections, dict)
    result: list[str] = []
    for owner, values in fields.items():
        result.append(f"Class descriptor  : 'Lcom/k2fsa/sherpa/onnx/{owner};'\n" + "\n".join(
            f"name          : '{name}'\ntype          : '{descriptor}'" for name, descriptor in values.items()))
    for owner, descriptor in constructors.items():
        result.append(f"Class descriptor  : 'Lcom/k2fsa/sherpa/onnx/{owner};'\nname          : '<init>'\ntype          : '{descriptor}'")
    for owner, values in reflections.items():
        result.append(f"Class descriptor  : 'Lcom/trevornk/ramblr/{owner};'\n" + "\n".join(
            f"name          : '{name}'\ntype          : '{descriptor}'" for name, descriptor in values.items()))
    return "\n".join(result)


class PrepareReleaseFromArtifactTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.artifact = self.base / "artifact"
        self.artifact.mkdir()
        self.output = self.base / "out"
        self.sdk = self.base / "sdk"
        build_tools = self.sdk / "build-tools/1.0.0"
        build_tools.mkdir(parents=True)
        self.records = self.base / "records"
        self.records.mkdir()
        self.keystore = self.base / "release.jks"
        self.password = self.base / "password"
        self.keystore.write_text("fixture only\n")
        self.password.write_text("fixture only\n")
        self._make_artifact()
        self._make_stubs(build_tools)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _make_artifact(self) -> None:
        (self.artifact / "r8-source-commit.txt").write_text(SOURCE_SHA + "\n")
        (self.artifact / "r8-unit-test-summary.txt").write_text(
            "github tests=1 failures=0 errors=0 skipped=0\n"
            "storefront tests=1 failures=0 errors=0 skipped=0\n")
        make_apk(self.artifact / "Ramblr-1.0.32-github-release.apk", github=True)
        make_apk(self.artifact / "Ramblr-1.0.32-storefront-release.apk", github=False)
        for flavor in ("github", "storefront"):
            target = self.artifact / f"mapping/{flavor}Release"
            target.mkdir(parents=True)
            (target / "mapping.txt").write_text(mapping())

    def _make_stubs(self, build_tools: Path) -> None:
        make_executable(self.bin / "git", f"""#!/bin/sh
if [ \"$1\" = status ]; then exit 0; fi
if [ \"$1\" = rev-parse ]; then printf '%s\\n' '{SOURCE_SHA}'; exit 0; fi
exit 97
""")
        make_executable(self.bin / "gh", """#!/bin/sh
if [ "$1" = run ] && [ "$2" = view ]; then
  printf '%s\\n' '{"conclusion":"success","headSha":"'"$SOURCE_SHA"'","name":"Reproducible release build","url":"https://example.invalid/run"}'
  exit 0
fi
if [ "$1" = run ] && [ "$2" = download ]; then
  while [ "$#" -gt 0 ]; do
    if [ "$1" = --dir ]; then target=$2; shift 2; continue; fi
    shift
  done
  cp -R "$FIXTURE_ARTIFACT/." "$target/"
  exit 0
fi
exit 98
""")
        make_executable(build_tools / "aapt", """#!/bin/sh
case "$3" in *github*) printf '%s\\n' 'uses-permission: name='"'"'android.permission.REQUEST_INSTALL_PACKAGES'"'"'' 'uses-permission: name='"'"'android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION'"'"'';; esac
""")
        make_executable(build_tools / "dexdump", "#!/bin/sh\nprintf '%s' \"$FIXTURE_DEXDUMP\"\n")
        make_executable(build_tools / "apksigner", """#!/bin/sh
set -eu
last=
for arg in "$@"; do last=$arg; done
case "$1" in
  sign)
    while [ "$#" -gt 0 ]; do
      if [ "$1" = --out ]; then out=$2; shift 2; continue; fi
      shift
    done
    cp "$last" "$out"
    printf '%s\\n' "$out" >> "$FIXTURE_SIGNED"
    ;;
  verify)
    if [ "${FIXTURE_CORRUPT:-0}" = 1 ]; then
      printf '%s\n' 'APK verification failed: malformed fixture' >&2
      exit 1
    fi
    if [ "${FIXTURE_INPUT_SIGNED:-0}" = 1 ]; then
      printf '%s\n' 'Signer #1 certificate SHA-256 digest: '"$FIXTURE_ACTUAL_SIGNER"
      exit 0
    fi
    if grep -Fx "$last" "$FIXTURE_SIGNED" >/dev/null 2>&1; then
      printf '%s\\n' 'Signer #1 certificate SHA-256 digest: '"$FIXTURE_ACTUAL_SIGNER"
      exit 0
    fi
    printf '%s\\n' 'Missing META-INF/MANIFEST.MF' >&2
    exit 1
    ;;
esac
""")
        make_executable(build_tools / "zipalign", "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$FIXTURE_ZIPALIGN\"\n")
        make_executable(self.bin / "apksigcopier", "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$FIXTURE_COMPARE\"\n")

    def run_script(self, *, expected_signer: str = EXPECTED_SIGNER,
                   extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        env = os.environ | {
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "ANDROID_HOME": str(self.sdk),
            "APKSIGCOPIER": str(self.bin / "apksigcopier"),
            "FIXTURE_ARTIFACT": str(self.artifact),
            "FIXTURE_DEXDUMP": dexdump_output(),
            "FIXTURE_SIGNED": str(self.records / "signed"),
            "FIXTURE_ZIPALIGN": str(self.records / "zipalign"),
            "FIXTURE_COMPARE": str(self.records / "compare"),
            "SOURCE_SHA": SOURCE_SHA,
            "EXPECTED_SIGNER": EXPECTED_SIGNER,
            "FIXTURE_ACTUAL_SIGNER": EXPECTED_SIGNER,
        }
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            [str(SCRIPT), "35545335641", str(self.output), str(self.keystore), str(self.password),
             "fixture", expected_signer],
            cwd=ROOT, text=True, capture_output=True, env=env)

    def assert_rejected_before_signing(self, message: str) -> None:
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stdout + result.stderr)
        self.assertFalse((self.records / "signed").exists())

    def test_prepares_two_candidates_with_portable_shell_and_exact_tool_arguments(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(sorted(path.name for path in self.output.glob("*.apk")), [
            "Ramblr-1.0.32-github-release.apk", "Ramblr-1.0.32-storefront-release.apk"])
        compare_records = [shlex.split(line) for line in (self.records / "compare").read_text().splitlines()]
        self.assertEqual(len(compare_records), 2)
        for record, flavor in zip(compare_records, ("github", "storefront")):
            self.assertEqual(record[:3], ["compare", "--unsigned",
                                          str(self.output / f"Ramblr-1.0.32-{flavor}-release.apk")])
            self.assertEqual(Path(record[3]).name, f"Ramblr-1.0.32-{flavor}-release.apk")
        self.assertEqual((self.records / "zipalign").read_text().splitlines(), [
            f"-c -p 4 {self.output}/Ramblr-1.0.32-github-release.apk",
            f"-c -p 4 {self.output}/Ramblr-1.0.32-storefront-release.apk",
        ])

    def test_rejects_zero_test_counts(self):
        (self.artifact / "r8-unit-test-summary.txt").write_text(
            "github tests=0 failures=0 errors=0 skipped=0\n"
            "storefront tests=1 failures=0 errors=0 skipped=0\n")
        self.assert_rejected_before_signing("github unit-test gate missing or failed")

    def test_rejects_missing_test_report_for_one_flavor(self):
        (self.artifact / "r8-unit-test-summary.txt").write_text(
            "github tests=1 failures=0 errors=0 skipped=0\n")
        self.assert_rejected_before_signing("storefront unit-test gate missing or failed")

    def test_rejects_failed_test_report(self):
        (self.artifact / "r8-unit-test-summary.txt").write_text(
            "github tests=1 failures=1 errors=0 skipped=0\n"
            "storefront tests=1 failures=0 errors=0 skipped=0\n")
        self.assert_rejected_before_signing("github unit-test gate missing or failed")

    def test_rejects_artifact_from_different_source(self):
        (self.artifact / "r8-source-commit.txt").write_text("0" * 40 + "\n")
        self.assert_rejected_before_signing("artifact provenance does not equal current source SHA")

    def test_rejects_duplicate_github_apk_in_artifact(self):
        duplicate = self.artifact / "duplicate"
        duplicate.mkdir()
        make_apk(duplicate / "Ramblr-1.0.32-github-release.apk", github=True)
        self.assert_rejected_before_signing("expected exactly one github APK")

    def test_rejects_signed_input_with_nonrelease_certificate(self):
        result = self.run_script(extra_env={
            "FIXTURE_INPUT_SIGNED": "1", "FIXTURE_ACTUAL_SIGNER": "ef" * 32})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CI APK is signed; expected unsigned input", result.stdout + result.stderr)
        self.assertFalse((self.records / "signed").exists())

    def test_rejects_already_release_signed_input(self):
        result = self.run_script(extra_env={"FIXTURE_INPUT_SIGNED": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CI APK is signed; expected unsigned input", result.stdout + result.stderr)
        self.assertFalse((self.records / "signed").exists())

    def test_rejects_corrupt_input(self):
        result = self.run_script(extra_env={"FIXTURE_CORRUPT": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("neither validly signed nor unsigned", result.stdout + result.stderr)
        self.assertFalse((self.records / "signed").exists())

    def test_rejects_candidate_with_wrong_certificate(self):
        result = self.run_script(expected_signer="cd" * 32)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("candidate signer mismatch", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
