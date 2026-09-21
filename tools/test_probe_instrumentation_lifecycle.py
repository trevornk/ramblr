#!/usr/bin/env python3
"""Regression checks for the isolated optimized-native instrumentation runner."""
from __future__ import annotations

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNNER = ROOT / "app/src/androidTest/kotlin/com/trevornk/ramblr/ProbeInstrumentationRunner.kt"
WORKFLOW = ROOT / ".github/workflows/r8-native-runtime-probe.yml"
BUILD_GRADLE = ROOT / "app/build.gradle.kts"
PROBE_TEST_RULES = ROOT / "app/probe-test-rules.pro"


class ProbeInstrumentationLifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = RUNNER.read_text()

    def test_on_create_starts_instrumentation_thread(self) -> None:
        """Custom Instrumentation must call start(); framework only invokes onCreate()."""
        on_create = re.search(
            r"override fun onCreate\(arguments: Bundle\) \{(?P<body>.*?)^    \}",
            self.source,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(on_create)
        assert on_create is not None
        body = on_create.group("body")
        self.assertIn("super.onCreate(arguments)", body)
        self.assertRegex(body, r"(?m)^\s*start\(\)\s*$")
        self.assertLess(body.index("super.onCreate(arguments)"), body.index("start()"))

    def test_runner_emits_entry_stage_before_native_work(self) -> None:
        self.assertIn('"runner.onCreate"', self.source)
        self.assertIn('"runner.onStart"', self.source)
        self.assertLess(self.source.index('"runner.onStart"'), self.source.index("NativeProbeModelProvisioningTest"))

    def test_runner_has_bounded_stage_contract(self) -> None:
        self.assertIn("isValidStage(selected)", self.source)
        self.assertNotIn("setOf(", self.source)
        self.assertIn("Thread.getAllStackTraces()", self.source)
        self.assertIn("terminalOnce", self.source)
        self.assertIn("stageDeadline", self.source)

    def test_ci_runs_lifecycle_regression_and_uploads_test_mapping(self) -> None:
        workflow = WORKFLOW.read_text()
        self.assertIn("test_probe_instrumentation_lifecycle.py", workflow)
        self.assertIn("mapping/githubRuntimeProbeAndroidTest/*", workflow)

    def test_probe_test_apk_owns_kotlin_runtime_and_checks_emitted_dex(self) -> None:
        """A runner loaded from androidTest cannot rely on target R8 retaining Kotlin helpers."""
        self.assertIn('androidTestImplementation(kotlin("stdlib"))', BUILD_GRADLE.read_text())
        self.assertIn('testProguardFiles("probe-test-rules.pro")', BUILD_GRADLE.read_text())
        self.assertIn("-keep class kotlin.collections.SetsKt", PROBE_TEST_RULES.read_text())
        self.assertIn("ProbeKotlinRuntimeLinkage.verify()", self.source)
        workflow = WORKFLOW.read_text()
        self.assertIn("verify_probe_dex_linkage.py", workflow)
        self.assertIn("--target", workflow)
        self.assertIn("--test", workflow)

    def test_future_probe_identity_uses_local_persistent_signing_when_configured(self) -> None:
        """A replacement probe must never inherit CI's ephemeral debug signer."""
        gradle = BUILD_GRADLE.read_text()
        self.assertIn('rootProject.file("probe-signing.properties")', gradle)
        self.assertIn('create("runtimeProbeLocal")', gradle)
        self.assertIn('applicationIdSuffix = ".r8probe9"', gradle)
        self.assertIn('signingConfig = signingConfigs.getByName("runtimeProbeLocal")', gradle)


if __name__ == "__main__":
    unittest.main()
