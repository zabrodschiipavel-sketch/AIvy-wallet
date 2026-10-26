"""Tests for toolchain metadata helpers."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_environment import properties, validate_release, validate_sdk


class EnvironmentTests(unittest.TestCase):
    def test_release_exact_pinned_version_passes(self):
        validate_release({"IMPLEMENTOR": "Eclipse Adoptium", "SEMANTIC_VERSION": "21.0.12.1+1"}, "21.0.12.1+1")

    def test_release_wrong_vendor_fails(self):
        with self.assertRaises(ValueError):
            validate_release({"IMPLEMENTOR": "Other Vendor", "SEMANTIC_VERSION": "21.0.12.1+1"}, "21.0.12.1+1")

    def test_release_wrong_build_fails(self):
        with self.assertRaises(ValueError):
            validate_release({"IMPLEMENTOR": "Eclipse Adoptium", "SEMANTIC_VERSION": "21.0.11+9"}, "21.0.12.1+1")

    def test_release_missing_version_fails(self):
        with self.assertRaises(ValueError):
            validate_release({"IMPLEMENTOR": "Eclipse Adoptium"}, "21.0.12.1+1")

    def test_sdk_variants(self):
        validate_sdk({"AndroidVersion.ApiLevel": "34"}, {"Pkg.Revision": "34.0.0"})
        cases = [
            ({"AndroidVersion.ApiLevel": "33"}, {"Pkg.Revision": "34.0.0"}),
            ({"AndroidVersion.ApiLevel": "34"}, {"Pkg.Revision": "33.0.3"}),
            ({}, {"Pkg.Revision": "34.0.0"}),
            ({"AndroidVersion.ApiLevel": "34"}, {}),
        ]
        for platform, tools in cases:
            with self.subTest(platform=platform, tools=tools):
                with self.assertRaises(ValueError):
                    validate_sdk(platform, tools)

    def test_properties_ignores_comments_and_unquotes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "source.properties"
            path.write_text("# comment\nIMPLEMENTOR=\"Eclipse Adoptium\"\n  # indented comment=ignored\nSEMANTIC_VERSION=\"21.0.12.1+1\"\n", encoding="utf-8")
            parsed = properties(path)
            self.assertEqual(parsed, {"IMPLEMENTOR": "Eclipse Adoptium", "SEMANTIC_VERSION": "21.0.12.1+1"})


if __name__ == "__main__":
    unittest.main()
