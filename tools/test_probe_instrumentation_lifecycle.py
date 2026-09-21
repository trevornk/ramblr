#!/usr/bin/env python3
"""Regression checks for the isolated optimized-native instrumentation runner."""
from __future__ import annotations

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNNER = ROOT / "app/src/androidTest/kotlin/com/trevornk/ramblr/ProbeInstrumentationRunner.kt"
WORKFLOW = ROOT / ".github/workflows/r8-native-runtime-probe.yml"


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
        self.assertIn("validStages", self.source)
        self.assertIn("Thread.getAllStackTraces()", self.source)
        self.assertIn("terminalOnce", self.source)
        self.assertIn("stageDeadline", self.source)

    def test_ci_runs_lifecycle_regression_and_uploads_test_mapping(self) -> None:
        workflow = WORKFLOW.read_text()
        self.assertIn("test_probe_instrumentation_lifecycle.py", workflow)
        self.assertIn("mapping/githubRuntimeProbeAndroidTest/*", workflow)


if __name__ == "__main__":
    unittest.main()
