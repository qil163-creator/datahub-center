from __future__ import annotations

import sys
import unittest
from pathlib import Path


QUALITY_ROOT = Path(__file__).resolve().parents[1]
if str(QUALITY_ROOT) not in sys.path:
    sys.path.insert(0, str(QUALITY_ROOT))

from check_error_codes import analyze_error_codes  # noqa: E402
from check_migrations import (  # noqa: E402
    analyze_migrations,
    allocation_findings,
    baseline_marker_findings,
    immutability_findings,
    next_migration_number,
)
from check_module_dependencies import analyze_module_boundaries  # noqa: E402
from common import classify_findings  # noqa: E402


def migration(name: str, *, depends_on: str = "baseline state") -> tuple[str, bytes]:
    number = name[:4]
    content = f"""-- Migration: {number} fixture
-- Purpose: exercise the migration quality gate
-- Depends on: {depends_on}
-- Target: existing PostgreSQL database
-- Repeatable: yes; fixture is additive
-- Rollback: drop fixture objects

BEGIN;
COMMIT;
"""
    return f"sql/postgresql/migrations/{name}", content.encode()


def error_file(owner: str, declarations: str, *, package_owner: str | None = None) -> tuple[str, str]:
    package_owner = package_owner or owner
    path = (
        f"yudao-module-{owner}/yudao-module-{owner}-api/src/main/java/"
        f"cn/iocoder/yudao/module/{owner}/enums/ErrorCodeConstants.java"
    )
    text = f"""package cn.iocoder.yudao.module.{package_owner}.enums;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
public interface ErrorCodeConstants {{
{declarations}
}}
"""
    return path, text


class MigrationGateTest(unittest.TestCase):
    def test_valid_chain(self) -> None:
        first_path, first = migration("0001_demo_table.sql")
        second_path, second = migration(
            "0002_demo_seed.sql",
            depends_on="migrations/0001_demo_table.sql",
        )
        self.assertEqual([], analyze_migrations({first_path: first, second_path: second}))

    def test_rejects_duplicate_number_and_missing_header(self) -> None:
        first_path, first = migration("0001_demo_table.sql")
        second_path, second = migration("0001_demo_seed.sql")
        second = second.replace(b"-- Rollback: drop fixture objects\n", b"")
        rules = {
            finding.rule
            for finding in analyze_migrations({first_path: first, second_path: second})
        }
        self.assertIn("MIGRATION_DUPLICATE_NUMBER", rules)
        self.assertIn("MIGRATION_HEADER", rules)

    def test_rejects_missing_numbered_dependency(self) -> None:
        path, content = migration(
            "0002_demo_seed.sql",
            depends_on="migrations/0001_demo_table.sql",
        )
        rules = {finding.rule for finding in analyze_migrations({path: content})}
        self.assertIn("MIGRATION_DEPENDENCY_MISSING", rules)

    def test_full_filename_in_migration_header_is_not_a_dependency(self) -> None:
        first_path, first = migration("0001_demo_table.sql")
        second_name = "0002_demo_seed.sql"
        second_path, second = migration(second_name, depends_on=Path(first_path).name)
        second = second.replace(b"-- Migration: 0002 fixture", f"-- Migration: {second_name}".encode())
        self.assertEqual([], analyze_migrations({first_path: first, second_path: second}))

    def test_dependency_header_still_rejects_explicit_self_reference(self) -> None:
        name = "0001_demo_table.sql"
        path, content = migration(name, depends_on=name)
        rules = {finding.rule for finding in analyze_migrations({path: content})}
        self.assertIn("MIGRATION_DEPENDENCY_ORDER", rules)

    def test_dependency_in_continued_header_is_still_validated(self) -> None:
        path, content = migration("0002_demo_seed.sql")
        content = content.replace(
            b"-- Depends on: baseline state",
            b"-- Depends on: baseline state\n-- 0001_demo_missing.sql",
        )
        rules = {finding.rule for finding in analyze_migrations({path: content})}
        self.assertIn("MIGRATION_DEPENDENCY_MISSING", rules)

    def test_immutability_uses_exact_bytes(self) -> None:
        path, old = migration("0001_demo_table.sql")
        findings = immutability_findings(
            {path: old + b"\n"},
            {path: old},
            base_ref="main",
        )
        self.assertEqual(["MIGRATION_IMMUTABLE_CHANGED"], [item.rule for item in findings])

    def test_baseline_marker_matches_highest_migration(self) -> None:
        path, content = migration("0007_demo_table.sql")
        self.assertEqual(
            [],
            baseline_marker_findings(
                {path: content},
                b"-- Included migrations through: 0007\nSELECT 1;\n",
            ),
        )

    def test_baseline_marker_rejects_stale_number(self) -> None:
        path, content = migration("0007_demo_table.sql")
        rules = {
            finding.rule
            for finding in baseline_marker_findings(
                {path: content},
                b"-- Included migrations through: 0006\nSELECT 1;\n",
            )
        }
        self.assertIn("MIGRATION_BASELINE_MARKER_MISMATCH", rules)

    def test_next_number_starts_at_one_and_uses_global_max(self) -> None:
        self.assertEqual(1, next_migration_number({}))
        first_path, first = migration("0002_demo_table.sql")
        second_path, second = migration("0010_demo_seed.sql")
        self.assertEqual(
            11,
            next_migration_number({first_path: first, second_path: second}),
        )

    def test_new_allocations_must_follow_base_consecutively(self) -> None:
        base_path, base = migration("0003_demo_table.sql")
        new_path, new = migration("0005_demo_seed.sql")
        rules = {
            finding.rule
            for finding in allocation_findings(
                {base_path: base, new_path: new},
                {base_path: base},
                base_ref="main",
            )
        }
        self.assertIn("MIGRATION_ALLOCATION_ORDER", rules)


class ErrorCodeGateTest(unittest.TestCase):
    POLICY = {"demo": [(1_111_000_000, 1_112_000_000)]}

    def test_accepts_unique_canonical_code_in_owner_range(self) -> None:
        path, text = error_file(
            "demo",
            '    ErrorCode NOT_FOUND = new ErrorCode(1_111_000_001, "missing");',
        )
        findings, declarations = analyze_error_codes({path: text}, self.POLICY)
        self.assertEqual(1, len(declarations))
        self.assertEqual([], findings)

    def test_rejects_duplicate_and_out_of_range(self) -> None:
        first_path, first_text = error_file(
            "demo",
            '    ErrorCode FIRST = new ErrorCode(1_999_000_001, "first");\n'
            '    ErrorCode SECOND = new ErrorCode(1_999_000_001, "second");',
        )
        rules = {
            finding.rule
            for finding in analyze_error_codes({first_path: first_text}, self.POLICY)[0]
        }
        self.assertIn("ERROR_CODE_DUPLICATE", rules)
        self.assertIn("ERROR_CODE_OUT_OF_RANGE", rules)

    def test_rejects_wrong_package_owner_and_literal_shape(self) -> None:
        path, text = error_file(
            "demo",
            '    ErrorCode BAD = new ErrorCode(1_111_01_001, "bad");',
            package_owner="other",
        )
        rules = {
            finding.rule
            for finding in analyze_error_codes({path: text}, self.POLICY)[0]
        }
        self.assertIn("ERROR_CODE_PACKAGE_OWNER", rules)
        self.assertIn("ERROR_CODE_LITERAL_FORMAT", rules)


class ModuleBoundaryGateTest(unittest.TestCase):
    def test_allows_cross_module_api_import(self) -> None:
        files = {
            "yudao-module-alpha/yudao-module-alpha-server/src/main/java/Alpha.java":
                "package cn.iocoder.yudao.module.alpha.service;\n"
                "import cn.iocoder.yudao.module.beta.api.BetaApi;\n"
        }
        self.assertEqual([], analyze_module_boundaries(files))

    def test_allows_intentional_third_party_package_shim(self) -> None:
        files = {
            "yudao-module-alpha/yudao-module-alpha-server/src/main/java/Shim.java":
                "package org.example.compat;\n"
        }
        self.assertEqual([], analyze_module_boundaries(files))

    def test_rejects_server_dependency_and_internal_import(self) -> None:
        files = {
            "yudao-module-alpha/yudao-module-alpha-server/pom.xml": """
                <project><dependencies><dependency>
                  <artifactId>yudao-module-beta-server</artifactId>
                </dependency></dependencies></project>
            """,
            "yudao-module-alpha/yudao-module-alpha-server/src/main/java/Alpha.java":
                "package cn.iocoder.yudao.module.alpha.service;\n"
                "import cn.iocoder.yudao.module.beta.service.BetaService;\n",
        }
        rules = {finding.rule for finding in analyze_module_boundaries(files)}
        self.assertIn("MODULE_SERVER_DEPENDENCY", rules)
        self.assertIn("MODULE_INTERNAL_IMPORT", rules)

    def test_rejects_framework_business_dependency(self) -> None:
        files = {
            "yudao-framework/example/pom.xml": """
                <project><dependencies><dependency>
                  <artifactId>yudao-module-system-api</artifactId>
                </dependency></dependencies></project>
            """
        }
        rules = {finding.rule for finding in analyze_module_boundaries(files)}
        self.assertIn("FRAMEWORK_BUSINESS_DEPENDENCY", rules)


class DebtClassificationTest(unittest.TestCase):
    def test_only_new_finding_blocks_in_diff_mode(self) -> None:
        _, bad_text = error_file(
            "demo",
            '    ErrorCode BAD = new ErrorCode(1_999_000_001, "bad");',
        )
        path = next(iter(dict([error_file("demo", "")]).keys()))
        current, _ = analyze_error_codes({path: bad_text}, ErrorCodeGateTest.POLICY)
        blocking, debt = classify_findings(current, current, strict=False)
        self.assertEqual([], blocking)
        self.assertEqual(
            {finding.fingerprint for finding in current},
            {finding.fingerprint for finding in debt},
        )


if __name__ == "__main__":
    unittest.main()
