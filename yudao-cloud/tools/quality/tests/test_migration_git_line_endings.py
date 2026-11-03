from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_quality_gates import migration


CHECKER = Path(__file__).resolve().parents[1] / "check_migrations.py"


class MigrationGitLineEndingsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "pom.xml").write_bytes(b"<project/>")
        (self.root / "yudao-module-demo").mkdir()
        self.git("init", "-q")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.path, self.original = migration("0001_demo_table.sql")
        self.file = self.root / self.path
        self.file.parent.mkdir(parents=True)
        self.file.write_bytes(self.original)
        (self.root / "sql/postgresql/datahub-center.sql").write_bytes(
            b"-- Included migrations through: 0001\nSELECT 1;\n"
        )

    def git(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", str(self.root), *args], check=True, capture_output=True,
        )

    def commit(self, *, autocrlf: str = "false", attributes: str | None = None) -> None:
        self.git("config", "core.autocrlf", autocrlf)
        if attributes is not None:
            (self.root / ".gitattributes").write_bytes(attributes.encode())
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")

    def check(self, *, expected_rule: str | None = None) -> None:
        before = self.file.read_bytes() if self.file.exists() else None
        result = subprocess.run(
            [sys.executable, "-B", str(CHECKER), "--root", str(self.root)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        output = result.stdout + result.stderr
        if expected_rule is None:
            self.assertEqual(0, result.returncode, output)
        else:
            self.assertNotEqual(0, result.returncode, output)
            self.assertIn(expected_rule, output)
        self.assertEqual(before, self.file.read_bytes() if self.file.exists() else None)
        self.assertEqual(self.original, self.git("show", "HEAD:" + self.path).stdout)

    def test_autocrlf_true_accepts_checkout_crlf_without_rewriting_sql(self) -> None:
        self.commit(autocrlf="true")
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n"))
        self.check()

    def test_autocrlf_input_accepts_clean_normalization(self) -> None:
        self.commit(autocrlf="input")
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n"))
        self.check()

    def test_explicit_eol_lf_accepts_crlf_when_autocrlf_disabled(self) -> None:
        self.commit(attributes="*.sql text eol=lf\n")
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n"))
        self.check()

    def test_explicit_eol_crlf_accepts_worktree_crlf(self) -> None:
        self.commit(attributes="*.sql text eol=crlf\n")
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n"))
        self.check()

    def test_no_git_normalization_rejects_crlf_change(self) -> None:
        self.commit()
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n"))
        self.check(expected_rule="MIGRATION_IMMUTABLE_CHANGED")

    def test_binary_attribute_overrides_autocrlf_and_rejects_change(self) -> None:
        self.commit(autocrlf="true", attributes="*.sql -text\n")
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n"))
        self.check(expected_rule="MIGRATION_IMMUTABLE_CHANGED")

    def test_sql_change_with_crlf_is_still_rejected(self) -> None:
        self.commit(autocrlf="true")
        self.file.write_bytes(self.original.replace(b"COMMIT;", b"ROLLBACK;").replace(b"\n", b"\r\n"))
        self.check(expected_rule="MIGRATION_IMMUTABLE_CHANGED")

    def test_extra_newline_with_crlf_is_still_rejected(self) -> None:
        self.commit(autocrlf="true")
        self.file.write_bytes(self.original.replace(b"\n", b"\r\n") + b"\r\n")
        self.check(expected_rule="MIGRATION_IMMUTABLE_CHANGED")

    def test_bom_addition_is_still_rejected(self) -> None:
        self.commit(autocrlf="true")
        self.file.write_bytes(b"\xef\xbb\xbf" + self.original.replace(b"\n", b"\r\n"))
        self.check(expected_rule="MIGRATION_IMMUTABLE_CHANGED")

    def test_deletion_is_still_rejected(self) -> None:
        self.commit(autocrlf="true")
        self.file.unlink()
        self.check(expected_rule="MIGRATION_IMMUTABLE_DELETED")


if __name__ == "__main__":
    unittest.main()
