from __future__ import annotations

import contextlib
import hashlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_quality_gates import migration
import check_migrations as checker
from common import GateConfigurationError


class MigrationArchiveRestorationTest(unittest.TestCase):
    """以真实 Git 祖先验证限定范围的原字节归档恢复许可。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("pom.xml", b"<project/>")
        (self.root / "yudao-module-demo").mkdir()
        self.git("init", "-q")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.archives = dict([
            migration("0133_modeling_drop-planning-change.sql",
                      depends_on="0131_demo_retired.sql; 0132_demo_retired.sql"),
            migration("0134_demo_archive.sql",
                      depends_on="0133_modeling_drop-planning-change.sql"),
            migration("0135_demo_independent.sql"),
        ])
        self.first_path = next(iter(self.archives))
        self.independent_path = "sql/postgresql/migrations/0135_demo_independent.sql"
        self.declaration = json.dumps({
            "version": 1,
            "baseline": {
                "path": checker.BASELINE_PATH,
                "included_through": "0144",
                "source": "用户授权的原字节归档恢复夹具，不代表数据库执行记录",
            },
            "dependencies": [{
                "dependent": self.first_path,
                "sha256": hashlib.sha256(self.archives[self.first_path]).hexdigest(),
                "missing_dependency": missing,
            } for missing in ("0131_demo_retired.sql", "0132_demo_retired.sql")],
        }, ensure_ascii=False).encode("utf-8")
        self.policy("REPAIRED_CONTINUATION_SHA256", hashlib.sha256(self.declaration).hexdigest())
        self.policy("ADOPTED_ARCHIVE_SHA256", {
            self.first_path: hashlib.sha256(self.archives[self.first_path]).hexdigest(),
        })
        self.policy("ARCHIVE_RESTORATION_FIRST", 133)
        self.policy("ARCHIVE_RESTORATION_LAST", 135)
        for path, content in self.archives.items():
            self.write(path, content)
        self.write(checker.CONTINUATION_PATH, self.declaration)
        self.current_path, current = migration("0191_demo_current.sql", depends_on="0134_demo_archive.sql")
        self.write(self.current_path, current)
        self.marker("0191")
        self.checkpoint()
        self.source_ref = self.git("rev-parse", "HEAD").stdout.decode().strip()
        self.policy("ARCHIVE_RESTORATION_SOURCE_REF", self.source_ref)
        for path in self.archives:
            (self.root / path).unlink()
        self.checkpoint()
        for path, content in self.archives.items():
            self.write(path, content)
        self.new_path, new = migration("0192_demo_new.sql", depends_on="0191_demo_current.sql")
        self.write(self.new_path, new)
        self.marker("0192")

    def policy(self, name, value):
        policy = patch.object(checker, name, value)
        policy.start()
        self.addCleanup(policy.stop)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args],
                              check=True, capture_output=True)

    def checkpoint(self):
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")

    def marker(self, number):
        self.write(checker.BASELINE_PATH, f"-- Included migrations through: {number}\nSELECT 1;\n".encode())

    def worktree_bytes(self):
        paths = list((self.root / checker.MIGRATION_DIR).glob("*.sql"))
        paths.extend(self.root / path for path in (checker.BASELINE_PATH, checker.CONTINUATION_PATH))
        return {str(path.relative_to(self.root)): path.read_bytes() for path in paths if path.is_file()}

    def run_modes(self, *, accepted, expected_rule=None):
        modes = {
            "check": lambda: checker.run_check(
                self.root, base_ref="HEAD", strict=False, show_debt=False, check_immutability=True),
            "strict": lambda: checker.run_check(
                self.root, base_ref="HEAD", strict=True, show_debt=False, check_immutability=True),
            "next": lambda: checker.run_next(self.root, base_ref="HEAD"),
            "forward": lambda: checker.run_list_forward(self.root, "0191", base_ref="HEAD"),
        }
        original = self.worktree_bytes()
        outputs = {}
        for name, mode in modes.items():
            with self.subTest(mode=name):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    try:
                        result = mode()
                    except GateConfigurationError as error:
                        result = 2
                        print(error)
                text = output.getvalue()
                self.assertEqual(accepted, result == 0, text)
                if expected_rule is not None:
                    self.assertIn(expected_rule, text)
                if not accepted:
                    self.assertNotRegex(text, r"(?m)^[0-9]{4}$")
                    self.assertNotRegex(text, r"(?m)^sql/postgresql/migrations/[^\n]+\.sql$")
                self.assertEqual(original, self.worktree_bytes(), "检查器不得改写归档或声明")
                outputs[name] = text
        return outputs

    def test_exact_restoration_passes_all_modes_without_rewriting_original_bytes(self):
        outputs = self.run_modes(accepted=True)
        self.assertEqual("0193\n", outputs["next"])
        self.assertEqual(self.new_path + "\n", outputs["forward"])
        for path, content in self.archives.items():
            self.assertEqual(content, (self.root / path).read_bytes())
            self.assertEqual(content, self.git("show", self.source_ref + ":" + path).stdout)

    def test_non_adopted_archive_sql_whitespace_and_bom_changes_are_rejected(self):
        original = self.archives[self.independent_path]
        for changed in (original.replace(b"COMMIT;", b"ROLLBACK;"), original + b"\n",
                        b"\xef\xbb\xbf" + original):
            with self.subTest(content_sha256=hashlib.sha256(changed).hexdigest()):
                self.write(self.independent_path, changed)
                self.run_modes(accepted=False)
        self.write(self.independent_path, original)

    def test_missing_independent_archive_is_rejected_without_a_dependency_fallback(self):
        (self.root / self.independent_path).unlink()
        self.run_modes(accepted=False)

    def test_unknown_low_number_does_not_inherit_restoration_permission(self):
        path, content = migration("0130_demo_unapproved.sql")
        self.write(path, content)
        self.run_modes(accepted=False, expected_rule="MIGRATION_ALLOCATION_ORDER")

    def test_changed_continuation_cannot_authorize_archive_restoration(self):
        changed = json.loads(self.declaration)
        changed["baseline"]["source"] = "未经授权的替换来源"
        self.write(checker.CONTINUATION_PATH, json.dumps(changed, ensure_ascii=False).encode("utf-8"))
        self.run_modes(accepted=False)

    def test_permission_cannot_be_reused_after_restoration_is_committed(self):
        self.checkpoint()
        path, content = migration("0130_demo_unapproved.sql")
        self.write(path, content)
        self.run_modes(accepted=False, expected_rule="MIGRATION_ALLOCATION_ORDER")

    def test_restored_files_remain_immutable_after_commit(self):
        self.checkpoint()
        self.write(self.independent_path, self.archives[self.independent_path] + "-- 修改原文\n".encode("utf-8"))
        self.run_modes(accepted=False, expected_rule="MIGRATION_IMMUTABLE_CHANGED")

    def test_git_authorized_crlf_restoration_preserves_worktree_bytes(self):
        self.git("config", "core.autocrlf", "true")
        for path, content in self.archives.items():
            self.write(path, content.replace(b"\n", b"\r\n"))
        self.run_modes(accepted=True)
        for path, content in self.archives.items():
            self.assertEqual(content.replace(b"\n", b"\r\n"), (self.root / path).read_bytes())

    def test_crlf_without_git_normalization_is_rejected(self):
        self.write(self.independent_path, self.archives[self.independent_path].replace(b"\n", b"\r\n"))
        self.run_modes(accepted=False)

    def test_missing_pinned_source_ref_fails_closed(self):
        with patch.object(checker, "ARCHIVE_RESTORATION_SOURCE_REF", "0" * 40):
            self.run_modes(accepted=False)

    def test_source_ref_with_incomplete_range_fails_closed(self):
        with patch.object(checker, "ARCHIVE_RESTORATION_SOURCE_REF",
                          self.git("rev-parse", "HEAD").stdout.decode().strip()):
            self.run_modes(accepted=False)


if __name__ == "__main__":
    unittest.main()
