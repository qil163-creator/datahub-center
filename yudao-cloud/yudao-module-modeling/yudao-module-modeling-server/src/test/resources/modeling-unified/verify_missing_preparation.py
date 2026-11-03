"""在本机 PG18 独立克隆库验证 0130 的缺失准备恢复；源模板只读，不自动删除测试库。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--source-db", required=True, help="未执行 0123 的专用旧测试模板")
    parser.add_argument("--completed-source-db", required=True, help="完成 0127 且包含模型的专用测试模板")
    parser.add_argument("--populated-source-db", required=True, help="完成 0125 且包含已确认映射的专用测试模板")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--pg-bin", type=Path, required=True)
    args = parser.parse_args()
    sources = {args.source_db, args.completed_source_db, args.populated_source_db}
    if any(not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", name) for name in sources):
        parser.error("模板名称须为普通 PostgreSQL 标识符")
    if not 1 <= args.port <= 65535:
        parser.error("端口须在 1 至 65535 之间")
    here = Path(__file__).resolve().parent
    repo = next(p for p in here.parents if (p / "pom.xml").is_file() and (p / "sql").is_dir())
    migrations = repo / "sql/postgresql/migrations"
    files = {number: next(migrations.glob(f"{number:04d}_*.sql")) for number in (123, 128, 129, 130)}
    original_hashes = {number: hashlib.sha256(path.read_bytes()).hexdigest() for number, path in files.items()}
    prefix = "modeling_resume_" + uuid.uuid4().hex[:10]
    clones: list[str] = []
    checks = 0
    flags = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    env = dict(os.environ, PGCLIENTENCODING="UTF8", PGCONNECT_TIMEOUT="10")

    # 与 verify_consolidated_upgrade.py 使用相同的本机、只读源模板及写入白名单边界。
    def binary(name: str) -> str:
        return str(args.pg_bin / (name + (".exe" if os.name == "nt" else "")))

    def run(argv: list[str], expected: str | None = None) -> str:
        result = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None and (result.returncode == 0 or expected not in result.stderr):
            raise RuntimeError("未按预期拒绝：" + result.stderr)
        return result.stdout.strip()

    def sql(db: str, content: str = "", file: Path | None = None,
            expected: str | None = None, read_only: bool = False) -> str:
        if db not in clones and (not read_only or db not in sources):
            raise RuntimeError("禁止向本次克隆以外写入")
        if read_only and file is not None:
            raise RuntimeError("只读源模板不得执行 SQL 文件")
        prelude = 'SET search_path="datahub-center",pg_catalog;\n'
        if read_only:
            prelude += "BEGIN READ ONLY;\n"
        with tempfile.TemporaryDirectory(prefix="modeling-resume-sql-") as temp:
            statement = Path(temp) / "statement.sql"
            statement.write_text(prelude + content + ("\nCOMMIT;" if read_only else ""), encoding="utf-8")
            argv = [binary("psql"), *flags, "-d", db, "-X", "-v", "ON_ERROR_STOP=1", "-Atq", "-f", str(statement)]
            if file is not None:
                argv += ["-f", str(file)]
            return run(argv, expected)

    def clone(source: str, suffix: str) -> str:
        if source not in sources and source not in clones:
            raise RuntimeError("未知克隆来源")
        db = prefix + "_" + suffix
        run([binary("createdb"), *flags, "-T", source, db])
        clones.append(db)
        print("CLONE " + db, flush=True)
        return db

    def passed(name: str) -> None:
        nonlocal checks
        checks += 1
        print("PASS " + name, flush=True)

    def digest(db: str, table: str, omitted: tuple[str, ...] = ()) -> str:
        if not re.fullmatch(r"[a-z_]+", table):
            raise ValueError("非法测试表标识符")
        image = "to_jsonb(t)"
        if omitted:
            image += "-ARRAY[" + ",".join("'" + name + "'" for name in omitted) + "]"
        return sql(db, f"SELECT md5(COALESCE(jsonb_agg({image} ORDER BY ({image})::text)::text,'[]')) FROM {table} t;")

    def database_image(db: str) -> dict[str, object]:
        tables = sql(db, """SELECT tablename FROM pg_tables WHERE schemaname='datahub-center'
            AND (tablename LIKE 'dh_modeling_%' OR tablename IN
            ('system_menu','system_role_menu','system_tenant_package','system_dict_type','system_dict_data'))
            ORDER BY tablename;""").splitlines()
        rows = {table: digest(db, table) for table in tables}
        structure = sql(db, """SELECT jsonb_build_object(
            'columns',(SELECT jsonb_agg(jsonb_build_array(table_name,column_name,ordinal_position,data_type,
                udt_name,column_default,is_nullable) ORDER BY table_name,ordinal_position)
                FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name LIKE 'dh_modeling_%'),
            'indexes',(SELECT jsonb_agg(jsonb_build_array(tablename,indexname,indexdef) ORDER BY tablename,indexname)
                FROM pg_indexes WHERE schemaname='datahub-center' AND tablename LIKE 'dh_modeling_%'),
            'constraints',(SELECT jsonb_agg(jsonb_build_array(c.relname,k.conname,pg_get_constraintdef(k.oid))
                ORDER BY c.relname,k.conname) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='datahub-center' AND c.relname LIKE 'dh_modeling_%'),
            'triggers',(SELECT jsonb_agg(jsonb_build_array(c.relname,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid))
                ORDER BY c.relname,t.tgname) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='datahub-center'
                AND c.relname LIKE 'dh_modeling_%' AND NOT t.tgisinternal),
            'functions',(SELECT jsonb_agg(jsonb_build_array(p.proname,pg_get_function_identity_arguments(p.oid),
                pg_get_functiondef(p.oid)) ORDER BY p.proname,pg_get_function_identity_arguments(p.oid))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='datahub-center'
                AND p.prokind='f' AND p.proname LIKE '%modeling%'));""")
        return {"rows": rows, "structure": json.loads(structure)}

    def preserved(db: str) -> dict[str, str]:
        result = {table: digest(db, table) for table in (
            "dh_modeling_dw_layer", "dh_modeling_biz_category", "dh_modeling_data_domain",
            "dh_modeling_subject_domain", "dh_modeling_concept_model_version")}
        result["concept"] = digest(db, "dh_modeling_concept_model", ("business_process_id",))
        result["entity"] = digest(db, "dh_modeling_concept_entity", ("business_object_id", "business_object_version"))
        return result

    def assert_final(db: str) -> None:
        assert sql(db, """SELECT count(*) FROM dh_modeling_unification_state WHERE phase='CLEANED'
            AND migration_key IN ('UNIFIED_MODEL_V1','WORKSPACE_REMOVAL_V1','BUSINESS_PLANNING_REMOVAL_V1');""") == "3"
        assert sql(db, """SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'
            AND table_name='dh_modeling_physical_model' AND column_name IN ('name','code','business_process_id');""") == "0"
        assert sql(db, "SELECT to_regclass('dh_modeling_collaboration') IS NULL;") == "t"
        assert sql(db, "SELECT to_regclass('dh_modeling_business_process') IS NULL;") == "t"
        assert sql(db, "SELECT count(*) FROM dh_modeling_unified_menu_backup WHERE migration_key='UNIFIED_MODEL_V1';") == "5"
        assert sql(db, """SELECT count(*) FROM system_menu WHERE deleted=0 AND status=0 AND visible
            AND trim(both '/' from component)='modeling/tablemodel/index';""") == "1"
        assert sql(db, """SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='datahub-center' AND t.tgenabled='O'
            AND (c.relname,t.tgname) IN (('dh_modeling_physical_model','modeling_unified_binding_guard'),
                ('dh_modeling_physical_model_version','modeling_unified_version_guard'),
                ('dh_modeling_logic_model_version','modeling_published_design_guard'));""") == "3"

    def upgrade_replay(db: str) -> None:
        old = preserved(db)
        sql(db, "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';", file=files[130])
        assert_final(db)
        assert preserved(db) == old, "0130 不应清空概念、规划或改写历史快照"
        after = database_image(db)
        sql(db, file=files[130])
        assert database_image(db) == after, "0130 整文件重放改变了原行或结构"

    assert int(sql(args.source_db, "SHOW server_version_num;", read_only=True)) // 10000 == 18
    assert sql(args.source_db, "SELECT to_regclass('dh_modeling_unification_state') IS NULL;", read_only=True) == "t"
    assert sql(args.source_db, "SELECT count(*) FROM dh_modeling_physical_model;", read_only=True) == "3"
    seed = clone(args.source_db, "seed")
    sql(seed, "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';", file=files[128])
    sql(seed, """
        INSERT INTO dh_modeling_concept_model(id,tenant_id,name) VALUES(99793001,7,'缺账本恢复保留概念');
        INSERT INTO dh_modeling_concept_entity(id,tenant_id,model_id,name,code,attrs)
            VALUES(99793002,7,99793001,'保留实体','resume_entity','[{"name":"历史属性","code":"old_value"}]');
        INSERT INTO dh_modeling_concept_model_version(id,tenant_id,model_id,version,snapshot)
            VALUES(99793003,7,99793001,1,'{"model":{"name":"冻结说明","businessProcessId":991},"entities":[{"businessObjectId":992}]}');
    """)

    absent = clone(seed, "absent")
    upgrade_replay(absent)
    passed("0128_then_no_0123_recovers_by_real_preparation_and_replays")

    partial = clone(seed, "partial")
    prepare = files[123].read_text(encoding="utf-8")
    marker = "INSERT INTO dh_modeling_unification_state(migration_key,phase,operator_name)"
    assert prepare.count(marker) == 1, "0123 截断点变化，须先核对夹具"
    sql(partial, prepare.split(marker)[0] + "\nCOMMIT;\n")
    assert sql(partial, "SELECT count(*) FROM dh_modeling_unification_state;") == "0"
    assert sql(partial, "SELECT count(*) FROM dh_modeling_unification_map;") == "0"
    assert sql(partial, "SELECT to_regclass('dh_modeling_unification_map') IS NOT NULL;") == "t"
    late = clone(partial, "late_failure")
    archived = clone(partial, "archive_conflict")
    missing_column = clone(partial, "column_conflict")
    before = database_image(partial)
    sql(partial, file=files[129], expected="0123")
    assert database_image(partial) == before, "旧 0129 拒绝后应完整回滚"
    upgrade_replay(partial)
    passed("partially_committed_0123_reproduces_old_failure_then_0130_recovers")

    sql(late, "UPDATE system_menu SET component='fixture/missing-physical-menu' WHERE component='modeling/physicalmodel/index';")
    before = database_image(late)
    sql(late, file=files[130], expected="缺少唯一有效旧菜单组件")
    assert database_image(late) == before, "菜单失败必须回滚补准备及切换 DDL"
    assert sql(late, "SELECT count(*) FROM dh_modeling_unification_state;") == "0", "不能残留伪造 PREPARED"
    passed("late_menu_failure_rolls_back_preparation_and_cleanup_without_fabricated_phase")

    prepared = clone(seed, "prepared")
    sql(prepared, file=files[123])
    prior = sql(prepared, "SELECT jsonb_build_array(operator_name,create_time) FROM dh_modeling_unification_state WHERE migration_key='UNIFIED_MODEL_V1';")
    upgrade_replay(prepared)
    assert sql(prepared, "SELECT jsonb_build_array(operator_name,create_time) FROM dh_modeling_unification_state WHERE migration_key='UNIFIED_MODEL_V1';") == prior
    passed("completed_0123_is_not_reprepared_and_keeps_original_preparation_identity")

    completed = clone(args.completed_source_db, "completed127")
    assert sql(completed, "SELECT count(*)>0 FROM dh_modeling_logic_model;") == "t"
    before = database_image(completed)
    upgrade_replay(completed)
    assert database_image(completed)["rows"] == before["rows"], "已完成阶段重放不得更改任何原行"
    passed("completed_0127_with_new_models_preserves_all_rows_and_replays")

    populated = clone(args.populated_source_db, "populated125")
    assert sql(populated, "SELECT count(*)>0 FROM dh_modeling_unification_map;") == "t"
    assert sql(populated, "SELECT count(*) FROM dh_modeling_unification_map WHERE review_status<>'CONFIRMED';") == "0"
    evidence_tables = ("dh_modeling_logic_model_version", "dh_modeling_physical_model_version", "dh_modeling_physical_model",
                       "dh_modeling_unification_map", "dh_modeling_unified_menu_backup", "dh_modeling_deploy_record", "dh_modeling_schema_baseline")
    evidence = {table: digest(populated, table) for table in evidence_tables}
    upgrade_replay(populated)
    assert evidence == {table: digest(populated, table) for table in evidence_tables}
    passed("populated_0125_keeps_frozen_versions_targets_and_confirmed_mapping_evidence")

    sql(archived, "CREATE TABLE dh_modeling_workspace_removal_archive(fixture_evidence jsonb); INSERT INTO dh_modeling_workspace_removal_archive VALUES('{\"original\":true}');")
    before = database_image(archived)
    sql(archived, file=files[130], expected="已有退役归档")
    assert database_image(archived) == before
    assert sql(archived, "SELECT count(*) FROM dh_modeling_unification_state;") == "0"
    passed("missing_phase_with_later_archive_is_rejected_without_relabeling")

    sql(missing_column, "ALTER TABLE dh_modeling_physical_model DROP COLUMN name;")
    before = database_image(missing_column)
    sql(missing_column, file=files[130], expected="旧列已删除")
    assert database_image(missing_column) == before
    assert sql(missing_column, "SELECT count(*) FROM dh_modeling_unification_state;") == "0"
    passed("missing_phase_with_removed_legacy_column_is_rejected_without_recreation")

    assert original_hashes == {number: hashlib.sha256(path.read_bytes()).hexdigest() for number, path in files.items()}, "验证期间生产迁移文件变化"
    print(f"PASS {checks} 检查组；0130 SHA256=" + original_hashes[130], flush=True)
    print("仅写入本次独立克隆，保留供复核：" + ", ".join(clones), flush=True)


if __name__ == "__main__":
    main()
