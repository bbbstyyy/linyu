"""Regression tests for the public boundary and version gate; no network or devices."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from check_public_source import check, public_files
from check_release import check_apk, check_tag, version


class PublicBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "PUBLIC_FILES.txt").write_text("PUBLIC_FILES.txt\nREADME.md\n")
        (self.root / "README.md").write_text("Example app\n")
        self.files = {"PUBLIC_FILES.txt", "README.md"}

    def test_clean_export(self):
        self.assertEqual(check(self.root, self.files), [])

    def test_extra_private_file_rejected(self):
        errors = check(self.root, self.files | {"account.enc"})
        self.assertTrue(any("Unapproved" in error for error in errors))

    def test_missing_file_rejected(self):
        self.assertTrue(check(self.root, {"PUBLIC_FILES.txt"}))

    def test_symlink_rejected(self):
        (self.root / "README.md").unlink()
        (self.root / "README.md").symlink_to(self.root / "PUBLIC_FILES.txt")
        with self.assertRaises(ValueError):
            public_files(self.root)

    def test_parent_path_rejected(self):
        (self.root / "PUBLIC_FILES.txt").write_text("../secret\n")
        with self.assertRaises(ValueError):
            public_files(self.root)

    def test_key_cannot_be_added_to_allowlist(self):
        (self.root / "PUBLIC_FILES.txt").write_text("release.keystore\n")
        with self.assertRaises(ValueError):
            public_files(self.root)

    def test_token_report_does_not_leak_value(self):
        token = "ghp_" + "a" * 36
        (self.root / "README.md").write_text(token)
        errors = check(self.root, self.files)
        self.assertTrue(errors)
        self.assertNotIn(token, str(errors))

    def test_new_phone_requires_review(self):
        (self.root / "README.md").write_text("139" + "12345678")
        self.assertTrue(check(self.root, self.files))

    def test_only_known_binary_is_allowed(self):
        (self.root / "README.md").write_bytes(b"\xff\xfe\x00")
        self.assertTrue(check(self.root, self.files))


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.file = self.root / "version.properties"
        self.file.write_text("versionName=0.2.8-mvp\nversionCode=10\n")

    def test_matching_prerelease(self):
        info = version(self.root)
        self.assertTrue(info["prerelease"])
        check_tag(info, "v0.2.8-mvp")

    def test_tag_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            check_tag(version(self.root), "v0.2.8")

    def test_stable_release(self):
        self.file.write_text("versionName=1.0.0\nversionCode=11\n")
        self.assertFalse(version(self.root)["prerelease"])

    def test_shell_metacharacters_rejected(self):
        self.file.write_text("versionName=1.0.0;bad\nversionCode=11\n")
        with self.assertRaises(ValueError):
            version(self.root)

    def test_invalid_code_rejected(self):
        self.file.write_text("versionName=1.0.0\nversionCode=0\n")
        with self.assertRaises(ValueError):
            version(self.root)

    def apk(self, legal=True):
        apk = self.root / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            if legal:
                for name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/Apache-2.0.txt"):
                    archive.writestr("assets/legal/" + name, "test")
        return apk

    def badging(self):
        return "package: name='local.rea.shower' versionCode='10' versionName='0.2.8-mvp'\n"

    def test_valid_apk(self):
        with patch("check_release.subprocess.check_output", side_effect=[self.badging(), "manifest"]):
            check_apk(version(self.root), self.apk(), "unused")

    def test_debug_apk_rejected(self):
        with patch("check_release.subprocess.check_output", return_value=self.badging() + "application-debuggable\n"):
            with self.assertRaises(ValueError):
                check_apk(version(self.root), self.apk(), "unused")

    def test_preview_component_rejected(self):
        with patch("check_release.subprocess.check_output", side_effect=[self.badging(), "AppUiPreviewActivity"]):
            with self.assertRaises(ValueError):
                check_apk(version(self.root), self.apk(), "unused")

    def test_missing_license_rejected(self):
        with patch("check_release.subprocess.check_output", side_effect=[self.badging(), "manifest"]):
            with self.assertRaises(ValueError):
                check_apk(version(self.root), self.apk(False), "unused")


if __name__ == "__main__":
    unittest.main()
