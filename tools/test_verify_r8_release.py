#!/usr/bin/env python3
"""Small mutation fixtures for the post-R8 contract checker."""
import contextlib
import importlib.util
import io
import pathlib
import unittest

SPEC = importlib.util.spec_from_file_location(
    "verify_r8_release", pathlib.Path(__file__).with_name("verify_r8_release.py"))
assert SPEC is not None and SPEC.loader is not None
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def block(*members: tuple[str, str]) -> str:
    return "\n".join(
        f"name          : '{name}'\n"
        f"type          : '{descriptor}'" for name, descriptor in members)


def valid_classes() -> dict[str, str]:
    classes: dict[str, str] = {}
    for owner, fields in VERIFY.JNI_FIELD_CONTRACT.items():
        classes[f"Lcom/k2fsa/sherpa/onnx/{owner};"] = block(*fields.items())
    for owner, descriptor in VERIFY.JNI_RESULT_CONSTRUCTORS.items():
        classes[f"Lcom/k2fsa/sherpa/onnx/{owner};"] = block(("<init>", descriptor))
    for owner, members in VERIFY.REFLECTION_CONTRACT.items():
        classes[f"Lcom/trevornk/ramblr/{owner};"] = block(*members.items())
    return classes


class R8ContractFixturesTest(unittest.TestCase):
    def assert_rejected(self, classes: dict[str, str]) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit):
                VERIFY.verify_dex_contract_from_classes(classes, github=True)

    def test_accepts_exact_owner_member_type_and_constructor_contract(self):
        VERIFY.verify_dex_contract_from_classes(valid_classes(), github=True)

    def test_rejects_false_positive_field_in_different_owner(self):
        classes = valid_classes()
        owner = "Lcom/k2fsa/sherpa/onnx/FeatureConfig;"
        classes[owner] = block(("featureDim", "I"), ("dither", "F"))
        classes["Lcom/k2fsa/sherpa/onnx/OfflineRecognizerConfig;"] += "\n" + block(("sampleRate", "I"))
        self.assert_rejected(classes)

    def test_rejects_retyped_field(self):
        classes = valid_classes()
        classes["Lcom/k2fsa/sherpa/onnx/FeatureConfig;"] = block(
            ("sampleRate", "F"), ("featureDim", "I"), ("dither", "F"))
        self.assert_rejected(classes)

    def test_rejects_constructor_descriptor_mutation(self):
        classes = valid_classes()
        classes["Lcom/k2fsa/sherpa/onnx/SpeechSegment;"] = block(("<init>", "(I)V"))
        self.assert_rejected(classes)

    def test_rejects_reflection_member_type_mutation(self):
        classes = valid_classes()
        classes["Lcom/trevornk/ramblr/SelfUpdatePrefs;"] = block(
            ("INSTANCE", "Lcom/trevornk/ramblr/SelfUpdatePrefs;"),
            ("isNotifyEnabled", "(Landroid/content/Context;)V"))
        self.assert_rejected(classes)


if __name__ == "__main__":
    unittest.main()
