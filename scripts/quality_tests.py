"""Negative controls for the CI gate; no Android SDK or third-party packages needed."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from quality import baseline_entries, feature_edges, inspect_badging, lint_errors, source_suppressions


class CandidateGateTest(unittest.TestCase):
    def test_verified_release_package(self):
        self.assertEqual(inspect_badging("package: name='com.pavel.wallet' versionCode='1'"),
                         "com.pavel.wallet")

    def test_rejects_missing_wrong_and_debuggable_package(self):
        for badging in ("", "package: name='com.pavel.wallet.debug'",
                        "package: name='com.ivy.wallet.optimized'",
                        "package: name='com.pavel.wallet'\napplication-debuggable"):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                inspect_badging(badging)


class LintGateTest(unittest.TestCase):
    def test_valid_empty_report_and_warnings_pass(self):
        self.assertEqual(lint_errors('<issues/>'), [])
        self.assertEqual(lint_errors('<issues><issue id="x" severity="Warning"/></issues>'), [])

    def test_error_and_fatal_fail(self):
        for severity in ("Error", "Fatal"):
            self.assertEqual(len(lint_errors(f'<issues><issue id="x" severity="{severity}"/></issues>')), 1)

    def test_invalid_report_and_baseline_fail(self):
        for report in ("", "<broken>", "<html/>", '<issues type="baseline"/>', '<issues><issue id="x"/></issues>'):
            with self.subTest(report=report), self.assertRaises((ValueError, ET.ParseError)):
                lint_errors(report)

    def test_cli_missing_report_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("quality.py")),
                                     "lint", str(Path(directory) / "missing.xml")], capture_output=True)
            self.assertNotEqual(result.returncode, 0)


class DebtRatchetTest(unittest.TestCase):
    def test_source_suppressions_cannot_be_added_or_replaced(self):
        original = {"shared/A.kt": '@Suppress("Old") class A'}
        for code in ('@Suppress("New") class A', '@Suppress("Old", "Old") class A',
                     '@file:Suppress("Old", "New")', '@SuppressLint("New") class A',
                     '@get:kotlin.Suppress("New") val x = 0'):
            self.assertTrue(source_suppressions({"shared/A.kt": code}) - source_suppressions(original))

    def test_suppression_comments_and_line_changes_do_not_count(self):
        self.assertFalse(source_suppressions({"shared/A.kt": '// @Suppress("Old")\n/* @SuppressLint("X") */'}))
        original = {"shared/A.kt": '@Suppress("Old") class A'}
        self.assertEqual(source_suppressions(original),
                         source_suppressions({"shared/A.kt": '\n\n@Suppress("Old") class A'}))

    def test_same_size_baseline_replacement_is_rejected(self):
        for kind, old, new in (
            ("compose", "old.Screen", "new.Screen"),
            ("detekt", '<SmellBaseline><CurrentIssues><ID>Old</ID></CurrentIssues></SmellBaseline>',
             '<SmellBaseline><CurrentIssues><ID>New</ID></CurrentIssues></SmellBaseline>'),
            ("lint", '<issues><issue id="Old"><location file="a.kt"/></issue></issues>',
             '<issues><issue id="New"><location file="a.kt"/></issue></issues>'),
        ):
            with self.subTest(kind=kind):
                self.assertTrue(baseline_entries(kind, new) - baseline_entries(kind, old))

    def test_lint_line_number_changes_do_not_grow_baseline(self):
        old = '<issues><issue id="X"><location file="a.kt" line="1"/></issue></issues>'
        self.assertEqual(baseline_entries("lint", old), baseline_entries("lint", old.replace('line="1"', 'line="2"')))

    def test_duplicate_exception_is_growth(self):
        self.assertTrue(baseline_entries("compose", "a\na\n") - baseline_entries("compose", "a\n"))

    def test_new_feature_dao_legacy_and_module_edges_are_rejected(self):
        files = {
            "feature/x/src/main/X.kt": "import com.ivy.legacy.Old\nimport com.ivy.design.UI as OldUI\n"
                "import com.ivy.data.db.dao.read.TransactionDao\nval db: com.ivy.data.db.IvyRoomDatabase",
            "feature/x/build.gradle.kts": 'implementation(projects.temp.legacyCode)\n'
                'implementation(project(":temp:old-design"))',
        }
        self.assertEqual(len(feature_edges(files)), 6)
        self.assertEqual(len(feature_edges(files) - feature_edges({})), 6)

    def test_comments_tests_and_unrelated_dependencies_are_ignored(self):
        files = {"feature/x/src/main/X.kt": "// import com.ivy.legacy.Old\n/* com.ivy.design.UI */\n"
                 "import com.ivy.data.repository.TransactionRepository",
                 "feature/x/src/test/X.kt": "import com.ivy.data.db.dao.read.TransactionDao"}
        self.assertFalse(feature_edges(files))


if __name__ == "__main__":
    unittest.main()
