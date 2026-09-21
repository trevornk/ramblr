#!/usr/bin/env python3
"""Unit checks for the DEX linkage verifier's binary-string parsing."""
from __future__ import annotations

import unittest

from tools.verify_probe_dex_linkage import decode_modified_utf8


class DexStringDecodingTest(unittest.TestCase):
    def test_decodes_dex_modified_utf8_nul(self) -> None:
        self.assertEqual("a\x00b", decode_modified_utf8(b"a\xc0\x80b"))

    def test_decodes_ascii_descriptor(self) -> None:
        self.assertEqual("Lkotlin/collections/SetsKt;", decode_modified_utf8(b"Lkotlin/collections/SetsKt;"))


if __name__ == "__main__":
    unittest.main()
