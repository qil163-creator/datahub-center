from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable, Mapping

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


MODULE_PATH_RE = re.compile(r"^yudao-module-(?P<owner>[a-z0-9-]+)/")
BUSINESS_ARTIFACT_RE = re.compile(
    r"^yudao-module-(?P<owner>[a-z0-9-]+)-(?P<kind>api|server)$"
)
PACKAGE_RE = re.compile(
    r"^\s*package\s+cn\.iocoder\.yudao\.module\.(?P<owner>[a-z0-9_]+)"
    r"(?:\.[a-zA-Z0-9_.]+)?;\s*$",
    re.MULTILINE,
)
IMPORT_RE = re.compile(
    r"^\s*import\s+(?:static\s+)?cn\.iocoder\.yudao\.module\."
    r"(?P<owner>[a-z0-9_]+)\.(?P<namespace>[a-zA-Z0-9_]+)(?:\.|;)",
    re.MULTILINE,
)
ALLOWED_CROSS_MODULE_NAMESPACES = frozenset({"api", "enums"})


def is_relevant_file(path: str) -> bool:
    normalized = normalize_path(path)
    if normalized.startswith("yudao-module-"):
        return normalized.endswith(".java") or normalized.endswith("pom.xml")
    if normalized.startswith("yudao-framework/"):
        return normalized.endswith(".java") or normalized.endswith("pom.xml")
    return False


def current_files(repo_root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    roots = [
        child
        for child in repo_root.iterdir()
        if child.is_dir()
        and (child.name.startswith("yudao-module-") or child.name == "yudao-framework")
    ]
    for source_root in sorted(roots):
        for path in source_root.rglob("*"):
            if not path.is_file():
                continue
            relative = normalize_path(path.relative_to(repo_root))
            if not is_relevant_file(relative):
                continue
            if any(part in {"target", "build"} for part in path.parts):
                continue
            try:
                result[relative] = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError as exc:
                raise GateConfigurationError(f"source is not valid UTF-8: {relative}: {exc}") from exc
    return result


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def direct_dependency_elements(project: ET.Element) -> Iterable[ET.Element]:
    for child in project:
        name = local_name(child.tag)
        if name == "dependencies":
            yield from (item for item in child if local_name(item.tag) == "dependency")
        elif name == "profiles":
            for profile in child:
                if local_name(profile.tag) != "profile":
                    continue
                for profile_child in profile:
                    if local_name(profile_child.tag) == "dependencies":
                        yield from (
                            item
                            for item in profile_child
                            if local_name(item.tag) == "dependency"
                        )


def child_text(element: ET.Element, name: str) -> str | None:
    for child in element:
        if local_name(child.tag) == name:
            return (child.text or "").strip() or None
    return None


def line_for_text(text: str, needle: str) -> int:
    index = text.find(needle)
    return text.count("\n", 0, max(index, 0)) + 1


def analyze_module_boundaries(files: Mapping[str, str]) -> list[Finding]:
    findings: list[Finding] = []
    for path, text in sorted(files.items()):
        if path.endswith("pom.xml"):
            try:
                project = ET.fromstring(text)
            except ET.ParseError as exc:
                findings.append(
                    Finding(
                        "MODULE_POM_XML",
                        path,
                        f"{path} is not parseable XML: {exc}",
                        (path,),
                    )
                )
                continue
            module_match = MODULE_PATH_RE.match(path)
            consumer = module_match.group("owner") if module_match else None
            is_framework = path.startswith("yudao-framework/")
            for dependency in direct_dependency_elements(project):
                artifact_id = child_text(dependency, "artifactId")
                if not artifact_id or artifact_id.startswith("${"):
                    continue
                business_match = BUSINESS_ARTIFACT_RE.fullmatch(artifact_id)
                if not business_match:
                    continue
                provider = business_match.group("owner")
                kind = business_match.group("kind")
                location = f"{path}:{line_for_text(text, f'<artifactId>{artifact_id}</artifactId>')}"
                if is_framework:
                    findings.append(
                        Finding(
                            "FRAMEWORK_BUSINESS_DEPENDENCY",
                            f"{path}:{artifact_id}",
                            f"{location} framework must not depend on business artifact {artifact_id}",
                            (path,),
                        )
                    )
                elif consumer and kind == "server":
                    findings.append(
                        Finding(
                            "MODULE_SERVER_DEPENDENCY",
                            f"{path}:{artifact_id}",
                            f"{location} module {consumer} must use {provider}'s *-api, never {artifact_id}",
                            (path,),
                        )
                    )
            continue

        if not path.endswith(".java"):
            continue
        module_match = MODULE_PATH_RE.match(path)
        if path.startswith("yudao-framework/"):
            for match in IMPORT_RE.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(
                    Finding(
                        "FRAMEWORK_BUSINESS_IMPORT",
                        f"{path}:{match.group(0).strip()}",
                        f"{path}:{line} framework source imports a business-module type",
                        (path,),
                    )
                )
            continue
        if module_match is None:
            continue
        owner = module_match.group("owner").replace("-", "_")
        package_match = PACKAGE_RE.search(text)
        package_owner = package_match.group("owner") if package_match else None
        # 少量兼容适配类有意放在第三方包中。
        # 仅当源码声明使用 Yudao 业务包时才能校验模块归属，
        # 不能仅凭外部包名推断存在违规依赖。
        if package_match and package_owner != owner:
            line = text.count("\n", 0, package_match.start()) + 1
            findings.append(
                Finding(
                    "MODULE_PACKAGE_OWNER",
                    f"{path}:{package_owner or '<missing>'}",
                    f"{path}:{line} belongs to module {owner} but package owner is {package_owner or '<missing>'}",
                    (path,),
                )
            )
        for match in IMPORT_RE.finditer(text):
            provider = match.group("owner")
            namespace = match.group("namespace")
            if provider == owner or namespace in ALLOWED_CROSS_MODULE_NAMESPACES:
                continue
            line = text.count("\n", 0, match.start()) + 1
            findings.append(
                Finding(
                    "MODULE_INTERNAL_IMPORT",
                    f"{path}:{match.group(0).strip()}",
                    f"{path}:{line} module {owner} imports internal namespace {provider}.{namespace}; use the provider *-api contract",
                    (path,),
                )
            )
    return findings


def run_check(
    repo_root: Path,
    *,
    base_ref: str,
    strict: bool,
    show_debt: bool,
) -> int:
    snapshot = current_files(repo_root)
    current_findings = analyze_module_boundaries(snapshot)
    baseline_findings: list[Finding] = []
    if not strict:
        # 仅加载当前发现问题涉及的文件，就足以区分
        # 既有问题和新增违规，同时避免每次运行都从 Git 历史中
        # 读取全部 Java 文件。
        candidate_paths = {path for finding in current_findings for path in finding.paths}
        baseline_bytes = load_git_files(
            repo_root,
            base_ref,
            lambda path: normalize_path(path) in candidate_paths,
        )
        baseline_findings = analyze_module_boundaries(decode_snapshot(baseline_bytes))
    blocking, debt = classify_findings(
        current_findings,
        baseline_findings,
        strict=strict,
    )
    java_count = sum(path.endswith(".java") for path in snapshot)
    pom_count = sum(path.endswith("pom.xml") for path in snapshot)
    return print_report(
        "module-boundaries",
        blocking,
        debt,
        summary=f"checked {pom_count} POM(s) and {java_count} Java source file(s), base={base_ref}",
        show_debt=show_debt,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Enforce framework/business and cross-module API/server boundaries."
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
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return run_check(
        resolve_repo_root(args.root),
        base_ref=default_base_ref(args.base_ref),
        strict=args.strict,
        show_debt=args.show_debt,
    )


if __name__ == "__main__":
    cli_guard(main)
