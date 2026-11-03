from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_quality_gates import migration


CHECKER = Path(__file__).resolve().parents[1] / "check_migrations.py"
METADATA = "tools/quality/migration_baseline_continuation.json"
BASELINE = "sql/postgresql/datahub-center.sql"
ARCHIVE = "sql/postgresql/migrations/0032_demo_archive.sql"
MISSING = "0031_demo_removed.sql"
MODES = ((), ("--strict",), ("--next",))


class BaselineContinuationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("pom.xml", b"<project/>")
        (self.root / "yudao-module-demo").mkdir()
        self.git("init", "-q")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        _, archived = migration(Path(ARCHIVE).name, depends_on=MISSING)
        self.write(ARCHIVE, archived)
        self.add_migration("0037_demo_checkpoint.sql", depends_on=Path(ARCHIVE).name)
        self.marker("0037")
        self.declaration = {
            "version": 1,
            "baseline": {
                "path": BASELINE,
                "included_through": "0037",
                "source": "Approved local baseline fixture, 2026-09-20",
            },
            "dependencies": [{
                "dependent": ARCHIVE,
                "sha256": hashlib.sha256(archived).hexdigest(),
                "missing_dependency": MISSING,
            }],
        }
        self.declare()
        self.checkpoint()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def git(self, *args):
        return subprocess.run(
            ["git", "-C", str(self.root), *args], check=True,
            capture_output=True,
        )

    def checkpoint(self):
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")

    def add_migration(self, name, **kwargs):
        path, content = migration(name, **kwargs)
        self.write(path, content)
        return path

    def marker(self, number):
        self.write(BASELINE, f"-- Included migrations through: {number}\nSELECT 1;\n".encode())

    def declare(self, declaration=None):
        self.write(METADATA, json.dumps(declaration or self.declaration).encode())

    def run_checker(self, *args):
        return subprocess.run(
            [sys.executable, "-B", str(CHECKER), "--root", str(self.root), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )

    def assert_rejected(self, rule, modes=MODES):
        for mode in modes:
            with self.subTest(mode=mode):
                result = self.run_checker(*mode)
                output = result.stdout + result.stderr
                self.assertNotEqual(0, result.returncode, output)
                self.assertIn(rule, output)
                self.assertNotIn("Traceback", output)
                self.assertNotRegex(result.stdout, r"(?m)^00[0-9]{2}$")

    def test_exact_continuation_passes_all_validation_modes(self):
        for mode in MODES:
            with self.subTest(mode=mode):
                result = self.run_checker(*mode)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                if mode == ("--next",):
                    self.assertEqual("0038\n", result.stdout)

    def test_next_and_synchronized_baseline_can_advance_without_restoring_history(self):
        self.add_migration("0038_demo_new.sql", depends_on="0037_demo_checkpoint.sql")
        self.marker("0038")
        for mode in MODES:
            result = self.run_checker(*mode)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("0039\n", self.run_checker("--next").stdout)

    def test_missing_declaration_never_becomes_accepted_historical_debt(self):
        (self.root / METADATA).unlink()
        self.checkpoint()
        self.assert_rejected("MIGRATION_DEPENDENCY_MISSING")

    def test_invalid_declarations_fail_closed(self):
        cases = [
            ("dependent", "sql/postgresql/migrations/0033_demo_unknown.sql"),
            ("dependent", "../" + ARCHIVE),
            ("sha256", "0" * 64),
            ("sha256", "invalid"),
            ("missing_dependency", "0030_demo_other.sql"),
            ("missing_dependency", "0038_demo_future.sql"),
        ]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                declaration = copy.deepcopy(self.declaration)
                declaration["dependencies"][0][field] = value
                self.declare(declaration)
                self.assert_rejected("baseline continuation")

    def test_invalid_schema_and_duplicate_json_keys_fail_closed(self):
        valid = json.dumps(self.declaration)
        for raw in ("{", "[]", "null", valid.replace('"version": 1', '"version": 1, "version": 1'),
                    valid.replace('"version": 1', '"version": true'),
                    valid.replace('"version": 1', '"version": 1, "unknown": true')):
            with self.subTest(raw=raw[:35]):
                self.write(METADATA, raw.encode())
                self.assert_rejected("baseline continuation")

    def test_source_and_cutoff_are_required_and_bounded(self):
        for field, value in (("source", ""), ("path", "elsewhere.sql"),
                             ("included_through", "37"), ("included_through", "0031"),
                             ("included_through", "0038")):
            with self.subTest(field=field, value=value):
                declaration = copy.deepcopy(self.declaration)
                declaration["baseline"][field] = value
                self.declare(declaration)
                self.assert_rejected("baseline continuation")

    def test_duplicate_declared_edge_is_invalid(self):
        self.declaration["dependencies"] *= 2
        self.declare()
        self.assert_rejected("baseline continuation")

    def test_declaration_cannot_cover_an_existing_dependency(self):
        self.add_migration(MISSING)
        self.assert_rejected("baseline continuation")

    def test_changed_archive_fails_even_when_git_records_changed_bytes(self):
        self.write(ARCHIVE, (self.root / ARCHIVE).read_bytes() + b"-- changed\n")
        self.checkpoint()
        self.assert_rejected("baseline continuation")

    def test_repinning_archive_cannot_bypass_git_immutability(self):
        content = (self.root / ARCHIVE).read_bytes() + b"-- changed\n"
        self.write(ARCHIVE, content)
        self.declaration["dependencies"][0]["sha256"] = hashlib.sha256(content).hexdigest()
        self.declare()
        self.assert_rejected("MIGRATION_IMMUTABLE_CHANGED")

    def test_rewriting_adopted_declaration_is_rejected_including_planning(self):
        self.declaration["baseline"]["source"] = "Different unreviewed source"
        self.declare()
        self.assert_rejected("MIGRATION_IMMUTABLE_CHANGED",
                             modes=MODES + (("--list-forward", "--after-baseline", "0037"),))

    def test_missing_and_invalid_baselines_block_next_as_well_as_checks(self):
        for content, rule in (
            (None, "MIGRATION_BASELINE_MISSING"),
            (b"SELECT 1;\n", "MIGRATION_BASELINE_MARKER_MISSING"),
            (b"-- Included migrations through: 0036\n", "MIGRATION_BASELINE_MARKER_MISMATCH"),
            (b"-- Included migrations through: 0038\n", "MIGRATION_BASELINE_MARKER_MISMATCH"),
            (b"-- Included migrations through: 0037\n" * 2, "MIGRATION_BASELINE_MARKER_DUPLICATE"),
            (b"\xff", "MIGRATION_BASELINE_UTF8"),
        ):
            with self.subTest(rule=rule, content=content):
                if content is None:
                    (self.root / BASELINE).unlink()
                else:
                    self.write(BASELINE, content)
                self.assert_rejected(rule)

    def test_duplicate_marker_far_from_header_is_rejected(self):
        self.write(BASELINE, b"-- Included migrations through: 0037\n" + b"-- pad\n" * 2000
                   + b"-- Included migrations through: 0037\n")
        self.assert_rejected("MIGRATION_BASELINE_MARKER_DUPLICATE")

    def test_new_file_cannot_inherit_historical_dependency_exemption(self):
        self.add_migration("0038_demo_new.sql", depends_on=MISSING)
        self.marker("0038")
        self.checkpoint()
        self.assert_rejected("MIGRATION_DEPENDENCY_MISSING")

    def test_undeclared_second_missing_dependency_is_rejected(self):
        self.add_migration("0038_demo_new.sql", depends_on="0030_demo_unknown.sql")
        self.marker("0038")
        self.assert_rejected("MIGRATION_DEPENDENCY_MISSING")

    def test_reverse_dependency_and_duplicate_numbers_still_block(self):
        self.add_migration("0038_demo_new.sql", depends_on="0039_demo_later.sql")
        self.add_migration("0039_demo_later.sql")
        self.add_migration("0039_demo_collision.sql")
        self.marker("0039")
        self.checkpoint()
        self.assert_rejected("MIGRATION_DEPENDENCY_ORDER")
        self.assert_rejected("MIGRATION_DUPLICATE_NUMBER")

    def test_headers_are_not_waived_by_continuation(self):
        path = self.add_migration("0038_demo_new.sql")
        self.write(path, (self.root / path).read_bytes().replace(b"-- Rollback:", b"-- Missing:"))
        self.marker("0038")
        self.assert_rejected("MIGRATION_HEADER")

    def test_forward_list_requires_an_explicit_baseline_start(self):
        self.assert_rejected("--after-baseline", modes=(("--list-forward",),))

    def test_forward_list_only_contains_migrations_after_selected_baseline(self):
        self.add_migration("0038_demo_new.sql", depends_on="0037_demo_checkpoint.sql")
        self.add_migration("0039_demo_later.sql", depends_on="0038_demo_new.sql")
        self.marker("0039")
        for start, expected in (("0037", "sql/postgresql/migrations/0038_demo_new.sql\n"
                               "sql/postgresql/migrations/0039_demo_later.sql\n"),
                                ("0038", "sql/postgresql/migrations/0039_demo_later.sql\n"),
                                ("0039", "")):
            result = self.run_checker("--list-forward", "--after-baseline", start)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(expected, result.stdout)

    def test_forward_start_cannot_precede_cutoff_or_exceed_current_baseline(self):
        for start in ("0036", "0038", "37", "-1", "nope"):
            with self.subTest(start=start):
                result = self.run_checker("--list-forward", "--after-baseline", start)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn(ARCHIVE, result.stdout.splitlines())

    def test_forward_list_validates_baseline_before_printing_any_paths(self):
        self.marker("0036")
        self.assert_rejected("MIGRATION_BASELINE_MARKER_MISMATCH",
                             modes=(("--list-forward", "--after-baseline", "0037"),))

    def test_complete_chain_keeps_original_forward_list_behavior(self):
        (self.root / METADATA).unlink()
        (self.root / ARCHIVE).unlink()
        (self.root / "sql/postgresql/migrations/0037_demo_checkpoint.sql").unlink()
        self.add_migration("0001_demo_table.sql")
        self.add_migration("0002_demo_seed.sql", depends_on="0001_demo_table.sql")
        self.marker("0002")
        self.checkpoint()
        for mode in MODES:
            result = self.run_checker(*mode)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        result = self.run_checker("--list-forward")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("sql/postgresql/migrations/0001_demo_table.sql\n"
                         "sql/postgresql/migrations/0002_demo_seed.sql\n", result.stdout)


if __name__ == "__main__":
    unittest.main()
