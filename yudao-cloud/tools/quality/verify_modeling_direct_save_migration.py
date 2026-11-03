"""在已明确标记的本机临时 PostgreSQL 集群验证 0236；仅创建并操作本次唯一测试数据库。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sql/postgresql/migrations/0236_modeling_remove-model-versions.sql"
BASELINE = ROOT / "sql/postgresql/datahub-center.sql"
FIXTURE = ROOT / "yudao-module-modeling/yudao-module-modeling-server/src/test/resources/sql/migration/modeling-0236-before.sql"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--isolated-info", type=Path, required=True, help="本次临时集群的身份文件，必须含 root/host/port/user")
    parser.add_argument("--postgres-bin", type=Path, required=True)
    args = parser.parse_args()
    info = json.loads(args.isolated_info.read_text(encoding="utf-8-sig"))
    cluster_root = Path(info["root"]).resolve()
    temporary_root = Path(tempfile.gettempdir()).resolve()
    if not cluster_root.is_relative_to(temporary_root) or info["host"] != "127.0.0.1":
        raise SystemExit("只允许当前机器临时目录内的隔离集群")
    suffix = ".exe" if os.name == "nt" else ""
    binary = args.postgres_bin / ("psql" + suffix)
    environment = dict(os.environ, PGCLIENTENCODING="UTF8", PGAPPNAME="codex-0236-isolated-verification")
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    base = [str(binary), "-X", "-qAt", "-h", info["host"], "-p", str(info["port"]), "-U", info["user"],
            "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose"]
    token = uuid.uuid4().hex[:10]
    work = temporary_root / ("codex-modeling-0236-" + token)
    work.mkdir()
    names = {key: f"codex_0236_{token}_{key}" for key in ("legacy", "upgrade", "fresh", "orphan", "missing", "conflict")}
    count = 0

    def sql(statement: str, database: str = "postgres", required: bool = True):
        nonlocal count
        if database != "postgres" and database not in names.values():
            raise AssertionError("禁止连接本次测试以外的数据库")
        result = subprocess.run(base + ["-d", database], input=statement, text=True, encoding="utf-8",
                                errors="replace", capture_output=True, env=environment, timeout=240)
        count += 1
        (work / f"{count:03d}-{database}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        if required and result.returncode:
            raise AssertionError(f"SQL失败，日志 {work}：\n{result.stderr[-6000:]}")
        return result

    def check(statement: str, database: str = "upgrade"):
        result = sql('SET search_path="datahub-center",pg_catalog;\nSELECT (' + statement + ")::text;", names[database])
        assert result.stdout.strip() == "true", (statement, result.stdout)

    def reject(statement: str, code: str = "23514", database: str = "upgrade"):
        result = sql('SET search_path="datahub-center",pg_catalog;\n' + statement, names[database], False)
        assert result.returncode and code in result.stderr, result.stderr

    actual_data = Path(sql("SHOW data_directory").stdout.strip()).resolve()
    if not actual_data.is_relative_to(cluster_root):
        raise SystemExit("服务器实际数据目录与临时集群身份不一致，拒绝执行")
    migration = MIGRATION.read_text(encoding="utf-8-sig")
    baseline = BASELINE.read_text(encoding="utf-8-sig")
    marker = migration.splitlines()[0]
    assert baseline.count(marker) == 1
    legacy = baseline.split(marker, 1)[0]
    assert baseline[baseline.index(marker):].strip() == migration.strip()
    assert baseline.count("-- Included migrations through: 0236") == 1
    sql(f"CREATE DATABASE {names['legacy']};")
    sql(legacy, names["legacy"])
    for key in ("upgrade", "orphan", "missing", "conflict"):
        sql(f"CREATE DATABASE {names[key]} TEMPLATE {names['legacy']};")
        sql(FIXTURE.read_text(encoding="utf-8-sig"), names[key])

    # 不完整数据必须整笔回滚，不能删除唯一来源证据或猜测任务模型。
    sql('SET search_path="datahub-center",pg_catalog; DELETE FROM dh_modeling_model_materialized WHERE id=-236001;', names["orphan"])
    reject(migration, "P0001", "orphan")
    check("to_regclass('dh_modeling_logic_model_version') IS NOT NULL AND NOT EXISTS(SELECT 1 FROM information_schema.columns "
          "WHERE table_schema='datahub-center' AND table_name='dh_modeling_logic_model' AND column_name='type_config')", "orphan")
    sql('SET search_path="datahub-center",pg_catalog; UPDATE dh_modeling_model_materialized SET version_id=-999999 WHERE id=-236002;', names["missing"])
    reject(migration, "P0001", "missing")
    sql('SET search_path="datahub-center",pg_catalog; UPDATE dh_modeling_deployment_attempt '
        "SET model_snapshot_json=model_snapshot_json||'{\"id\":-999999}' WHERE id=-236001;", names["conflict"])
    reject(migration, "P0001", "conflict")
    sql(migration, names["upgrade"])
    check("NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='datahub-center' AND "
          "((table_name='dh_modeling_logic_model' AND column_name='latest_version') OR "
          "(table_name IN ('dh_modeling_indicator_model','dh_modeling_business_condition') AND column_name='source_version_id') OR "
          "(table_name IN ('dh_modeling_deployment_attempt','dh_modeling_model_materialized') AND column_name='version_id'))) "
          "AND to_regclass('dh_modeling_logic_model_version') IS NULL")
    check("(SELECT type_config=' {\"dimension\":{\"fixture\":\"current\"}}'::jsonb AND published_by=777 "
          "AND published_time=timestamp '2026-01-02 03:04:05' FROM dh_modeling_logic_model m "
          "JOIN public.verify_modeling_0236 c ON c.model_id=m.id)")
    check("(SELECT type_config='{\"deleted\":\"preserved\"}'::jsonb AND status='DRAFT' "
          "FROM dh_modeling_logic_model WHERE id=-236001)")
    check("(SELECT to_jsonb(f)=c.field_before FROM dh_modeling_logic_model_column f "
          "JOIN public.verify_modeling_0236 c ON c.model_id=f.model_id AND c.field_uid=f.field_uid WHERE f.deleted=0)")
    check("NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model_column f JOIN public.verify_modeling_0236 c "
          "ON c.model_id=f.model_id AND c.missing_uid=f.field_uid WHERE f.deleted=0)")
    check("(SELECT (to_jsonb(a)-ARRAY['version_id','model_id','design_revision','model_snapshot_json'])="
          "(c.original_attempt-ARRAY['version_id','model_snapshot_json']) AND a.model_id=c.model_id "
          "AND a.design_revision IS NULL AND a.model_snapshot_json="
          "(((c.original_attempt->'model_snapshot_json')-'modelId')||jsonb_build_object('id',c.model_id)) "
          "FROM dh_modeling_deployment_attempt a CROSS JOIN public.verify_modeling_0236 c WHERE a.id=-236001)")
    check("(SELECT m.fields_json=a.fields_json AND m.design_revision IS NULL FROM dh_modeling_model_materialized m "
          "JOIN dh_modeling_deployment_attempt a ON a.id=m.attempt_id WHERE m.id=-236001)")
    check("(SELECT fields_json IS NOT NULL AND design_revision IS NULL AND attempt_id IS NULL "
          "AND creator='fixture-original' AND create_time=timestamp '2026-01-03 04:05:06' "
          "FROM dh_modeling_model_materialized WHERE id=-236002)")
    check("NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
          "WHERE n.nspname='datahub-center' AND p.prokind IN ('f','p') "
          r"AND p.prosrc ~ '\m(dh_modeling_logic_model_version|source_version_id|latest_version)\M')")

    # 当前发布来源可绑定；错误稳定标识、实体及跨归属引用都被数据库守卫拒绝。
    reject("UPDATE dh_modeling_indicator_model SET value_field_uid='missing-uid' WHERE id=-236001;")
    reject("UPDATE dh_modeling_indicator_model SET entity_id=-999999 WHERE id=-236001;")
    reject("UPDATE dh_modeling_business_condition SET data_freshness='invalid' WHERE id=-236001;")
    reject("UPDATE dh_modeling_logic_model m SET id=m.id+1000000 FROM public.verify_modeling_0236 c WHERE m.id=c.model_id;")
    reject("UPDATE dh_modeling_logic_model m SET table_type=CASE m.table_type WHEN 'DIMENSION' THEN 'DETAIL' ELSE 'DIMENSION' END "
           "FROM public.verify_modeling_0236 c WHERE m.id=c.model_id;")
    reject("BEGIN; DELETE FROM dh_modeling_logic_model_column f USING public.verify_modeling_0236 c "
           "WHERE f.model_id=c.model_id AND f.field_uid=c.field_uid; COMMIT;")
    # 整单替换可在事务中先软删后恢复同一稳定标识；提交时只检查最终完整结构。
    sql('SET search_path="datahub-center",pg_catalog; BEGIN; UPDATE dh_modeling_logic_model_column f SET deleted=1 '
        "FROM public.verify_modeling_0236 c WHERE f.model_id=c.model_id AND f.field_uid=c.field_uid; "
        "UPDATE dh_modeling_logic_model_column f SET deleted=0 FROM public.verify_modeling_0236 c "
        "WHERE f.model_id=c.model_id AND f.field_uid=c.field_uid; COMMIT;", names["upgrade"])
    sql('SET search_path="datahub-center",pg_catalog; UPDATE dh_modeling_logic_model m '
        "SET status='DRAFT',enabled=0,published_by=NULL,published_time=NULL,type_config='{\"saved\":\"after-migration\"}' "
        "FROM public.verify_modeling_0236 c WHERE m.id=c.model_id;", names["upgrade"])
    reject("UPDATE dh_modeling_indicator_model SET description='重新保存' WHERE id=-236001;")
    reject("UPDATE dh_modeling_business_condition SET description='重新保存' WHERE id=-236001;")
    sql(migration, names["upgrade"])
    check("(SELECT type_config='{\"saved\":\"after-migration\"}'::jsonb AND status='DRAFT' AND enabled=0 "
          "AND published_by IS NULL AND published_time IS NULL FROM dh_modeling_logic_model m "
          "JOIN public.verify_modeling_0236 c ON c.model_id=m.id)")
    sql(f"CREATE DATABASE {names['fresh']};")
    sql(baseline, names["fresh"])

    # 对比真实 PostgreSQL 最终列、约束、索引、触发器和函数，确保新库与已有库升级同构。
    schema = r"""SET search_path="datahub-center",pg_catalog;
        SELECT jsonb_build_object(
          'columns',(SELECT jsonb_agg(jsonb_build_array(table_name,column_name,data_type,is_nullable,column_default)
              ORDER BY table_name,ordinal_position) FROM information_schema.columns
              WHERE table_schema='datahub-center' AND table_name LIKE 'dh_modeling_%'),
          'constraints',(SELECT jsonb_agg(jsonb_build_array(c.relname,k.conname,pg_get_constraintdef(k.oid))
              ORDER BY c.relname,k.conname) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
              JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='datahub-center' AND c.relname LIKE 'dh_modeling_%'),
          'indexes',(SELECT jsonb_agg(jsonb_build_array(indexname,indexdef) ORDER BY indexname)
              FROM pg_indexes WHERE schemaname='datahub-center' AND tablename LIKE 'dh_modeling_%'),
          'triggers',(SELECT jsonb_agg(pg_get_triggerdef(t.oid) ORDER BY c.relname,t.tgname)
              FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE NOT t.tgisinternal AND n.nspname='datahub-center' AND c.relname LIKE 'dh_modeling_%'),
          'functions',(SELECT jsonb_agg(pg_get_functiondef(p.oid) ORDER BY p.proname,pg_get_function_identity_arguments(p.oid))
              FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='datahub-center'
              AND p.prokind='f' AND p.proname LIKE '%modeling%'));
    """
    upgraded = json.loads(sql(schema, names["upgrade"]).stdout.strip())
    fresh = json.loads(sql(schema, names["fresh"]).stdout.strip())
    assert upgraded == fresh, "新库与升级后的建模结构不一致"
    print(json.dumps({"result": "PASS", "sql_runs": count, "databases": names, "logs": str(work)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
