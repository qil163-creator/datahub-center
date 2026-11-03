from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence

from common import (
    Finding,
    GateConfigurationError,
    classify_findings,
    cli_guard,
    decode_snapshot,
    default_base_ref,
    load_git_files,
    normalize_path,
    print_report,
    resolve_repo_root,
)


POLICY_PATH = Path(__file__).with_name("error_code_ranges.json")
MODULE_PATH_RE = re.compile(r"^yudao-module-(?P<owner>[a-z0-9-]+)/")
PACKAGE_RE = re.compile(
    r"^\s*package\s+cn\.iocoder\.yudao\.module\.(?P<owner>[a-z0-9_]+)"
    r"(?:\.[a-zA-Z0-9_.]+)?;\s*$",
    re.MULTILINE,
)
DECLARATION_RE = re.compile(
    r"\bErrorCode\s+(?P<name>[A-Z][A-Z0-9_]*)\s*=\s*"
    r"new\s+ErrorCode\s*\(\s*(?P<code>[0-9][0-9_]*)\s*,",
    re.MULTILINE,
)
CANONICAL_LITERAL_RE = re.compile(r"^[0-9]_[0-9]{3}_[0-9]{3}_[0-9]{3}$")


@dataclass(frozen=True)
class ErrorCodeDeclaration:
    owner: str
    package_owner: str | None
    path: str
    name: str
    literal: str
    value: int
    line: int

    @property
    def identity(self) -> str:
        return f"{self.path}:{self.name}:{self.value}"


def is_error_code_source(path: str) -> bool:
    normalized = normalize_path(path)
    return (
        normalized.startswith("yudao-module-")
        and "/src/main/java/" in normalized
        and normalized.endswith("/ErrorCodeConstants.java")
    )


def current_error_code_files(repo_root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for module in sorted(repo_root.glob("yudao-module-*")):
        if not module.is_dir():
            continue
        for path in module.rglob("ErrorCodeConstants.java"):
            relative = normalize_path(path.relative_to(repo_root))
            if not is_error_code_source(relative):
                continue
            try:
                result[relative] = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError as exc:
                raise GateConfigurationError(
                    f"error-code source is not valid UTF-8: {relative}: {exc}"
                ) from exc
    return result


def parse_declarations(files: Mapping[str, str]) -> list[ErrorCodeDeclaration]:
    declarations: list[ErrorCodeDeclaration] = []
    for path, text in sorted(files.items()):
        path_match = MODULE_PATH_RE.match(path)
        if not path_match:
            continue
        owner = path_match.group("owner").replace("-", "_")
        package_match = PACKAGE_RE.search(text)
        package_owner = package_match.group("owner") if package_match else None
        for match in DECLARATION_RE.finditer(text):
            literal = match.group("code")
            declarations.append(
                ErrorCodeDeclaration(
                    owner=owner,
                    package_owner=package_owner,
                    path=path,
                    name=match.group("name"),
                    literal=literal,
                    value=int(literal.replace("_", "")),
                    line=text.count("\n", 0, match.start()) + 1,
                )
            )
    return declarations


def parse_range_value(value: object, *, owner: str, field: str) -> int:
    if not isinstance(value, str) or not CANONICAL_LITERAL_RE.fullmatch(value):
        raise GateConfigurationError(
            f"range {owner}.{field} must use canonical N_NNN_NNN_NNN text, got {value!r}"
        )
    return int(value.replace("_", ""))


def load_range_policy(path: Path = POLICY_PATH) -> dict[str, list[tuple[int, int]]]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GateConfigurationError(f"cannot read error-code range policy {path}: {exc}") from exc
    ranges_raw = raw.get("ranges")
    if not isinstance(ranges_raw, dict) or not ranges_raw:
        raise GateConfigurationError("error-code range policy must contain a non-empty 'ranges' object")
    policy: dict[str, list[tuple[int, int]]] = {}
    occupied: list[tuple[int, int, str]] = []
    for raw_owner, entries in ranges_raw.items():
        owner = str(raw_owner).replace("-", "_")
        if not isinstance(entries, list) or not entries:
            raise GateConfigurationError(f"range owner {owner} must have at least one range")
        owner_ranges: list[tuple[int, int]] = []
        for entry in entries:
            if not isinstance(entry, dict):
                raise GateConfigurationError(f"range entry for {owner} must be an object")
            start = parse_range_value(entry.get("start"), owner=owner, field="start")
            end = parse_range_value(entry.get("end"), owner=owner, field="end")
            if start >= end:
                raise GateConfigurationError(f"range for {owner} is empty or reversed: {start}..{end}")
            for other_start, other_end, other_owner in occupied:
                if start < other_end and other_start < end:
                    raise GateConfigurationError(
                        f"ranges overlap: {owner} [{start},{end}) and {other_owner} [{other_start},{other_end})"
                    )
            occupied.append((start, end, owner))
            owner_ranges.append((start, end))
        policy[owner] = sorted(owner_ranges)
    return policy


def analyze_error_codes(
    files: Mapping[str, str],
    policy: Mapping[str, Sequence[tuple[int, int]]],
) -> tuple[list[Finding], list[ErrorCodeDeclaration]]:
    findings: list[Finding] = []
    declarations = parse_declarations(files)
    paths_with_declarations = {declaration.path for declaration in declarations}

    for path, text in sorted(files.items()):
        path_match = MODULE_PATH_RE.match(path)
        if not path_match:
            continue
        owner = path_match.group("owner").replace("-", "_")
        expected_fragment = f"yudao-module-{owner.replace('_', '-')}-api/src/main/java/"
        if expected_fragment not in path:
            findings.append(
                Finding(
                    "ERROR_CODE_LOCATION",
                    path,
                    f"{path} must live in the owning module's *-api src/main/java tree",
                    (path,),
                )
            )
        package_match = PACKAGE_RE.search(text)
        package_owner = package_match.group("owner") if package_match else None
        if package_owner != owner:
            findings.append(
                Finding(
                    "ERROR_CODE_PACKAGE_OWNER",
                    f"{path}:{package_owner or '<missing>'}",
                    f"{path} belongs to module {owner} but declares package owner {package_owner or '<missing>'}",
                    (path,),
                )
            )
        if path not in paths_with_declarations:
            findings.append(
                Finding(
                    "ERROR_CODE_UNPARSED_FILE",
                    path,
                    f"{path} contains no statically parseable 'ErrorCode NAME = new ErrorCode(<literal>, ...)' declaration",
                    (path,),
                )
            )

    by_value: dict[int, list[ErrorCodeDeclaration]] = {}
    for declaration in declarations:
        by_value.setdefault(declaration.value, []).append(declaration)
        if not CANONICAL_LITERAL_RE.fullmatch(declaration.literal):
            findings.append(
                Finding(
                    "ERROR_CODE_LITERAL_FORMAT",
                    declaration.identity,
                    f"{declaration.path}:{declaration.line} {declaration.name} uses {declaration.literal}; expected N_NNN_NNN_NNN",
                    (declaration.path,),
                )
            )
        allowed_ranges = policy.get(declaration.owner)
        if allowed_ranges and not any(start <= declaration.value < end for start, end in allowed_ranges):
            printable = ", ".join(f"[{start}, {end})" for start, end in allowed_ranges)
            findings.append(
                Finding(
                    "ERROR_CODE_OUT_OF_RANGE",
                    declaration.identity,
                    f"{declaration.path}:{declaration.line} {declaration.name}={declaration.literal} is outside {declaration.owner} ranges {printable}",
                    (declaration.path,),
                )
            )

    for value, group in sorted(by_value.items()):
        if len(group) < 2:
            continue
        identities = sorted(item.identity for item in group)
        locations = ", ".join(
            f"{item.path}:{item.line} ({item.name})" for item in sorted(group, key=lambda item: item.identity)
        )
        findings.append(
            Finding(
                "ERROR_CODE_DUPLICATE",
                f"{value}:{'|'.join(identities)}",
                f"error code {value} is declared {len(group)} times: {locations}",
                tuple(sorted({item.path for item in group})),
            )
        )
    return findings, declarations


def run_check(
    repo_root: Path,
    *,
    base_ref: str,
    strict: bool,
    show_debt: bool,
    policy_path: Path = POLICY_PATH,
) -> int:
    policy = load_range_policy(policy_path)
    current_files = current_error_code_files(repo_root)
    current_findings, declarations = analyze_error_codes(current_files, policy)
    baseline_findings: list[Finding] = []
    if not strict:
        baseline_bytes = load_git_files(repo_root, base_ref, is_error_code_source)
        baseline_findings, _ = analyze_error_codes(decode_snapshot(baseline_bytes), policy)
    blocking, debt = classify_findings(
        current_findings,
        baseline_findings,
        strict=strict,
    )
    return print_report(
        "error-codes",
        blocking,
        debt,
        summary=f"checked {len(declarations)} declaration(s) in {len(current_files)} file(s), base={base_ref}",
        show_debt=show_debt,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Check error-code uniqueness, canonical literals, package ownership, and allocated ranges."
    )
    parser.add_argument("--root", help="yudao-cloud repository root; auto-detected by default")
    parser.add_argument(
        "--base-ref",
        help="Git ref used to grandfather existing debt (default: QUALITY_BASE_REF or HEAD)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="fail on every finding, including findings already present at the base ref",
    )
    parser.add_argument("--show-debt", action="store_true")
    parser.add_argument(
        "--policy",
        type=Path,
        default=POLICY_PATH,
        help=f"range policy JSON (default: {POLICY_PATH})",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return run_check(
        resolve_repo_root(args.root),
        base_ref=default_base_ref(args.base_ref),
        strict=args.strict,
        show_debt=args.show_debt,
        policy_path=args.policy,
    )


if __name__ == "__main__":
    cli_guard(main)
