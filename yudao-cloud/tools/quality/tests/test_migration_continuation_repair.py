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


def digest(content):
    return hashlib.sha256(content).hexdigest()


class ContinuationRepairTest(unittest.TestCase):
    """Synthetic Git snapshots exercise the narrowly pinned adoption policy."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("pom.xml", b"<project/>")
        (self.root / "yudao-module-demo").mkdir()
        self.git("init", "-q")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        retired_path, retired = migration(
            "0032_governance_quality-collect-issue-default.sql",
            depends_on="0031_asset_delivery-lease-acceptance.sql",
        )
        self.retired_path, self.retired = retired_path, retired
        self.anchor_path, self.anchor = migration(
            "0144_modeling_drop-physical-tables.sql", depends_on="0134_demo_archive.sql",
        )
        self.archives = dict([
            migration("0133_modeling_drop-planning-change.sql",
                      depends_on="0131_demo_retired.sql; 0132_demo_retired.sql"),
            migration("0134_demo_archive.sql", depends_on="0133_modeling_drop-planning-change.sql"),
        ])
        self.old = self.declaration("0037", [{
            "dependent": retired_path, "sha256": digest(retired),
            "missing_dependency": "0031_asset_delivery-lease-acceptance.sql",
        }])
        first_path = next(iter(self.archives))
        self.new = self.declaration("0144", [{
            "dependent": first_path, "sha256": digest(self.archives[first_path]),
            "missing_dependency": missing,
        } for missing in ("0131_demo_retired.sql", "0132_demo_retired.sql")])
        values = {
            "RETIRED_CONTINUATION_SHA256": digest(self.old),
            "REPAIRED_CONTINUATION_SHA256": digest(self.new),
            "REPAIR_ANCHOR_SHA256": digest(self.anchor),
            "ADOPTED_ARCHIVE_SHA256": {path: digest(data) for path, data in self.archives.items()},
        }
        for name, value in values.items():
            policy = patch.object(checker, name, value)
            policy.start()
            self.addCleanup(policy.stop)
        self.write(self.anchor_path, self.anchor)
        self.write(checker.CONTINUATION_PATH, self.old)
        self.marker("0144")
        self.checkpoint()
        for path, data in self.archives.items():
            self.write(path, data)
        self.write(checker.CONTINUATION_PATH, self.new)
        path, content = migration("0145_demo_new.sql", depends_on=Path(self.anchor_path).name)
        self.write(path, content)
        self.marker("0145")

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

    def declaration(self, cutoff, dependencies):
        return json.dumps({"version": 1, "baseline": {
            "path": checker.BASELINE_PATH, "included_through": cutoff,
            "source": "Explicitly reviewed fixture, not deployed database evidence",
        }, "dependencies": dependencies}).encode()

    def run_modes(self, *, accepted, base_ref="HEAD", include_check=True):
        modes = [
            lambda: checker.run_next(self.root, base_ref=base_ref),
            lambda: checker.run_list_forward(self.root, "0144", base_ref=base_ref),
        ]
        if include_check:
            modes += [lambda strict=strict: checker.run_check(
                self.root, base_ref=base_ref, strict=strict,
                show_debt=False, check_immutability=True,
            ) for strict in (False, True)]
        outputs = []
        for mode in modes:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                try:
                    result = mode()
                except GateConfigurationError as error:
                    result = 2
                    print(error)
            self.assertEqual(accepted, result == 0, output.getvalue())
            outputs.append(output.getvalue())
        return outputs

    def test_exact_repair_checks_and_planning_pass_without_mutating_sql(self):
        originals = checker.current_migration_files(self.root)
        outputs = self.run_modes(accepted=True)
        self.assertEqual("0146\n", outputs[0])
        self.assertEqual("sql/postgresql/migrations/0145_demo_new.sql\n", outputs[1])
        self.assertEqual(originals, checker.current_migration_files(self.root))

    def test_adopted_repair_passes_in_clean_crlf_checkout(self):
        self.checkpoint()
        clone = self.root / "clone"
        self.git("-c", "core.autocrlf=true", "clone", "-q", str(self.root), str(clone))
        self.root = clone
        self.assertIn(b"\r\n", (clone / next(iter(self.archives))).read_bytes())
        self.run_modes(accepted=True)

    def test_every_adopted_archive_byte_is_pinned(self):
        for path, content in self.archives.items():
            with self.subTest(path=path):
                self.write(path, content + b"\n")
                self.run_modes(accepted=False)
                self.write(path, content)

    def test_missing_archive_blocks_repair(self):
        (self.root / next(iter(self.archives))).unlink()
        self.run_modes(accepted=False)

    def test_source_cutoff_or_checksum_changes_cannot_repin_declaration(self):
        for field, value in (("source", "Unreviewed replacement"), ("included_through", "0145")):
            changed = json.loads(self.new)
            changed["baseline"][field] = value
            self.write(checker.CONTINUATION_PATH, json.dumps(changed).encode())
            self.run_modes(accepted=False)
        changed = json.loads(self.new)
        changed["dependencies"][0]["sha256"] = "0" * 64
        self.write(checker.CONTINUATION_PATH, json.dumps(changed).encode())
        self.run_modes(accepted=False)

    def test_unapproved_previous_declaration_blocks_repair(self):
        changed = json.loads(self.old)
        changed["baseline"]["source"] = "Different old source"
        self.write(checker.CONTINUATION_PATH, json.dumps(changed).encode())
        self.checkpoint()
        self.write(checker.CONTINUATION_PATH, self.new)
        self.run_modes(accepted=False)

    def test_changed_anchor_is_never_waived(self):
        self.write(self.anchor_path, self.anchor + b"\n")
        self.run_modes(accepted=False)

    def test_unknown_lower_number_and_duplicate_number_block_planning(self):
        for filename in ("0001_demo_inserted.sql", "0134_demo_collision.sql"):
            path, content = migration(filename)
            self.write(path, content)
            self.run_modes(accepted=False)
            (self.root / path).unlink()

    def test_adoption_cannot_be_reused_after_commit_for_another_low_number(self):
        self.checkpoint()
        path, content = migration("0130_demo_inserted.sql")
        self.write(path, content)
        self.run_modes(accepted=False)

    def test_deleting_declaration_and_archives_cannot_bypass_planning_history(self):
        (self.root / checker.CONTINUATION_PATH).unlink()
        for path in self.archives:
            (self.root / path).unlink()
        _, standalone = migration(Path(self.anchor_path).name)
        self.write(self.anchor_path, standalone)
        self.run_modes(accepted=False, include_check=False)

    def test_older_valid_ref_reports_history_violations_without_configuration_exception(self):
        # Construct a separate older legitimate checkpoint. The repair must not
        # mistake that complete snapshot for the obsolete 0144 packaging state.
        self.write(self.retired_path, self.retired)
        _, checkpoint = migration("0037_demo_checkpoint.sql", depends_on=Path(self.retired_path).name)
        for path in checker.current_migration_files(self.root):
            if path != self.retired_path:
                (self.root / path).unlink()
        self.write("sql/postgresql/migrations/0037_demo_checkpoint.sql", checkpoint)
        self.write(checker.CONTINUATION_PATH, self.old)
        self.marker("0037")
        self.checkpoint()
        valid_ref = self.git("rev-parse", "HEAD").stdout.decode().strip()
        (self.root / self.retired_path).unlink()
        (self.root / "sql/postgresql/migrations/0037_demo_checkpoint.sql").unlink()
        self.write(self.anchor_path, self.anchor)
        for path, content in self.archives.items():
            self.write(path, content)
        self.write(checker.CONTINUATION_PATH, self.new)
        self.marker("0144")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = checker.run_check(self.root, base_ref=valid_ref, strict=False,
                                       show_debt=False, check_immutability=True)
        self.assertEqual(1, result)
        self.assertIn("MIGRATION_IMMUTABLE_DELETED", output.getvalue())


if __name__ == "__main__":
    unittest.main()
