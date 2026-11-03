"""验证历史 0128 的首次统一前测试数据清空；只克隆显式隔离模板，不修改源库。

引用控制表夹具属于该历史阶段，不适用于已移除引用控制的当前新库基线。
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import uuid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--source-db", required=True)
    parser.add_argument("--user", default="postgres")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.source_db):
        parser.error("源测试库名须为普通 PostgreSQL 标识符")
    repo = next(p for p in Path(__file__).resolve().parents if (p / "pom.xml").exists() and (p / "sql").exists())
    migrations = repo / "sql/postgresql/migrations"
    reset = migrations / "0128_modeling_clear-unused-before-unification.sql"
    prefix = f"modeling_unused_{uuid.uuid4().hex[:10]}"
    connection = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    clones: list[str] = []
    passed = 0
    declaration = "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';"

    def run(call: list[str], expected: str | None = None) -> str:
        result = subprocess.run(call, text=True, encoding="utf-8", capture_output=True)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None and (result.returncode == 0 or expected not in result.stderr):
            raise RuntimeError("未按预期拒绝：" + result.stderr)
        return result.stdout.strip()

    def sql(db: str, command: str | None = None, file: Path | None = None, expected: str | None = None) -> str:
        call = ["psql", *connection, "-d", db, "-X", "-v", "ON_ERROR_STOP=1", "-Atq"]
        if command:
            call += ["-c", command]
        if file:
            call += ["-f", str(file)]
        return run(call, expected)

    def clone(suffix: str) -> str:
        db = prefix + "_" + suffix
        run(["createdb", *connection, "-T", args.source_db, db])
        clones.append(db)
        return db

    def ok(name: str) -> None:
        nonlocal passed
        passed += 1
        print("PASS " + name, flush=True)

    def digest(db: str, table: str) -> str:
        return sql(db, f'SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,\'[]\')) FROM "datahub-center".{table} t;')

    db = clone("positive")
    assert sql(db, 'SELECT count(*) FROM "datahub-center".dh_modeling_physical_model;') == "3", "模板须为明确的旧演示基线"
    before = digest(db, "dh_modeling_physical_model")
    sql(db, file=reset)
    assert digest(db, "dh_modeling_physical_model") == before
    ok("不开启时不清空演示模型")

    sql(db, 'INSERT INTO "datahub-center".dh_dataforge_reference_mutation(tenant_id,operation_id,owner,state,revision,purpose,request_hash) VALUES(1,\'unused-modeling\',\'modeling\',\'ACTIVE\',1,\'reset-test\',repeat(\'a\',64)),(1,\'keep-dataforge\',\'dataforge\',\'ACTIVE\',1,\'reset-test\',repeat(\'b\',64));')
    preserved_tables = ["dh_modeling_concept_model", "dh_modeling_concept_entity", "dh_modeling_concept_model_version", "dh_modeling_dw_layer", "dh_modeling_biz_category", "dh_modeling_business_process", "dh_modeling_data_domain", "dh_modeling_subject_domain", "dh_modeling_theme_plan", "dh_modeling_model_check_config", "dh_dataforge_reference_gate", "dh_dataforge_reference_audit", "dh_governance_standard_mapping", "dh_governance_lineage_event_outbox"]
    preserved = {t: digest(db, t) for t in preserved_tables}
    sequences = sql(db, "SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='datahub-center' AND sequencename LIKE 'dh_modeling_%' ORDER BY sequencename;")
    sql(db, command=declaration, file=reset)
    body = reset.read_text(encoding="utf-8")
    tables = re.findall(r"'([a-z_]+)'", body.split("model_tables text[] := ARRAY[", 1)[1].split("];", 1)[0])
    assert len(tables) == 19
    for table in tables:
        assert sql(db, f'SELECT count(*) FROM "datahub-center".{table};') == "0", table
    assert sequences == sql(db, "SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='datahub-center' AND sequencename LIKE 'dh_modeling_%' ORDER BY sequencename;")
    assert preserved == {t: digest(db, t) for t in preserved_tables}
    assert sql(db, 'SELECT owner FROM "datahub-center".dh_dataforge_reference_mutation;') == "dataforge"
    ok("十九表清空且规划概念共享记录序列与其他领域事务保留")
    sql(db, command=declaration, file=reset)
    assert preserved == {t: digest(db, t) for t in preserved_tables}
    ok("统一前重放稳定")

    sql(db, file=migrations / "0123_modeling_unified-definition.sql")
    sql(db, command=declaration, file=reset, expected="统一准备或后续阶段已成功")
    ok("准备已完成时拒绝显式清空")
    cutover = "SET datahub.modeling_unified_cutover='UNIFIED_MODEL_V1';"
    for filename in ["0124_modeling_unified-target-cleanup.sql", "0125_modeling_unified-menu.sql", "0126_modeling_remove-workspace.sql", "0127_modeling_remove-business-process-object.sql"]:
        sql(db, command=cutover, file=migrations / filename)
    sql(db, file=reset)
    sql(db, command=declaration, file=reset, expected="统一准备或后续阶段已成功")
    ok("清空后首次升级全链完成且正常末尾不再次清空")

    unknown = clone("foreign_key")
    before = digest(unknown, "dh_modeling_physical_model")
    sql(unknown, 'CREATE TABLE public.reset_dependency(id bigint PRIMARY KEY, physical_id bigint REFERENCES "datahub-center".dh_modeling_physical_model(id)); INSERT INTO public.reset_dependency VALUES(1,1);')
    sql(unknown, command=declaration, file=reset, expected="foreign key constraint")
    assert digest(unknown, "dh_modeling_physical_model") == before
    ok("未知外键拒绝并整笔回滚")

    queued = clone("queued_lineage")
    before = digest(queued, "dh_modeling_physical_model")
    sql(queued, 'INSERT INTO "datahub-center".dh_governance_lineage_event_outbox(event_id,payload,tenant_id,status) VALUES(\'unused-model-lineage\',\'{"taskType":"MODEL_DEPLOY","taskId":1}\'::jsonb,1,0);')
    sql(queued, command=declaration, file=reset, expected="未决血缘事件")
    assert digest(queued, "dh_modeling_physical_model") == before
    assert sql(queued, 'SELECT count(*) FROM "datahub-center".dh_governance_lineage_event_outbox WHERE event_id=\'unused-model-lineage\';') == "1"
    ok("共享未决投递拒绝清空且保留事件原行")

    scoped = clone("unrelated_lineage")
    sql(scoped, 'INSERT INTO "datahub-center".dh_governance_lineage_event_outbox(event_id,payload,tenant_id,status) VALUES(\'other-tenant\',\'{"taskType":"MODEL_DEPLOY","taskId":1}\'::jsonb,2,0),(\'other-task\',\'{"taskType":"ETL","taskId":1}\'::jsonb,1,0),(\'applied-model\',\'{"taskType":"MODEL_DEPLOY","taskId":1}\'::jsonb,1,2);')
    retained = digest(scoped, "dh_governance_lineage_event_outbox")
    sql(scoped, command=declaration, file=reset)
    assert retained == digest(scoped, "dh_governance_lineage_event_outbox")
    ok("其他租户其他任务与成功血缘保留且不误阻断")

    evidence = clone("mapping_evidence")
    before = digest(evidence, "dh_modeling_physical_model")
    sql(evidence, 'CREATE TABLE "datahub-center".dh_modeling_unification_map(id bigint); INSERT INTO "datahub-center".dh_modeling_unification_map VALUES(1);')
    sql(evidence, command=declaration, file=reset, expected="已存在统一映射证据")
    assert digest(evidence, "dh_modeling_physical_model") == before
    ok("存在映射证据时拒绝清空")
    print(f"PASS {passed} 检查组；仅克隆测试库，保留供复核：" + ", ".join(clones))


if __name__ == "__main__":
    main()
