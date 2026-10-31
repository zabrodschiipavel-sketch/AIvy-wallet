"""Tests for toolchain metadata helpers."""
import sys
import contextlib
import io
import os
import hashlib
import json
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_environment import properties, validate_release, validate_sdk
import verify_environment


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
        validate_sdk({"AndroidVersion.ApiLevel": "34"}, {"Pkg.Revision": "34.0.0"}, "34")
        validate_sdk({"AndroidVersion.ApiLevel": "35"}, {"Pkg.Revision": "34.0.0"}, "35")
        cases = [
            ({"AndroidVersion.ApiLevel": "33"}, {"Pkg.Revision": "34.0.0"}),
            ({"AndroidVersion.ApiLevel": "34"}, {"Pkg.Revision": "33.0.3"}),
            ({}, {"Pkg.Revision": "34.0.0"}),
            ({"AndroidVersion.ApiLevel": "34"}, {}),
        ]
        for platform, tools in cases:
            with self.subTest(platform=platform, tools=tools):
                with self.assertRaises(ValueError):
                    validate_sdk(platform, tools, "34")

    def _sdk_packages(self, api):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            catalog = root / "gradle/libs.versions.toml"
            catalog.parent.mkdir()
            catalog.write_text('[versions]\ncompile-sdk = "' + api + '"\n', encoding="utf-8")
            output = io.StringIO()
            with mock.patch.object(sys, "argv", ["verify_environment.py", "--root", str(root), "--sdk-packages"]):
                with mock.patch.dict(os.environ, {}, clear=True), contextlib.redirect_stdout(output):
                    result = verify_environment.main()
            return result, output.getvalue()

    def test_sdk_packages_follow_catalog_without_java_or_sdk(self):
        for api in ("1", "35", "36"):
            with self.subTest(api=api):
                result, output = self._sdk_packages(api)
                self.assertEqual(result, 0)
                self.assertEqual(output.splitlines(), ["platforms;android-" + api, "build-tools;34.0.0"])

    def test_sdk_packages_reject_floating_catalog_version(self):
        for api in ("latest", "", "0", "-1", "３５"):
            with self.subTest(api=api):
                result, output = self._sdk_packages(api)
                self.assertEqual(result, 1)
                self.assertIn("ENVIRONMENT CHECK FAILED", output)

    def test_sdk_catalog_requires_a_string(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "gradle").mkdir()
            (root / "gradle/libs.versions.toml").write_text('[versions]\ncompile-sdk = 35\n')
            with self.assertRaises(ValueError):
                verify_environment.sdk_api(root)

    def test_cli_requires_exactly_one_action(self):
        for args in ([], ["--sdk-packages", "--out", "unused.json"]):
            with self.subTest(args=args), mock.patch.object(sys, "argv", ["verify_environment.py", *args]):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                    verify_environment.main()
                self.assertEqual(failure.exception.code, 2)

    def test_sdk_packages_use_the_script_project_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "gradle").mkdir()
            (root / "gradle/libs.versions.toml").write_text('[versions]\ncompile-sdk = "35"\n')
            output = io.StringIO()
            with mock.patch.object(verify_environment, "__file__", str(root / "scripts/verify_environment.py")):
                with mock.patch.object(sys, "argv", ["verify_environment.py", "--sdk-packages"]):
                    with contextlib.redirect_stdout(output):
                        self.assertEqual(verify_environment.main(), 0)
            self.assertEqual(output.getvalue().splitlines(), ["platforms;android-35", "build-tools;34.0.0"])

    def test_cli_records_selected_sdk_and_rejects_wrong_java_binary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pin = "21.0.12.1+1"
            files = {
                ".java-version": pin,
                "gradle/libs.versions.toml": '[versions]\ncompile-sdk = "35"\n',
                "jdk/release": 'IMPLEMENTOR="Eclipse Adoptium"\nSEMANTIC_VERSION="' + pin + '"\n',
                "sdk/platforms/android-35/source.properties": "AndroidVersion.ApiLevel=35\nPkg.Revision=2\n",
                "sdk/build-tools/34.0.0/source.properties": "Pkg.Revision=34.0.0\n",
                "sdk/platform-tools/source.properties": "Pkg.Revision=37.0.1\n",
                "gradle-user/gradle.properties": "private-fixture-setting=true\n",
                "gradle-user/init.d/custom.gradle.kts": "private-fixture-init\n",
            }
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            jar = root / "sdk/platforms/android-35/android.jar"
            jar.write_bytes(b"fixture android api")
            (root / "gradle-user/caches").mkdir()
            output_file = root / "environment.json"
            env = {"JAVA_HOME": str(root / "jdk"), "ANDROID_HOME": str(root / "sdk"),
                   "GRADLE_USER_HOME": str(root / "gradle-user")}
            for version, expected, new_output in (("Temurin-" + pin, 0, False),
                                                  ("Temurin-" + pin, 0, True),
                                                  ("Temurin-other", 1, False)):
                with self.subTest(version=version, new_output=new_output):
                    if new_output:
                        output_file = root / "new/nested/environment.json"
                    else:
                        output_file.write_text('{"stale": true}', encoding="utf-8")
                    output = io.StringIO()
                    argv = ["verify_environment.py", "--root", str(root), "--out", str(output_file)]
                    with mock.patch.object(sys, "argv", argv), mock.patch.dict(os.environ, env, clear=True):
                        with mock.patch.object(verify_environment.subprocess, "check_output",
                                               side_effect=lambda *args, **kwargs: version if kwargs.get("text") else version.encode()) as java:
                            with contextlib.redirect_stdout(output):
                                result = verify_environment.main()
                    self.assertEqual(result, expected)
                    suffix = ".exe" if os.name == "nt" else ""
                    self.assertEqual(java.call_args.args[0], [str(root / "jdk/bin" / ("java" + suffix)), "-version"])
                    if expected:
                        self.assertFalse(output_file.exists())
                        self.assertIn("ENVIRONMENT CHECK FAILED", output.getvalue())
                    else:
                        report = json.loads(output_file.read_text(encoding="utf-8"))
                        self.assertEqual(report["schema"], 1)
                        self.assertEqual(report["jdk"]["version"], pin)
                        self.assertEqual(report["sdk"]["platform"]["AndroidVersion.ApiLevel"], "35")
                        self.assertEqual(report["sdk"]["android_jar_sha256"], hashlib.sha256(jar.read_bytes()).hexdigest())
                        self.assertEqual(report["gradle_user_home"], env["GRADLE_USER_HOME"])
                        self.assertTrue(report["global_dependency_cache_reused"])
                        self.assertEqual(set(report["user_gradle_configuration_files"]),
                                         {str(root / "gradle-user/gradle.properties"),
                                          str(root / "gradle-user/init.d/custom.gradle.kts")})
                        self.assertNotIn("private-fixture", output_file.read_text())
            with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(Path, "home", return_value=root):
                with mock.patch.object(verify_environment.subprocess, "check_output", return_value="Temurin-" + pin):
                    report = verify_environment.inspect(root, root / "jdk", root / "sdk")
            self.assertEqual(report["gradle_user_home"], str(root / ".gradle"))
            self.assertFalse(report["global_dependency_cache_reused"])

    def test_properties_ignores_comments_and_unquotes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "source.properties"
            path.write_text("# comment\nIMPLEMENTOR=\"Eclipse Adoptium\"\n  # indented comment=ignored\nSEMANTIC_VERSION=\"21.0.12.1+1\"\n", encoding="utf-8")
            parsed = properties(path)
            self.assertEqual(parsed, {"IMPLEMENTOR": "Eclipse Adoptium", "SEMANTIC_VERSION": "21.0.12.1+1"})


if __name__ == "__main__":
    unittest.main()
