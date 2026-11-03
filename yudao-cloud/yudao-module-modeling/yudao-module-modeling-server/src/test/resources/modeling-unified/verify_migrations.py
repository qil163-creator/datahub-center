"""仅对显式指定的隔离 PostgreSQL 实例运行；创建临时克隆，不删除或修改源库。"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--source-db", required=True, help="已导入改造前完整基线的专用测试库")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--baseline-db", help="可选：已单独导入最新完整基线的隔离新库，只读检查最终状态")
    args = parser.parse_args()
    if args.baseline_db and not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.baseline_db):
        parser.error("新库名须为普通 PostgreSQL 标识符")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.source_db):
        parser.error("源测试库名须为普通 PostgreSQL 标识符")
    here = Path(__file__).resolve().parent
    repo = next(p for p in here.parents if (p / "pom.xml").is_file() and (p / "sql/postgresql/migrations").is_dir())
    migrations = repo / "sql/postgresql/migrations"
    prepare = migrations / "0123_modeling_unified-definition.sql"
    cutover = migrations / "0124_modeling_unified-target-cleanup.sql"
    prefix = "modeling_unified_" + uuid.uuid4().hex[:10]
    env = dict(os.environ, PGCLIENTENCODING="UTF8")
    prefix_args = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    checks = 0

    def run(command: list[str], expected: str | None = None) -> str:
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", env=env)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None and (result.returncode == 0 or expected not in result.stderr):
            raise RuntimeError("未按预期拒绝迁移: " + result.stderr)
        return result.stdout

    def sql(db: str, *, file: Path | None = None, command: str | None = None, expected: str | None = None) -> str:
        call = ["psql", *prefix_args, "-d", db, "-X", "-v", "ON_ERROR_STOP=1", "-q"]
        if command is not None:
            call += ["-c", command]
        if file is not None:
            call += ["-f", str(file)]
        return run(call, expected)

    def clone(suffix: str) -> str:
        db = prefix + "_" + suffix
        run(["createdb", *prefix_args, "-T", args.source_db, db])
        return db

    def passed(name: str) -> None:
        nonlocal checks
        checks += 1
        print("PASS " + name, flush=True)

    # 本夹具绑定于改造前仓库的明确演示数据，拒绝对任意业务库按固定编号写入。
    raw = clone("raw")
    row = sql(raw, command='SELECT count(*) FROM "datahub-center".dh_modeling_physical_model;')
    if not re.search(r"\b3\b", row):
        raise RuntimeError("源库不是包含 3 个建模演示物理模型的改造前测试基线")
    sql(raw, file=prepare, expected="物理模型引用的逻辑模型不存在、跨租户或已删除")
    passed("raw_baseline_invalid_link_rejected")

    positive = clone("positive")
    sql(positive, file=here / "setup-valid-fixture.sql")
    sql(positive, file=prepare)
    sql(positive, file=prepare)
    passed("prepare_and_replay")
    sql(positive, file=cutover, expected="未声明已验收统一模型新版")
    passed("cutover_requires_release_declaration")
    declaration = "SET datahub.modeling_unified_cutover='UNIFIED_MODEL_V1';"
    sql(positive, command=declaration, file=cutover, expected="存在未映射或未审查")
    passed("cutover_requires_actual_review")
    sql(positive, file=here / "review-and-acl-assertions.sql")
    passed("acl_equivalence_and_single_draft")
    sql(positive, command=declaration, file=cutover)
    sql(positive, file=cutover)
    passed("cutover_and_replay")
    sql(positive, file=here / "post-cutover-assertions.sql")
    passed("history_tenant_identity_and_evidence_assertions")

    gate_db = clone("gates")
    sql(gate_db, command='UPDATE "datahub-center".dh_modeling_physical_model SET logic_model_id=3 WHERE id=1;')
    # 负向用例包在外层事务中，仍执行完整生产迁移内容；只移除事务边界使测试修改一起回滚。
    body = prepare.read_text(encoding="utf-8").replace("BEGIN;\nSET LOCAL search_path", "SET LOCAL search_path", 1)
    body = body.rsplit("COMMIT;", 1)[0]
    cases = [
        ("running_check", "UPDATE dh_modeling_model_check SET task_status='RUNNING' WHERE id=1;", "仍有排队"),
        ("pending_delivery", "UPDATE dh_modeling_deploy_record SET metadata_status='PENDING' WHERE id=1;", "元数据、血缘或基线登记仍未完成"),
        ("deleted_latest_version", "UPDATE dh_modeling_physical_model_version SET deleted=1 WHERE id=2;", "最新版本指针不存在或只指向已删除版本"),
        ("duplicate_name", "UPDATE dh_modeling_physical_model SET name='学生' WHERE id=2;", "与已有统一设计同名或同编码"),
        ("cross_tenant_source", "INSERT INTO dh_modeling_logic_model(id,name,tenant_id) VALUES(99999,'隔离租户模型',2); UPDATE dh_modeling_physical_model SET logic_model_id=99999 WHERE id=1;", "物理模型引用的逻辑模型不存在、跨租户或已删除"),
    ]
    with tempfile.TemporaryDirectory(prefix="modeling-unified-verify-") as temp:
        fixture = Path(temp) / "negative.sql"
        for name, mutation, expected in cases:
            fixture.write_text('BEGIN;\nSET LOCAL search_path="datahub-center",pg_catalog;\n' + mutation + "\n" + body + "\nROLLBACK;\n", encoding="utf-8")
            sql(gate_db, file=fixture, expected=expected)
            passed(name)
    # 历史 ACL/审查负例必须先验证；0126 后仅重放当前退役脚本，禁止旧链重新查询已删表。
    sql(positive, command=declaration, file=migrations / "0125_modeling_unified-menu.sql")
    passed("menu_cutover_0125")
    evidence_query = 'SELECT md5(jsonb_agg(to_jsonb(m) ORDER BY physical_model_id)::text) FROM "datahub-center".dh_modeling_unification_map m;'
    evidence = sql(positive, command=evidence_query)
    removal = migrations / "0126_modeling_remove-workspace.sql"
    sql(positive, file=removal)
    sql(positive, file=here / "post-workspace-removal-assertions.sql")
    assert evidence == sql(positive, command=evidence_query), "退役后统一迁移原始证据变化"
    sql(positive, file=removal)
    sql(positive, file=here / "post-workspace-removal-assertions.sql")
    passed("workspace_removal_0126_and_replay_preserve_mapping_evidence")
    if args.baseline_db:
        sql(args.baseline_db, file=here / "post-workspace-removal-assertions.sql")
        passed("fresh_baseline_final_schema_0126")
    print(f"PASS {checks} check groups. Disposable clones retained: {raw}, {positive}, {gate_db}")


if __name__ == "__main__":
    main()
