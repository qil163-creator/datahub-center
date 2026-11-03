from __future__ import annotations

import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence


EXIT_OK = 0
EXIT_VIOLATIONS = 1
EXIT_CONFIGURATION = 2


class GateConfigurationError(RuntimeError):
    """Raised when a quality gate cannot evaluate the requested repository."""


@dataclass(frozen=True, order=True)
class Finding:
    rule: str
    identity: str
    message: str
    paths: tuple[str, ...] = ()

    @property
    def fingerprint(self) -> str:
        return f"{self.rule}:{self.identity}"


def normalize_path(path: str | Path) -> str:
    return Path(path).as_posix().lstrip("./")


def resolve_repo_root(value: str | Path | None) -> Path:
    start = Path(value or os.getcwd()).resolve()
    candidates = (start, *start.parents)
    for candidate in candidates:
        if (candidate / "pom.xml").is_file() and any(
            child.is_dir() for child in candidate.glob("yudao-module-*")
        ):
            return candidate
    raise GateConfigurationError(
        f"not a yudao-cloud repository root (pom.xml plus yudao-module-* required): {start}"
    )


def read_utf8(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as exc:
        raise GateConfigurationError(f"file is not valid UTF-8: {path}: {exc}") from exc


def run_git(
    repo_root: Path, args: Sequence[str], *, text: bool = False,
    input_data: bytes | None = None,
) -> bytes | str:
    process = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        input=input_data,
    )
    if process.returncode != 0:
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise GateConfigurationError(
            f"git {' '.join(args)} failed with exit {process.returncode}: {detail}"
        )
    if text:
        return process.stdout.decode("utf-8", errors="strict")
    return process.stdout


def validate_git_ref(repo_root: Path, ref: str) -> None:
    run_git(repo_root, ["rev-parse", "--verify", f"{ref}^{{commit}}"])


def list_git_files(repo_root: Path, ref: str) -> list[str]:
    raw = run_git(repo_root, ["ls-tree", "-r", "--name-only", "-z", ref])
    assert isinstance(raw, bytes)
    return [item.decode("utf-8") for item in raw.split(b"\0") if item]


def load_git_files(
    repo_root: Path,
    ref: str,
    predicate: Callable[[str], bool],
) -> dict[str, bytes]:
    validate_git_ref(repo_root, ref)
    result: dict[str, bytes] = {}
    for path in list_git_files(repo_root, ref):
        if not predicate(path):
            continue
        raw = run_git(repo_root, ["show", f"{ref}:{path}"])
        assert isinstance(raw, bytes)
        result[normalize_path(path)] = raw
    return result


def default_base_ref(explicit: str | None) -> str:
    return explicit or os.environ.get("QUALITY_BASE_REF") or "HEAD"


def classify_findings(
    current: Iterable[Finding],
    baseline: Iterable[Finding],
    *,
    strict: bool,
) -> tuple[list[Finding], list[Finding]]:
    current_sorted = sorted(set(current), key=lambda item: item.fingerprint)
    if strict:
        return current_sorted, []
    baseline_fingerprints = {item.fingerprint for item in baseline}
    blocking = [
        finding
        for finding in current_sorted
        if finding.fingerprint not in baseline_fingerprints
    ]
    debt = [
        finding
        for finding in current_sorted
        if finding.fingerprint in baseline_fingerprints
    ]
    return blocking, debt


def print_report(
    title: str,
    blocking: Sequence[Finding],
    debt: Sequence[Finding],
    *,
    summary: str,
    show_debt: bool,
) -> int:
    print(f"[{title}] {summary}")
    for finding in blocking:
        print(f"ERROR {finding.rule}: {finding.message}")
    if debt:
        print(f"[{title}] historical debt accepted by base ref: {len(debt)} finding(s)")
        if show_debt:
            for finding in debt:
                print(f"DEBT  {finding.rule}: {finding.message}")
    if blocking:
        print(f"[{title}] FAILED: {len(blocking)} blocking finding(s)")
        return EXIT_VIOLATIONS
    print(f"[{title}] PASSED")
    return EXIT_OK


def cli_guard(main: Callable[[], int]) -> None:
    try:
        raise SystemExit(main())
    except GateConfigurationError as exc:
        print(f"CONFIGURATION ERROR: {exc}", file=sys.stderr)
        raise SystemExit(EXIT_CONFIGURATION) from exc
    except KeyboardInterrupt:
        print("INTERRUPTED", file=sys.stderr)
        raise SystemExit(130)


def decode_snapshot(files: Mapping[str, bytes]) -> dict[str, str]:
    result: dict[str, str] = {}
    for path, content in files.items():
        try:
            result[normalize_path(path)] = content.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise GateConfigurationError(
                f"file at git ref is not valid UTF-8: {path}: {exc}"
            ) from exc
    return result
