import hashlib
import re
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_migrations import (  # noqa: E402
    analyze_migrations,
    baseline_marker_findings,
    current_migration_files,
    forward_migration_files,
)
from common import GateConfigurationError  # noqa: E402
from test_quality_gates import migration  # noqa: E402


class ModelingMigrationPackagingTest(unittest.TestCase):
    def setUp(self):
        # Synthetic historical packaging, independent of deleted production SQL.
        # Override only the fixture checksum policy; hashing/validation stay real.
        self.forward_path = "sql/postgresql/migrations/0015_modeling_biz-theme-remove-and-data-domain.sql"
        self.rollback_path = "sql/postgresql/migrations/0015_modeling_biz-theme-remove-and-data-domain_rollback.sql"
        self.files = dict([
            migration("0013_modeling_legacy-data-adaptation.sql"),
            migration("0014_governance_standard-quality-rule.sql"),
        ])
        self.files[self.forward_path] = (
            b"-- Migration: 0015 fixture\n-- Purpose: historical packaging fixture\n"
            b"-- Target: fixture database\n-- Rollback: manual recovery\nSELECT 1;\n"
        )
        self.files[self.rollback_path] = b"-- Manual-only synthetic recovery fixture\nSELECT 2;\n"
        checksums = {path: hashlib.sha256(self.files[path]).hexdigest()
                     for path in (self.forward_path, self.rollback_path)}
        policy = patch.dict("check_migrations.LEGACY_ARTIFACT_SHA256", checksums, clear=True)
        policy.start()
        self.addCleanup(policy.stop)

    def test_only_exact_historical_rollback_is_excluded(self):
        forward = forward_migration_files(self.files)
        self.assertFalse(any(path.endswith("_rollback.sql") for path in forward))
        self.assertTrue(any(path.endswith("0015_modeling_biz-theme-remove-and-data-domain.sql") for path in forward))
        self.assertEqual([], analyze_migrations(self.files))

    def test_modified_historical_artifact_fails_closed(self):
        for path in (self.forward_path, self.rollback_path):
            with self.subTest(path=path):
                changed = dict(self.files)
                changed[path] += b"\n-- modified"
                with self.assertRaises(GateConfigurationError):
                    forward_migration_files(changed)

    def test_packaging_does_not_mutate_original_sql_bytes(self):
        originals = dict(self.files)
        forward_migration_files(self.files)
        self.assertEqual(originals, self.files)

    def test_new_unclassified_rollback_is_not_exempt(self):
        path = "sql/postgresql/migrations/0017_modeling_new_rollback.sql"
        self.files[path] = b"-- not a production migration"
        self.assertTrue(any(f.rule == "MIGRATION_FILENAME" and path in f.paths
                            for f in analyze_migrations(self.files)))


class CurrentBaselineTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[3]
        self.baseline = (self.root / "sql/postgresql/datahub-center.sql").read_text(encoding="utf-8-sig")

    def test_current_baseline_marker_tracks_latest_migration(self):
        findings = baseline_marker_findings(current_migration_files(self.root), self.baseline.encode())
        self.assertEqual([], [(finding.rule, finding.message) for finding in findings])

    def test_baseline_uses_official_extensions_and_no_invalid_expression_index(self):
        for forbidden in ("$libdir/btree_gist", "$libdir/pg_trgm", '"tsrange(valid_from',
                          'CREATE INDEX "ex_std_code_mapping_active_period"'):
            self.assertFalse(forbidden in self.baseline, f"baseline contains invalid extension/index form: {forbidden}")
        for extension in ("btree_gist", "pg_trgm"):
            self.assertTrue(f"CREATE EXTENSION IF NOT EXISTS {extension}" in self.baseline,
                            f"baseline is missing official extension: {extension}")

    def test_baseline_retires_code_mappings_and_preserves_standard_codes(self):
        # 0180 已退役跨系统码值映射及其约束；标准码集与码项必须继续保留。
        prefix = r'(?:"?datahub-center"?\.)?'
        for table in ("dh_governance_standard_code_mapping", "dh_governance_standard_source_code_set"):
            dropped = list(re.finditer(
                rf'DROP TABLE IF EXISTS\s+{prefix}"?{table}"?\s+RESTRICT\s*;', self.baseline,
            ))
            self.assertTrue(dropped, f"baseline is missing retirement of {table}")
            self.assertFalse(re.search(
                rf'CREATE TABLE(?: IF NOT EXISTS)?\s+{prefix}"?{table}"?\s*\(',
                self.baseline[dropped[-1].end():],
            ), f"baseline recreates retired table {table}")
        for table in ("dh_governance_standard_code_set", "dh_governance_standard_code_item"):
            created = list(re.finditer(
                rf'CREATE TABLE(?: IF NOT EXISTS)?\s+{prefix}"?{table}"?\s*\(', self.baseline,
            ))
            self.assertTrue(created, f"baseline is missing retained table {table}")
            self.assertFalse(re.search(
                rf'DROP TABLE(?: IF EXISTS)?\s+{prefix}"?{table}"?(?:\s|;)',
                self.baseline[created[-1].end():],
            ), f"baseline drops retained table {table}")


if __name__ == "__main__":
    unittest.main()
