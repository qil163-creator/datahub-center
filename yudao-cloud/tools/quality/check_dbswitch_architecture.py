"""Deterministic DB Switch artifact boundaries and product-manifest coverage."""
from __future__ import annotations

import json
import re
from pathlib import Path
from xml.etree import ElementTree

from common import EXIT_OK, EXIT_VIOLATIONS, cli_guard, resolve_repo_root

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
OPERATIONS = {
    "CONNECT", "METADATA", "DDL_CREATE", "DDL_ALTER", "READ", "WRITE_INSERT", "WRITE_COPY", "WRITE_UPSERT", "WRITE_ATOMIC_BATCH",
    "SYNC_FULL", "SYNC_DIFF", "SYNC_CURSOR",
}


def inspect(root: Path) -> list[str]:
    errors: list[str] = []
    component = root / "yudao-framework/yudao-dbswitch"
    if not component.is_dir():
        return ["DB Switch artifact root is missing"]
    graph: dict[str, set[str]] = {}
    for pom in component.rglob("pom.xml"):
        if "target" in pom.relative_to(component).parts:
            continue
        project = ElementTree.parse(pom).getroot()
        artifact = project.findtext("m:artifactId", namespaces=NS)
        assert artifact is not None
        deps = project.findall("m:dependencies/m:dependency", NS)
        own = {
            dep.findtext("m:artifactId", default="", namespaces=NS)
            for dep in deps
            if dep.findtext("m:scope", default="compile", namespaces=NS) != "test"
        }
        graph[artifact] = {name for name in own if name.startswith("yudao-dbswitch-")}
        for name in own:
            if name.startswith("yudao-module-") or "flink" in name or "calcite" in name or name == "orange":
                errors.append(f"{artifact}: forbidden dependency {name}")
        allowed = {
            "yudao-dbswitch-common": set(),
            "yudao-dbswitch-core": {"yudao-dbswitch-common"},
            "yudao-dbswitch-driver": {"yudao-dbswitch-common", "yudao-dbswitch-core"},
            "yudao-dbswitch-data": {"yudao-dbswitch-common", "yudao-dbswitch-core", "yudao-dbswitch-driver"},
        }.get(artifact)
        if allowed is not None and not graph[artifact] <= allowed:
            errors.append(f"{artifact}: invalid inward dependency {sorted(graph[artifact] - allowed)}")
        for source in (pom.parent / "src/main/java").rglob("*.java"):
            text = source.read_text(encoding="utf-8-sig")
            if re.search(r"import\s+cn\.iocoder\.yudao\.module\.", text):
                errors.append(f"{source.relative_to(root)}: imports a business implementation")

    def visit(name: str, stack: tuple[str, ...]) -> None:
        if name in stack:
            errors.append("DB Switch dependency cycle: " + " -> ".join((*stack, name)))
            return
        for dep in graph.get(name, set()):
            if dep not in graph:
                errors.append(f"{name}: dependency {dep} is absent from the reactor")
            else:
                visit(dep, (*stack, name))

    for name in graph:
        visit(name, ())

    providers: set[str] = set()
    manifest_products: set[str] = set()
    service_name = "cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductProvider"
    for service in component.rglob(service_name):
        if "target" in service.relative_to(component).parts:
            continue
        for line in service.read_text(encoding="utf-8").splitlines():
            provider = line.split("#", 1)[0].strip()
            if not provider:
                continue
            if provider in providers:
                errors.append(f"Duplicate product provider {provider}")
            providers.add(provider)
            module = service.parents[5]
            source = module / "src/main/java" / Path(*provider.split(".")).with_suffix(".java")
            if not source.is_file():
                errors.append(f"Provider has no source in its own artifact: {provider}")
    manifests = [path for path in component.rglob("META-INF/dbswitch/products/*.json")
                 if "target" not in path.relative_to(component).parts]
    manifest_providers: set[str] = set()
    product_manifests: dict[str, dict] = {}
    for path in manifests:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        product = manifest["product"]
        if product in manifest_products:
            errors.append(f"Duplicate product manifest {product}")
        manifest_products.add(product)
        product_manifests[product] = manifest
        manifest_providers.add(manifest["provider"])
        if set(manifest["operations"]) != OPERATIONS:
            errors.append(f"Incomplete operation matrix: {product}")
        for operation, capability in manifest["operations"].items():
            if capability["status"] not in {"DECLARED", "UNVERIFIED", "VERIFIED", "UNSUPPORTED"}:
                errors.append(f"Invalid capability status: {product}/{operation}")
            if capability["status"] == "VERIFIED":
                evidence = capability.get("evidence")
                label = f"{product}/{operation}"
                if not isinstance(evidence, dict):
                    errors.append(f"Verification without evidence: {label}")
                    continue
                if not manifest.get("serverVersion") or not re.fullmatch(r"[a-fA-F0-9]{64}", manifest.get("driverBundleFingerprint", "")):
                    errors.append(f"Verification lacks server version or immutable driver identity: {label}")
                report = evidence.get("report", "")
                report_path = (root / report).resolve() if isinstance(report, str) else root
                if not report or not report_path.is_relative_to(root.resolve()) or not report_path.is_file():
                    errors.append(f"Verification report missing or outside repository: {label}")
                    continue
                try:
                    suite = ElementTree.parse(report_path).getroot()
                    counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
                    if suite.tag != "testsuite" or suite.get("name") != evidence.get("testClass") or counts["tests"] <= 0 \
                            or any(counts[key] for key in ("failures", "errors", "skipped")) \
                            or evidence.get("executed") != counts["tests"] or evidence.get("skipped") != 0:
                        errors.append(f"Verification report does not prove a non-skipped successful execution: {label}")
                except (ElementTree.ParseError, ValueError, OSError):
                    errors.append(f"Verification report is invalid: {label}")
        cursor_targets = manifest.get("cursorTargets")
        if not isinstance(cursor_targets, list) or any(not isinstance(target, str) for target in cursor_targets):
            errors.append(f"Missing or invalid cursor direction list: {product}")
            continue
        if len(set(cursor_targets)) != len(cursor_targets):
            errors.append(f"Duplicate cursor direction: {product}")
        if cursor_targets and manifest["operations"].get("SYNC_CURSOR", {}).get("status") not in {"DECLARED", "VERIFIED"}:
            errors.append(f"Cursor directions without a declared source implementation: {product}")
        provider_source = path.parents[6] / "src/main/java" / Path(*manifest["provider"].split(".")).with_suffix(".java")
        if provider_source.is_file():
            declarations = re.findall(r"\.cursorTargets\s*\(([^)]*)\)", provider_source.read_text(encoding="utf-8-sig"))
            source_targets = {target for declaration in declarations
                              for target in re.findall(r"ProductTypeEnum\.([A-Z0-9_]+)", declaration)}
            if source_targets != set(cursor_targets):
                errors.append(f"Provider/manifest cursor directions differ: {product}")
    for product, manifest in product_manifests.items():
        cursor_targets = manifest.get("cursorTargets")
        if not isinstance(cursor_targets, list):
            continue
        for target in cursor_targets:
            if not isinstance(target, str):
                continue
            destination = product_manifests.get(target)
            if destination is None or destination["operations"].get("WRITE_UPSERT", {}).get("status") not in {"DECLARED", "VERIFIED"}:
                errors.append(f"Cursor direction lacks an implemented target UPSERT: {product}/{target}")
    if providers != manifest_providers:
        errors.append("Product manifests and ServiceLoader providers differ")
    if not providers:
        errors.append("No products registered")
    consumers = [*root.glob("yudao-module-*"),
                 root / "yudao-framework/yudao-spring-boot-starter-biz-etl",
                 root / "yudao-framework/yudao-spring-boot-starter-biz-quality"]
    bypass = re.compile(r"\b(?:DatabaseFactory|DatabaseOperatorFactory|DatabaseWriterFactory|DatabaseSynchronizeFactory)\s*\."
                        r"|\bnew\s+(?:[\w.]+\.)?(?:MetaDataByJdbcServiceImpl|MetaDataByDescriptionServiceImpl)\s*\(")
    for consumer in consumers:
        for source in consumer.rglob("*.java"):
            if "/src/main/java/" not in source.as_posix() or "target" in source.relative_to(root).parts:
                continue
            content = source.read_text(encoding="utf-8-sig")
            if bypass.search(content):
                errors.append(f"{source.relative_to(root)}: bypasses the context-owned DB Switch runtime")
    return sorted(set(errors))


def run_check(root: Path) -> int:
    errors = inspect(root)
    for error in errors:
        print("[dbswitch] " + error)
    print(f"[dbswitch] architecture and manifest checks: {len(errors)} violation(s)")
    return EXIT_VIOLATIONS if errors else EXIT_OK


if __name__ == "__main__":
    cli_guard(lambda: run_check(resolve_repo_root(None)))
