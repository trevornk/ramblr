#!/usr/bin/env python3
"""Unit checks for the DEX linkage verifier's binary-string parsing."""
from __future__ import annotations

import unittest
from pathlib import Path

from tools.verify_probe_dex_linkage import decode_modified_utf8


class DexStringDecodingTest(unittest.TestCase):
    def test_decodes_dex_modified_utf8_nul(self) -> None:
        self.assertEqual("a\x00b", decode_modified_utf8(b"a\xc0\x80b"))

    def test_decodes_ascii_descriptor(self) -> None:
        self.assertEqual("Lkotlin/collections/SetsKt;", decode_modified_utf8(b"Lkotlin/collections/SetsKt;"))

    def test_linkage_gate_checks_runner_and_known_runtime_facade_not_metadata(self) -> None:
        source = Path(__file__).with_name("verify_probe_dex_linkage.py").read_text()
        self.assertIn("SETS_DESCRIPTOR not in target_definitions", source)
        self.assertNotIn("unresolved_kotlin", source)


if __name__ == "__main__":
    unittest.main()
