import hashlib
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_migrations import analyze_migrations, forward_migration_files, leading_headers
from common import GateConfigurationError


class DeliveredMigrationHeadersTest(unittest.TestCase):
    path = "sql/postgresql/migrations/0043_asset_market-delivery-apply-index.sql"

    def setUp(self):
        # This packaging contract must remain testable after the production SQL
        # archive is retired. Only replace the fixture hash, never the validator.
        self.original = "-- 编号：0043\n-- 用途：独立历史包装夹具\nSELECT 1;\n".encode()
        policy = patch.dict("check_migrations.LEGACY_ARTIFACT_SHA256",
                            {self.path: hashlib.sha256(self.original).hexdigest()}, clear=True)
        policy.start()
        self.addCleanup(policy.stop)

    def test_delivered_chinese_headers_have_pinned_validation_metadata(self):
        files = {self.path: self.original}
        headers, _ = leading_headers(forward_migration_files(files)[self.path].decode())
        self.assertEqual("0043", headers["migration"])
        self.assertEqual("0042_asset_grant-source-delivery.sql", headers["depends on"])
        self.assertEqual(6, len(headers))
        self.assertEqual(self.original, files[self.path])

    def test_any_byte_change_to_delivered_file_is_rejected(self):
        with self.assertRaises(GateConfigurationError):
            forward_migration_files({self.path: self.original + b"\n"})

    def test_another_migration_does_not_inherit_header_exemption(self):
        files = {"sql/postgresql/migrations/0044_asset_new-index.sql": self.original}
        findings = analyze_migrations(files)
        self.assertEqual(6, sum(f.rule == "MIGRATION_HEADER" for f in findings))


if __name__ == "__main__":
    unittest.main()
