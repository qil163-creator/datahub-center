from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_quality_gates import migration
from common import in_git_work_tree, load_git_files


CHECKER = Path(__file__).resolve().parents[1] / "check_migrations.py"


class MigrationMonorepoLayoutTest(unittest.TestCase):
    """yudao-cloud 作为单仓子目录（自身没有 .git）时，门禁仍须读取并比对 Git 中的迁移。"""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.backend = self.repo / "yudao-cloud"
        (self.backend / "yudao-module-demo").mkdir(parents=True)
        (self.backend / "pom.xml").write_bytes(b"<project/>")
        self.path, self.original = migration("0001_demo_table.sql")
        self.file = self.backend / self.path
        self.file.parent.mkdir(parents=True)
        self.file.write_bytes(self.original)
        (self.backend / "sql/postgresql/datahub-center.sql").write_bytes(
            b"-- Included migrations through: 0001\nSELECT 1;\n"
        )
        # 仓库根下的兄弟目录，同名路径不得混入后端快照。
        sibling = self.repo / "frontend" / self.path
        sibling.parent.mkdir(parents=True)
        sibling.write_bytes(b"-- not a backend migration\n")
        self.git("init", "-q")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.repo / "no-hooks"))
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        self.assertFalse((self.backend / ".git").exists())

    def git(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", str(self.repo), *args], check=True, capture_output=True,
        )

    def run_checker(self) -> tuple[int, str]:
        result = subprocess.run(
            [sys.executable, "-B", str(CHECKER), "--root", str(self.backend)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        return result.returncode, result.stdout + result.stderr

    def test_git_snapshot_paths_are_relative_to_backend_root(self) -> None:
        self.assertTrue(in_git_work_tree(self.backend))
        snapshot = load_git_files(self.backend, "HEAD", lambda path: path.endswith(".sql"))
        self.assertEqual(
            {self.path: self.original,
             "sql/postgresql/datahub-center.sql": b"-- Included migrations through: 0001\nSELECT 1;\n"},
            snapshot,
        )

    def test_directory_outside_git_is_not_a_work_tree(self) -> None:
        with tempfile.TemporaryDirectory() as plain:
            self.assertFalse(in_git_work_tree(Path(plain)))

    def test_unchanged_subdirectory_checkout_passes(self) -> None:
        code, output = self.run_checker()
        self.assertEqual(0, code, output)
        self.assertNotIn("CONFIGURATION ERROR", output)

    def test_changed_delivered_migration_is_rejected(self) -> None:
        self.file.write_bytes(self.original.replace(b"COMMIT;", b"ROLLBACK;"))
        code, output = self.run_checker()
        self.assertNotEqual(0, code, output)
        self.assertIn("MIGRATION_IMMUTABLE_CHANGED", output)

    def test_deleted_delivered_migration_is_rejected(self) -> None:
        self.file.unlink()
        code, output = self.run_checker()
        self.assertNotEqual(0, code, output)
        self.assertIn("MIGRATION_IMMUTABLE_DELETED", output)


if __name__ == "__main__":
    unittest.main()
