from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Mapping

from common import (
    Finding,
    GateConfigurationError,
    classify_findings,
    cli_guard,
    default_base_ref,
    load_git_files,
    normalize_path,
    print_report,
    resolve_repo_root,
    run_git,
)


MIGRATION_DIR = "sql/postgresql/migrations"
BASELINE_PATH = "sql/postgresql/datahub-center.sql"
CONTINUATION_PATH = "tools/quality/migration_baseline_continuation.json"
# One reviewed packaging repair after 0032-0132 were retired and the original
# local 0133-0143 files were omitted from Git. These are exact bytes, not a
# general permission to rewrite continuation declarations or backfill numbers.
RETIRED_CONTINUATION_SHA256 = "fc9dd1ee11131dcc389ca7a00ba47ce0eb9c894a5d494f7e59fa83e65c71af93"
REPAIRED_CONTINUATION_SHA256 = "b1b6a6d7c887841340d0c05617fb195fb7552e37e3583dc970c6342bceed8f82"
REPAIR_ANCHOR_PATH = MIGRATION_DIR + "/0144_modeling_drop-physical-tables.sql"
REPAIR_ANCHOR_SHA256 = "c3bb1d0c5229847d0624f54269d221a4cc4a792abc4c3edcb02a7dd281f6daf4"
# 421288b3 删除归档却保留原承接声明；只认可该提交父版本中这段归档的原始内容。
ARCHIVE_RESTORATION_SOURCE_REF = "cace783550235641c77b2e3b9332b2e0254bffe9"
ARCHIVE_RESTORATION_FIRST = 133
ARCHIVE_RESTORATION_LAST = 190
ADOPTED_ARCHIVE_SHA256 = {
    MIGRATION_DIR + "/0133_modeling_drop-planning-change.sql":
        "2f515d0f2c7a3ce49a98d25a2435430097c5879d395a182ba1bfdc0e676da77d",
    MIGRATION_DIR + "/0134_modeling_drop-migration-evidence.sql":
        "6fe1e18daf9589563fcfa0ac25c8f2bb31df247a07522860724c1487df8631aa",
    MIGRATION_DIR + "/0135_modeling_shorten-core-table-comments.sql":
        "ae0929ea8459dd2093332cbbf04bd9f9486ac0c7e39e1feda6d6f4d8c697cd33",
    MIGRATION_DIR + "/0136_modeling_biz-category-data-domain-rel.sql":
        "2ffb7684eda16a26189e315eb8e556d5c012dc99d22463514044b1d93460b74d",
    MIGRATION_DIR + "/0137_modeling_logic-physical-biz-category.sql":
        "b5e2337d0d4183dd313bf025b80710a234b746c22d1b400319054a7115309b49",
    MIGRATION_DIR + "/0138_modeling_logic-model-profile.sql":
        "98b17ee8e9b4b9d932b420e59c663f3e146c4ef92472502887f99d432699bc8f",
    MIGRATION_DIR + "/0139_modeling_drop-design-check.sql":
        "331250b1169789ad8f825bacd66ac0d3ec3bde1277a8b596a8083a9035e9ba2f",
    MIGRATION_DIR + "/0140_modeling_model-materialized.sql":
        "8cbd6230e8f68304b74f84e8250972ed605aae4168538c9305625beadd183a35",
    MIGRATION_DIR + "/0141_modeling_drop-legacy-publish.sql":
        "238748921ad537a0109d970114fa71296a5b63513387b11587b0e8a3a0090de4",
    MIGRATION_DIR + "/0142_modeling_logic-model-column.sql":
        "17378657b9c60ab10893b65fbd0d35ffb3a8e5187def871870de078a39e2f08f",
    MIGRATION_DIR + "/0143_modeling_drop-physical-menu.sql":
        "efa3e8911d43735be77c193f0aaa4f30e035867ff1aa6e4f144474226e364373",
}
LEGACY_ARTIFACT_SHA256 = {
    MIGRATION_DIR + "/0043_asset_market-delivery-apply-index.sql":
        "4ded2752bae962049911aecad22c54550b3fc5a29bc4b51973575406e4f38f73",
    MIGRATION_DIR + "/0015_modeling_biz-theme-remove-and-data-domain_rollback.sql":
        "2bc3fc55f75160a33459ca4cf8aa37e2ab65910f55c39414a9ef336fda01ef13",
    MIGRATION_DIR + "/0015_modeling_biz-theme-remove-and-data-domain.sql":
        "ee782f2b50eef2facfb7a906e8bb3ae43e91fe49ca83dc89753186eb0cbded72",
    MIGRATION_DIR + "/0137_modeling_logic-physical-biz-category.sql":
        ADOPTED_ARCHIVE_SHA256[MIGRATION_DIR + "/0137_modeling_logic-physical-biz-category.sql"],
}
FILENAME_RE = re.compile(
    r"^(?P<number>[0-9]{4})_(?P<module>[a-z][a-z0-9-]*)_"
    r"(?P<description>[a-z0-9][a-z0-9-]*)\.sql$"
)
DEPENDENCY_RE = re.compile(
    r"(?:(?:migrations|postgresql/migrations)/)?"
    r"(?P<filename>[0-9]{4}_[a-z][a-z0-9-]*_[a-z0-9][a-z0-9-]*\.sql)",
    re.IGNORECASE,
)
HEADER_RE = re.compile(
    r"^\s*--\s*(Migration|Purpose|Depends on|Target|Repeatable|Rollback)\s*:\s*(.*?)\s*$",
    re.IGNORECASE,
)
REQUIRED_HEADERS = (
    "migration",
    "purpose",
    "depends on",
    "target",
    "repeatable",
    "rollback",
)
BASELINE_MARKER_RE = re.compile(
    r"^\s*--\s*Included migrations through\s*:\s*(?P<number>[0-9]{4})\s*$",
    re.IGNORECASE | re.MULTILINE,
)


def current_migration_files(repo_root: Path) -> dict[str, bytes]:
    directory = repo_root / MIGRATION_DIR
    if not directory.exists():
        return {}
    if not directory.is_dir():
        raise GateConfigurationError(f"migration path is not a directory: {directory}")
    result: dict[str, bytes] = {}
    for path in sorted(directory.iterdir(), key=lambda item: item.name.lower()):
        if path.is_file() and path.suffix.lower() == ".sql":
            relative = normalize_path(path.relative_to(repo_root))
            result[relative] = pinned_checkout_bytes(repo_root, relative, path.read_bytes())
    return result


def is_migration_sql(path: str) -> bool:
    normalized = normalize_path(path)
    parent, _, filename = normalized.rpartition("/")
    return parent == MIGRATION_DIR and filename.lower().endswith(".sql")


def is_migration_or_baseline(path: str) -> bool:
    normalized = normalize_path(path)
    return is_migration_sql(normalized) or normalized in (BASELINE_PATH, CONTINUATION_PATH)


def leading_headers(text: str) -> tuple[dict[str, str], str]:
    headers: dict[str, str] = {}
    header_text: list[str] = []
    for line in text.splitlines()[:40]:
        stripped = line.strip()
        if not stripped:
            if headers:
                continue
            continue
        if not stripped.startswith("--"):
            break
        match = HEADER_RE.match(line)
        if match:
            headers[match.group(1).lower()] = match.group(2).strip()
        # Migration 可以使用完整文件名，它声明自身身份，不是前置依赖；其他说明仍按原规则检查。
        if not match or match.group(1).lower() != "migration":
            header_text.append(line)
    return headers, "\n".join(header_text)


def forward_migration_files(files: Mapping[str, bytes]) -> dict[str, bytes]:
    """Immutable historical packaging repair; never change bytes used by immutability checks.

    Only these exact, checksum-pinned historical artifacts are special. Unknown files,
    new rollbacks, or altered bytes still fail closed. Metadata here is for validation
    and planning only, never substituted into the SQL file sent to PostgreSQL.
    """
    result = dict(files)
    rollback = MIGRATION_DIR + "/0015_modeling_biz-theme-remove-and-data-domain_rollback.sql"
    forward = MIGRATION_DIR + "/0015_modeling_biz-theme-remove-and-data-domain.sql"
    for path, checksum in LEGACY_ARTIFACT_SHA256.items():
        if path in files and hashlib.sha256(files[path]).hexdigest() != checksum:
            raise GateConfigurationError(f"immutable legacy artifact checksum mismatch: {path}")
    result.pop(rollback, None)  # 仅供人工恢复使用的脚本，绝不能作为向前升级的迁移。
    if forward in result:
        result[forward] = (
            b"-- Depends on: 0013_modeling_legacy-data-adaptation.sql; 0014_governance_standard-quality-rule.sql\n"
            b"-- Repeatable: yes structurally; erased theme assignments cannot be restored by rerun\n"
            + result[forward]
        )
    # 0043 交付时的六项声明均为中文，必须完整保留其 SQL
    # 原始字节，仅补充校验元数据；新迁移仍须使用标准文件头。
    delivered = MIGRATION_DIR + "/0043_asset_market-delivery-apply-index.sql"
    if delivered in result:
        result[delivered] = (
            b"-- Migration: 0043\n"
            b"-- Purpose: Index the latest delivery operation per application\n"
            b"-- Depends on: 0042_asset_grant-source-delivery.sql\n"
            b"-- Target: Existing PostgreSQL with 0042; new schema mirrored in baseline\n"
            b"-- Repeatable: yes; additive CREATE INDEX IF NOT EXISTS\n"
            b'-- Rollback: DROP INDEX IF EXISTS "datahub-center".idx_market_delivery_apply_latest\n'
            + result[delivered]
        )
    delivered = MIGRATION_DIR + "/0137_modeling_logic-physical-biz-category.sql"
    if delivered in result:
        result[delivered] = (
            b"-- Migration: 0137\n"
            b"-- Purpose: Assign logical and physical models to business categories\n"
            b"-- Depends on: 0136_modeling_biz-category-data-domain-rel.sql\n"
            b"-- Target: Existing PostgreSQL with logical and physical model tables\n"
            b"-- Repeatable: yes; guarded column and index additions\n"
            b"-- Rollback: Drop the added biz_category_id columns and index after backing up assignments\n"
            + result[delivered]
        )
    return result


def analyze_migrations(
    files: Mapping[str, bytes],
    *,
    continued_dependencies: frozenset[tuple[str, str]] = frozenset(),
) -> list[Finding]:
    files = forward_migration_files(files)
    findings: list[Finding] = []
    by_number: dict[int, list[str]] = {}
    valid_files: dict[str, tuple[int, str, str]] = {}

    for path in sorted(files):
        filename = Path(path).name
        match = FILENAME_RE.fullmatch(filename)
        if not match:
            findings.append(
                Finding(
                    "MIGRATION_FILENAME",
                    path,
                    f"{path} must match NNNN_<module>_<short-description>.sql",
                    (path,),
                )
            )
            continue
        number = int(match.group("number"))
        if number == 0:
            findings.append(
                Finding(
                    "MIGRATION_NUMBER_ZERO",
                    path,
                    f"{path} uses reserved number 0000; production migrations start at 0001",
                    (path,),
                )
            )
        by_number.setdefault(number, []).append(path)
        valid_files[filename.lower()] = (number, path, filename)

        try:
            text = files[path].decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            findings.append(
                Finding(
                    "MIGRATION_UTF8",
                    path,
                    f"{path} is not valid UTF-8: {exc}",
                    (path,),
                )
            )
            continue

        headers, header_text = leading_headers(text)
        for required in REQUIRED_HEADERS:
            if not headers.get(required):
                findings.append(
                    Finding(
                        "MIGRATION_HEADER",
                        f"{path}:{required}",
                        f"{path} is missing a leading '-- {required.title()}: ...' header",
                        (path,),
                    )
                )
        migration_value = headers.get("migration", "")
        expected_prefix = f"{number:04d}"
        if migration_value and not migration_value.startswith(expected_prefix):
            findings.append(
                Finding(
                    "MIGRATION_HEADER_NUMBER",
                    path,
                    f"{path} header Migration value must start with {expected_prefix}",
                    (path,),
                )
            )

    for number, paths in sorted(by_number.items()):
        if len(paths) > 1:
            joined = ", ".join(sorted(paths))
            findings.append(
                Finding(
                    "MIGRATION_DUPLICATE_NUMBER",
                    f"{number:04d}:{'|'.join(sorted(paths))}",
                    f"migration number {number:04d} is used by: {joined}",
                    tuple(sorted(paths)),
                )
            )

    for path in sorted(files):
        filename = Path(path).name
        current_info = valid_files.get(filename.lower())
        if current_info is None:
            continue
        current_number = current_info[0]
        try:
            text = files[path].decode("utf-8-sig")
        except UnicodeDecodeError:
            continue
        _, header_text = leading_headers(text)
        for dependency in DEPENDENCY_RE.finditer(header_text):
            dependency_name = dependency.group("filename").lower()
            dependency_info = valid_files.get(dependency_name)
            if dependency_info is None:
                if (path, dependency.group("filename")) in continued_dependencies:
                    continue
                findings.append(
                    Finding(
                        "MIGRATION_DEPENDENCY_MISSING",
                        f"{path}:{dependency_name}",
                        f"{path} references missing numbered dependency {dependency_name}",
                        (path,),
                    )
                )
                continue
            dependency_number, dependency_path, _ = dependency_info
            if dependency_number >= current_number:
                findings.append(
                    Finding(
                        "MIGRATION_DEPENDENCY_ORDER",
                        f"{path}:{dependency_path}",
                        f"{path} depends on non-earlier migration {dependency_path}",
                        (path, dependency_path),
                    )
                )
    return findings


def immutability_findings(
    current: Mapping[str, bytes],
    baseline: Mapping[str, bytes],
    *,
    base_ref: str,
    repo_root: Path | None = None,
) -> list[Finding]:
    findings: list[Finding] = []
    for path, old_content in sorted(baseline.items()):
        new_content = current.get(path)
        if new_content is None:
            findings.append(
                Finding(
                    "MIGRATION_IMMUTABLE_DELETED",
                    path,
                    f"{path} exists at {base_ref} and must not be deleted or renamed",
                    (path,),
                )
            )
            continue
        if new_content != old_content and not git_line_endings_equivalent(
            repo_root, path, new_content, old_content,
        ):
            old_hash = hashlib.sha256(old_content).hexdigest()[:12]
            new_hash = hashlib.sha256(new_content).hexdigest()[:12]
            findings.append(
                Finding(
                    "MIGRATION_IMMUTABLE_CHANGED",
                    path,
                    f"{path} differs from {base_ref} (sha256 {old_hash} -> {new_hash}); add a later migration instead",
                    (path,),
                )
            )
    return findings


def git_line_endings_equivalent(
    repo_root: Path | None, path: str, current: bytes, baseline: bytes,
) -> bool:
    # 只允许纯 CRLF/LF 差异，且必须由该路径实际 Git 配置/属性认可。
    # 不统一清理空白、不忽略 BOM，也不允许 clean filter 掩盖 SQL 内容变化。
    # 输入和已交付原始 SHA 均保持不变；Git 仅计算对象号，不写对象或工作区。
    if repo_root is None or current.replace(b"\r\n", b"\n") != baseline.replace(b"\r\n", b"\n"):
        return False
    current_blob = run_git(
        repo_root, ["hash-object", f"--path={path}", "--stdin"], input_data=current,
    )
    baseline_blob = run_git(
        repo_root, ["hash-object", "--no-filters", "--stdin"], input_data=baseline,
    )
    return current_blob == baseline_blob


def allocation_findings(
    current: Mapping[str, bytes],
    baseline: Mapping[str, bytes],
    *,
    base_ref: str,
    historical_adoptions: frozenset[str] = frozenset(),
) -> list[Finding]:
    def numbered_paths(files: Mapping[str, bytes]) -> dict[str, int]:
        result: dict[str, int] = {}
        for path in files:
            match = FILENAME_RE.fullmatch(Path(path).name)
            if match:
                result[path] = int(match.group("number"))
        return result

    current_numbers = numbered_paths(current)
    baseline_numbers = numbered_paths(baseline)
    new_numbers = sorted(
        number for path, number in current_numbers.items()
        if path not in baseline and path not in historical_adoptions
    )
    if not new_numbers:
        return []
    base_max = max(baseline_numbers.values(), default=0)
    expected = list(range(base_max + 1, base_max + 1 + len(new_numbers)))
    if new_numbers == expected:
        return []
    actual_text = ", ".join(f"{number:04d}" for number in new_numbers)
    expected_text = ", ".join(f"{number:04d}" for number in expected)
    paths = tuple(
        sorted(path for path, number in current_numbers.items()
               if path not in baseline and path not in historical_adoptions)
    )
    return [
        Finding(
            "MIGRATION_ALLOCATION_ORDER",
            f"{base_ref}:{actual_text}:{expected_text}",
            f"new migrations relative to {base_ref} are {actual_text}; expected consecutive allocation {expected_text}",
            paths,
        )
    ]


def next_migration_number(files: Mapping[str, bytes]) -> int:
    numbers = [
        int(match.group("number"))
        for path in files
        if (match := FILENAME_RE.fullmatch(Path(path).name)) is not None
    ]
    next_number = max(numbers, default=0) + 1
    if next_number > 9999:
        raise GateConfigurationError(
            "four-digit migration sequence is exhausted at 9999; define a new versioning policy before adding SQL"
        )
    return next_number


def run_next(repo_root: Path, *, base_ref: str | None = None) -> int:
    current = current_migration_files(repo_root)
    continuation_content = read_optional(repo_root / CONTINUATION_PATH, repo_root=repo_root)
    findings, _ = analyze_snapshot(
        current, read_optional(repo_root / BASELINE_PATH),
        continuation_content,
    )
    findings.extend(continuation_history_findings(repo_root, continuation_content, base_ref=base_ref))
    if findings:
        for finding in sorted(findings, key=lambda item: item.fingerprint):
            print(f"ERROR {finding.rule}: {finding.message}")
        return 1
    print(f"{next_migration_number(forward_migration_files(current)):04d}")
    return 0


def baseline_marker_findings(
    migrations: Mapping[str, bytes],
    baseline_content: bytes | None,
) -> list[Finding]:
    numbered = [
        int(match.group("number"))
        for path in migrations
        if (match := FILENAME_RE.fullmatch(Path(path).name)) is not None
    ]
    if not numbered:
        return []
    expected = max(numbered)
    if baseline_content is None:
        return [
            Finding(
                "MIGRATION_BASELINE_MISSING",
                f"{BASELINE_PATH}:{expected:04d}",
                f"{BASELINE_PATH} is missing; it must include migrations through {expected:04d}",
                (BASELINE_PATH,),
            )
        ]
    try:
        baseline_text = baseline_content.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        return [
            Finding(
                "MIGRATION_BASELINE_UTF8",
                BASELINE_PATH,
                f"{BASELINE_PATH} is not valid UTF-8: {exc}",
                (BASELINE_PATH,),
            )
        ]
    markers = list(BASELINE_MARKER_RE.finditer(baseline_text))
    if not markers:
        return [
            Finding(
                "MIGRATION_BASELINE_MARKER_MISSING",
                f"{BASELINE_PATH}:{expected:04d}",
                f"{BASELINE_PATH} must declare '-- Included migrations through: {expected:04d}' near its beginning",
                (BASELINE_PATH,),
            )
        ]
    if len(markers) > 1:
        values = ", ".join(match.group("number") for match in markers)
        return [
            Finding(
                "MIGRATION_BASELINE_MARKER_DUPLICATE",
                f"{BASELINE_PATH}:{values}",
                f"{BASELINE_PATH} has multiple included-through markers: {values}",
                (BASELINE_PATH,),
            )
        ]
    if markers[0].start() >= 12000:
        return [Finding(
            "MIGRATION_BASELINE_MARKER_MISSING", BASELINE_PATH,
            f"{BASELINE_PATH} must declare its included-through marker near its beginning",
            (BASELINE_PATH,),
        )]
    actual = int(markers[0].group("number"))
    if actual != expected:
        return [
            Finding(
                "MIGRATION_BASELINE_MARKER_MISMATCH",
                f"{BASELINE_PATH}:{actual:04d}:{expected:04d}",
                f"{BASELINE_PATH} says {actual:04d}, but the highest migration is {expected:04d}",
                (BASELINE_PATH,),
            )
        ]
    return []


def pinned_checkout_bytes(repo_root: Path, path: str, content: bytes) -> bytes:
    """Recognize only Git-authorized checkout EOL changes for pinned artifacts."""
    checksums = {**LEGACY_ARTIFACT_SHA256, **ADOPTED_ARCHIVE_SHA256,
                 REPAIR_ANCHOR_PATH: REPAIR_ANCHOR_SHA256}
    accepted = ({RETIRED_CONTINUATION_SHA256, REPAIRED_CONTINUATION_SHA256}
                if path == CONTINUATION_PATH else {checksums.get(path)})
    normalized = content.replace(b"\r\n", b"\n")
    if (content != normalized and hashlib.sha256(normalized).hexdigest() in accepted
            and (repo_root / ".git").exists()
            and git_line_endings_equivalent(repo_root, path, content, normalized)):
        return normalized
    return content


def read_optional(path: Path, *, repo_root: Path | None = None) -> bytes | None:
    if not path.is_file():
        return None
    content = path.read_bytes()
    if repo_root is not None:
        return pinned_checkout_bytes(repo_root, normalize_path(path.relative_to(repo_root)), content)
    return content


def approved_continuation_repair(
    current_content: bytes | None, snapshot: Mapping[str, bytes],
    current_files: Mapping[str, bytes],
) -> bool:
    """Accept exactly the reviewed obsolete-declaration repair, once."""
    previous = snapshot.get(CONTINUATION_PATH, b"")
    if (hashlib.sha256(previous).hexdigest() != RETIRED_CONTINUATION_SHA256
            or hashlib.sha256(current_content or b"").hexdigest() != REPAIRED_CONTINUATION_SHA256
            or MIGRATION_DIR + "/0032_governance_quality-collect-issue-default.sql" in snapshot
            or hashlib.sha256(snapshot.get(REPAIR_ANCHOR_PATH, b"")).hexdigest()
            != REPAIR_ANCHOR_SHA256):
        return False
    return all(hashlib.sha256(current_files.get(path, b"")).hexdigest() == checksum
               for path, checksum in ADOPTED_ARCHIVE_SHA256.items())


def historical_archive_adoptions(
    repo_root: Path, current_content: bytes | None, snapshot: Mapping[str, bytes],
    current_files: Mapping[str, bytes],
) -> frozenset[str]:
    """仅恢复固定 Git 提交中的完整归档，不认可任意历史文件或重新编号。"""
    if (hashlib.sha256(current_content or b"").hexdigest() != REPAIRED_CONTINUATION_SHA256
            or snapshot.get(CONTINUATION_PATH) != current_content):
        return frozenset()
    numbers = [int(match["number"]) for path in snapshot if is_migration_sql(path)
               and (match := FILENAME_RE.fullmatch(Path(path).name))]
    if not numbers or min(numbers) <= ARCHIVE_RESTORATION_LAST:
        return frozenset()

    def in_archive(path: str) -> bool:
        match = FILENAME_RE.fullmatch(Path(path).name)
        return (is_migration_sql(path) and match is not None
                and ARCHIVE_RESTORATION_FIRST <= int(match["number"]) <= ARCHIVE_RESTORATION_LAST)

    if not any(in_archive(path) for path in current_files):
        return frozenset()
    originals = load_git_files(repo_root, ARCHIVE_RESTORATION_SOURCE_REF, in_archive)
    expected = set(range(ARCHIVE_RESTORATION_FIRST, ARCHIVE_RESTORATION_LAST + 1))
    if (len(originals) != len(expected)
            or {int(Path(path).name[:4]) for path in originals} != expected):
        raise GateConfigurationError("archive restoration source must contain the complete pinned range")
    for path, original in originals.items():
        restored = current_files.get(path)
        if restored is None:
            raise GateConfigurationError(f"immutable restored archive is missing: {path}")
        if restored != original and not git_line_endings_equivalent(repo_root, path, restored, original):
            raise GateConfigurationError(f"immutable restored archive differs from pinned Git source: {path}")
    return frozenset(originals)


def continuation_history_findings(
    repo_root: Path, content: bytes | None, *, base_ref: str | None,
) -> list[Finding]:
    # 声明采用后，规划过程不得接受重新固定校验值的归档或重写的来源信息；
    # 即使导出的测试夹具没有 Git 信息，也仍须执行结构和哈希校验。
    if not (repo_root / ".git").exists():
        return []
    ref = default_base_ref(base_ref)
    snapshot = load_git_files(repo_root, ref, is_migration_or_baseline)
    current = current_migration_files(repo_root)
    baseline = {path: data for path, data in snapshot.items() if is_migration_sql(path)}
    repair = approved_continuation_repair(content, snapshot, current)
    adoptions = (frozenset(ADOPTED_ARCHIVE_SHA256) if repair
                 else historical_archive_adoptions(repo_root, content, snapshot, current))
    findings = immutability_findings(current, baseline, base_ref=ref, repo_root=repo_root)
    findings.extend(allocation_findings(
        current, baseline, base_ref=ref,
        historical_adoptions=adoptions,
    ))
    if not repair:
        findings.extend(immutability_findings(
            {CONTINUATION_PATH: content} if content is not None else {},
            {CONTINUATION_PATH: snapshot[CONTINUATION_PATH]} if CONTINUATION_PATH in snapshot else {},
            base_ref=ref, repo_root=repo_root,
        ))
    return findings


def continuation_dependencies(
    content: bytes | None,
    files: Mapping[str, bytes],
    baseline_content: bytes | None,
) -> tuple[frozenset[tuple[str, str]], int | None]:
    """Validate a reviewable declaration; never infer coverage from smaller numbers.

    The cutoff describes a repository baseline, not an environment's applied state.
    Hashes bind original bytes before legacy header adaptation. The baseline itself
    may advance, while the explicitly declared historical edges remain unchanged.
    """
    if content is None:
        return frozenset(), None
    if hashlib.sha256(content).hexdigest() == REPAIRED_CONTINUATION_SHA256:
        for path, checksum in ADOPTED_ARCHIVE_SHA256.items():
            if hashlib.sha256(files.get(path, b"")).hexdigest() != checksum:
                raise GateConfigurationError(f"immutable adopted archive checksum mismatch: {path}")

    def object_keys(value, keys):
        if not isinstance(value, dict) or set(value) != set(keys):
            raise ValueError(f"expected fields: {', '.join(keys)}")

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    try:
        declaration = json.loads(content.decode("utf-8-sig"), object_pairs_hook=unique_object)
        object_keys(declaration, ("version", "baseline", "dependencies"))
        if type(declaration["version"]) is not int or declaration["version"] != 1:
            raise ValueError("version must be 1")
        baseline = declaration["baseline"]
        object_keys(baseline, ("path", "included_through", "source"))
        if baseline["path"] != BASELINE_PATH:
            raise ValueError(f"baseline path must be {BASELINE_PATH}")
        if not isinstance(baseline["source"], str) or not baseline["source"].strip():
            raise ValueError("baseline source must be a nonempty reviewable reference")
        cutoff_text = baseline["included_through"]
        if not isinstance(cutoff_text, str) or not re.fullmatch(r"[0-9]{4}", cutoff_text):
            raise ValueError("included_through must be four decimal digits")
        cutoff = int(cutoff_text)
        markers = list(BASELINE_MARKER_RE.finditer((baseline_content or b"").decode("utf-8-sig")))
        if cutoff == 0 or len(markers) != 1 or int(markers[0].group("number")) < cutoff:
            raise ValueError("current baseline must have one included-through marker at or after the cutoff")
        entries = declaration["dependencies"]
        if not isinstance(entries, list) or not entries:
            raise ValueError("dependencies must be a nonempty list of exact edges")
        edges: set[tuple[str, str]] = set()
        filenames = {Path(path).name.lower() for path in files}
        for entry in entries:
            object_keys(entry, ("dependent", "sha256", "missing_dependency"))
            if not all(isinstance(value, str) for value in entry.values()):
                raise ValueError("dependency fields must be strings")
            path, missing = entry["dependent"], entry["missing_dependency"]
            filename = path.removeprefix(MIGRATION_DIR + "/")
            dependent_match = FILENAME_RE.fullmatch(filename)
            missing_match = FILENAME_RE.fullmatch(missing)
            if path != MIGRATION_DIR + "/" + filename or not dependent_match or not missing_match:
                raise ValueError("dependency must bind an exact migration path and missing filename")
            if not 0 < int(missing_match["number"]) < int(dependent_match["number"]) <= cutoff:
                raise ValueError("dependency must be earlier than its dependent, both at or before the cutoff")
            if path not in files or missing.lower() in filenames:
                raise ValueError("dependent must exist and the declared dependency must be missing")
            checksum = entry["sha256"]
            if not re.fullmatch(r"[0-9a-f]{64}", checksum) or hashlib.sha256(files[path]).hexdigest() != checksum:
                raise ValueError(f"immutable SHA-256 mismatch: {path}")
            headers, _ = leading_headers(files[path].decode("utf-8-sig"))
            dependencies = {match["filename"] for match in DEPENDENCY_RE.finditer(headers.get("depends on", ""))}
            if missing not in dependencies:
                raise ValueError(f"declared dependency is absent from Depends on: {path}")
            edge = (path, missing)
            if edge in edges:
                raise ValueError("duplicate declared dependency edge")
            edges.add(edge)
        return frozenset(edges), cutoff
    except (ValueError, UnicodeError) as exc:
        raise GateConfigurationError(f"invalid baseline continuation ({CONTINUATION_PATH}): {exc}") from exc


def analyze_snapshot(
    files: Mapping[str, bytes], baseline_content: bytes | None, continuation_content: bytes | None,
) -> tuple[list[Finding], int | None]:
    marker_findings = baseline_marker_findings(files, baseline_content)
    if marker_findings:
        # 即使声明校验也依赖该错误信息，仍须报告原始基线错误。
        return analyze_migrations(files) + marker_findings, None
    edges, cutoff = continuation_dependencies(continuation_content, files, baseline_content)
    return analyze_migrations(files, continued_dependencies=edges), cutoff


def run_list_forward(
    repo_root: Path, after_baseline: str | None, *, base_ref: str | None = None,
) -> int:
    files = current_migration_files(repo_root)
    continuation_content = read_optional(repo_root / CONTINUATION_PATH, repo_root=repo_root)
    findings, cutoff = analyze_snapshot(
        files, read_optional(repo_root / BASELINE_PATH),
        continuation_content,
    )
    findings.extend(continuation_history_findings(repo_root, continuation_content, base_ref=base_ref))
    if findings:
        for finding in sorted(findings, key=lambda item: item.fingerprint):
            print(f"ERROR {finding.rule}: {finding.message}")
        return 1
    if cutoff is not None and after_baseline is None:
        raise GateConfigurationError(
            f"baseline continuation is present: use --list-forward --after-baseline {cutoff:04d} "
            "only after verifying the target database's baseline state; the retained archive is not a replayable chain"
        )
    start = 0
    if after_baseline is not None:
        if not re.fullmatch(r"[0-9]{4}", after_baseline):
            raise GateConfigurationError("--after-baseline must be a four-digit included-through number")
        start = int(after_baseline)
        highest = max((int(Path(path).name[:4]) for path in forward_migration_files(files)), default=0)
        if not (cutoff or 0) <= start <= highest:
            raise GateConfigurationError("--after-baseline must be between the continuation cutoff and current baseline")
    for path in sorted(forward_migration_files(files)):
        if int(Path(path).name[:4]) > start:
            print(path)
    return 0


def run_check(
    repo_root: Path,
    *,
    base_ref: str,
    strict: bool,
    show_debt: bool,
    check_immutability: bool,
) -> int:
    current = current_migration_files(repo_root)
    continuation_content = read_optional(repo_root / CONTINUATION_PATH, repo_root=repo_root)
    current_findings, cutoff = analyze_snapshot(
        current, read_optional(repo_root / BASELINE_PATH), continuation_content,
    )
    baseline_snapshot = load_git_files(repo_root, base_ref, is_migration_or_baseline)
    baseline: dict[str, bytes] = {
        path: content
        for path, content in baseline_snapshot.items()
        if is_migration_sql(path)
    }
    repair = approved_continuation_repair(continuation_content, baseline_snapshot, current)
    adoptions = (frozenset(ADOPTED_ARCHIVE_SHA256) if repair
                 else historical_archive_adoptions(repo_root, continuation_content, baseline_snapshot, current))
    baseline_findings: list[Finding] = []
    if not strict:
        if repair or adoptions:
            # 仅供比较的旧快照已缺失声明中的归档；仍分析其真实 SQL，当前完整依赖图和不可变检查不变。
            baseline_findings = analyze_migrations(baseline) + baseline_marker_findings(
                baseline, baseline_snapshot.get(BASELINE_PATH),
            )
        else:
            baseline_findings, _ = analyze_snapshot(
                baseline, baseline_snapshot.get(BASELINE_PATH), baseline_snapshot.get(CONTINUATION_PATH),
            )
    blocking, debt = classify_findings(
        current_findings,
        baseline_findings,
        strict=strict,
    )
    # 损坏的依赖图和基线漂移绝不能视为可容忍的历史遗留问题。
    # 延续使用的归档在每种模式下都必须通过全部结构检查。
    hard_debt = [finding for finding in debt if cutoff is not None
                 or finding.rule.startswith("MIGRATION_BASELINE_")
                 or finding.rule in {"MIGRATION_DEPENDENCY_MISSING", "MIGRATION_DEPENDENCY_ORDER",
                                     "MIGRATION_DUPLICATE_NUMBER"}]
    blocking.extend(hard_debt)
    debt = [finding for finding in debt if finding not in hard_debt]
    if check_immutability:
        blocking.extend(
            immutability_findings(current, baseline, base_ref=base_ref, repo_root=repo_root)
        )
        if not repair:
            blocking.extend(immutability_findings(
                {CONTINUATION_PATH: continuation_content} if continuation_content is not None else {},
                {CONTINUATION_PATH: baseline_snapshot[CONTINUATION_PATH]}
                if CONTINUATION_PATH in baseline_snapshot else {},
                base_ref=base_ref,
                repo_root=repo_root,
            ))
    blocking.extend(allocation_findings(
        current, baseline, base_ref=base_ref,
        historical_adoptions=adoptions,
    ))
    blocking = sorted(set(blocking), key=lambda item: item.fingerprint)
    numbered = sum(
        1 for path in current if FILENAME_RE.fullmatch(Path(path).name)
    )
    return print_report(
        "migrations",
        blocking,
        debt,
        summary=f"checked {len(current)} SQL file(s), {numbered} valid numbered filename(s), base={base_ref}",
        show_debt=show_debt,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate ordered PostgreSQL migrations and immutable history."
    )
    parser.add_argument("--root", help="yudao-cloud repository root; auto-detected by default")
    parser.add_argument(
        "--list-forward", action="store_true",
        help="validate and list only forward SQL paths; never glob migrations/*.sql",
    )
    parser.add_argument(
        "--next",
        action="store_true",
        help="validate existing migration structure and baseline marker, then print the next global four-digit number",
    )
    parser.add_argument(
        "--after-baseline",
        help="with --list-forward, list only migrations after this verified baseline number (NNNN)",
    )
    parser.add_argument(
        "--base-ref",
        help="Git ref used for historical-debt and immutability comparison (default: QUALITY_BASE_REF or HEAD)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="fail on every structural finding, including findings already present at the base ref",
    )
    parser.add_argument(
        "--show-debt",
        action="store_true",
        help="print non-blocking findings already present at the base ref",
    )
    parser.add_argument(
        "--no-immutability",
        action="store_true",
        help="skip comparison of tracked migration bytes (intended only for isolated fixture tests)",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.after_baseline is not None and not args.list_forward:
        parser.error("--after-baseline requires --list-forward")
    if args.next and args.list_forward:
        parser.error("--next and --list-forward cannot be combined")
    root = resolve_repo_root(args.root)
    if args.next:
        return run_next(root, base_ref=args.base_ref)
    if args.list_forward:
        return run_list_forward(root, args.after_baseline, base_ref=args.base_ref)
    return run_check(
        root,
        base_ref=default_base_ref(args.base_ref),
        strict=args.strict,
        show_debt=args.show_debt,
        check_immutability=not args.no_immutability,
    )


if __name__ == "__main__":
    cli_guard(main)
