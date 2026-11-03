from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import check_dbswitch_architecture
import check_error_codes
import check_migrations
import check_module_dependencies
from common import (
    EXIT_CONFIGURATION,
    EXIT_OK,
    EXIT_VIOLATIONS,
    cli_guard,
    default_base_ref,
    resolve_repo_root,
)


def run_self_tests() -> int:
    quality_root = Path(__file__).resolve().parent
    process = subprocess.run(
        [
            sys.executable,
            "-B",
            "-m",
            "unittest",
            "discover",
            "-s",
            str(quality_root / "tests"),
            "-v",
        ],
        check=False,
    )
    return EXIT_OK if process.returncode == 0 else EXIT_CONFIGURATION


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run every repository-local deterministic quality gate."
    )
    parser.add_argument("--root", help="yudao-cloud repository root; auto-detected by default")
    parser.add_argument(
        "--base-ref",
        help="Git ref used for debt and immutable-history comparison (default: QUALITY_BASE_REF or HEAD)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="fail on historical debt too; intended for debt-removal audits",
    )
    parser.add_argument(
        "--show-debt",
        action="store_true",
        help="print findings already present at the comparison ref",
    )
    parser.add_argument(
        "--no-immutability",
        action="store_true",
        help="skip migration byte comparison (not for CI or release checks)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run the quality-gate unit fixtures instead of scanning the repository",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.self_test:
        return run_self_tests()
    root = resolve_repo_root(args.root)
    base_ref = default_base_ref(args.base_ref)
    results = [
        check_dbswitch_architecture.run_check(root),
        check_migrations.run_check(
            root,
            base_ref=base_ref,
            strict=args.strict,
            show_debt=args.show_debt,
            check_immutability=not args.no_immutability,
        ),
        check_error_codes.run_check(
            root,
            base_ref=base_ref,
            strict=args.strict,
            show_debt=args.show_debt,
        ),
        check_module_dependencies.run_check(
            root,
            base_ref=base_ref,
            strict=args.strict,
            show_debt=args.show_debt,
        ),
    ]
    if EXIT_CONFIGURATION in results:
        return EXIT_CONFIGURATION
    if EXIT_VIOLATIONS in results:
        return EXIT_VIOLATIONS
    return EXIT_OK


if __name__ == "__main__":
    cli_guard(main)
