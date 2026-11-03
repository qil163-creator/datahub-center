"""在本地 PostgreSQL 18 独立克隆库验证历史 0129；不修改源模板，不自动删除测试库。

引用审计保留断言证明 0129 当时的升级契约，不适用于已移除引用控制的当前新库基线。
"""
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
    parser.add_argument("--source-db", required=True, help="尚未执行 0123 的明确旧演示测试模板")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--pg-bin", type=Path, help="可选：psql 和 createdb 所在目录")
    parser.add_argument("--populated-source-db", help="可选：已有非空模型、已确认映射且完成 0125 的专用测试模板")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.source_db):
        parser.error("源模板名称须为普通 PostgreSQL 标识符")
    if not 1 <= args.port <= 65535:
        parser.error("端口须在 1 至 65535 之间")
    if args.populated_source_db and not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.populated_source_db):
        parser.error("非空模型测试模板名称须为普通 PostgreSQL 标识符")
    sources = {args.source_db}
    if args.populated_source_db:
        sources.add(args.populated_source_db)
    here = Path(__file__).resolve().parent
    repo = next(p for p in here.parents if (p / "pom.xml").exists() and (p / "sql").is_dir())
    migrations = repo / "sql/postgresql/migrations"
    names = {
        123: "0123_modeling_unified-definition.sql",
        124: "0124_modeling_unified-target-cleanup.sql",
        125: "0125_modeling_unified-menu.sql",
        126: "0126_modeling_remove-workspace.sql",
        127: "0127_modeling_remove-business-process-object.sql",
        128: "0128_modeling_clear-unused-before-unification.sql",
        129: "0129_modeling_consolidated-upgrade.sql",
    }
    files = {number: migrations / name for number, name in names.items()}
    for file in files.values():
        if not file.is_file():
            parser.error(f"迁移尚未准备好：{file.name}")
    original_bytes = {number: hashlib.sha256(file.read_bytes()).hexdigest() for number, file in files.items()}
    prefix = "modeling_repair_" + uuid.uuid4().hex[:10]
    clones: list[str] = []
    checks = 0
    flags = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    env = dict(os.environ, PGCLIENTENCODING="UTF8", PGCONNECT_TIMEOUT="10")

    def binary(name: str) -> str:
        if args.pg_bin is None:
            return name
        return str(args.pg_bin / (name + (".exe" if os.name == "nt" else "")))

    def run(argv: list[str], expected: str | None = None) -> str:
        result = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None and (result.returncode == 0 or expected not in result.stderr):
            raise RuntimeError("未按预期拒绝：" + result.stderr)
        return result.stdout.strip()

    def sql(db: str, content: str | None = None, file: Path | None = None,
            expected: str | None = None, read_only: bool = False) -> str:
        if db not in clones and (not read_only or db not in sources):
            raise RuntimeError("禁止向本次创建的独立克隆之外写入或执行迁移")
        argv = [binary("psql"), *flags, "-d", db, "-X", "-v", "ON_ERROR_STOP=1", "-Atq"]
        prelude = 'SET search_path="datahub-center",pg_catalog;\n'
        if read_only:
            if file is not None:
                raise RuntimeError("源模板只读路径不允许传入文件")
            prelude += "BEGIN READ ONLY;\n"
        with tempfile.TemporaryDirectory(prefix="modeling-repair-sql-") as temp:
            statement = Path(temp) / "statement.sql"
            statement.write_text(prelude + (content or "") + ("\nCOMMIT;" if read_only else ""), encoding="utf-8")
            argv += ["-f", str(statement)]
            if file is not None:
                argv += ["-f", str(file)]
            return run(argv, expected)

    def clone(source: str, suffix: str) -> str:
        if source not in sources and source not in clones:
            raise RuntimeError("克隆来源不在源模板与本次克隆清单内")
        db = prefix + "_" + suffix
        if len(db) > 63:
            raise RuntimeError("测试库名称过长")
        run([binary("createdb"), *flags, "-T", source, db])
        clones.append(db)
        print("CLONE " + db, flush=True)
        return db

    def passed(name: str) -> None:
        nonlocal checks
        checks += 1
        print("PASS " + name, flush=True)

    def exists(db: str, table: str) -> bool:
        return sql(db, f"SELECT to_regclass('{table}') IS NOT NULL;") == "t"

    def digest(db: str, table: str, omitted: tuple[str, ...] = ()) -> str:
        if not re.fullmatch(r"[a-z_]+", table):
            raise ValueError("非测试表标识符")
        image = "to_jsonb(t)"
        if omitted:
            image += "-ARRAY[" + ",".join("'" + value + "'" for value in omitted) + "]"
        return sql(db, f"SELECT md5(COALESCE(jsonb_agg({image} ORDER BY ({image})::text)::text,'[]')) FROM {table} t;")

    def database_image(db: str) -> dict[str, object]:
        # 原行摘要和结构均比对；拒绝用“最终无旧表”代替整体回滚与重放验证。
        table_names = sql(db, """SELECT tablename FROM pg_tables WHERE schemaname='datahub-center'
            AND (tablename LIKE 'dh_modeling_%' OR tablename IN
                ('system_menu','system_role_menu','system_tenant_package','system_dict_type','system_dict_data'))
            ORDER BY tablename;""").splitlines()
        rows = {table: digest(db, table) for table in table_names}
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

    def preserve_image(db: str) -> dict[str, str]:
        tables = ["dh_modeling_dw_layer", "dh_modeling_biz_category", "dh_modeling_data_domain",
                  "dh_modeling_subject_domain", "dh_modeling_theme_plan", "dh_modeling_planning_change",
                  "dh_modeling_concept_model_version", "dh_modeling_concept_relation",
                  "dh_modeling_model_check_config", "dh_governance_standard_mapping",
                  "dh_governance_lineage_event_outbox", "dh_dataforge_reference_audit"]
        result = {table: digest(db, table) for table in tables}
        result["concept_heads"] = digest(db, "dh_modeling_concept_model", ("business_process_id",))
        result["concept_entities"] = digest(db, "dh_modeling_concept_entity", ("business_object_id", "business_object_version"))
        return result

    def assert_final(db: str) -> None:
        assert sql(db, """SELECT count(*) FROM dh_modeling_unification_state WHERE phase='CLEANED'
            AND migration_key IN ('UNIFIED_MODEL_V1','WORKSPACE_REMOVAL_V1','BUSINESS_PLANNING_REMOVAL_V1');""") == "3"
        assert sql(db, """SELECT count(*) FROM information_schema.tables WHERE table_schema='datahub-center' AND table_name IN (
            'dh_modeling_collaboration','dh_modeling_collaborator','dh_modeling_approval','dh_modeling_design_report',
            'dh_modeling_inbox','dh_modeling_object_change','dh_modeling_reference_receipt','dh_modeling_business_process',
            'dh_modeling_business_process_data_domain_rel','dh_modeling_business_process_supporting_domain_rel',
            'dh_modeling_business_process_domain_candidate','dh_modeling_subject_domain_process_rel','dh_modeling_business_object',
            'dh_modeling_business_object_attribute','dh_modeling_business_object_process_rel',
            'dh_modeling_business_object_version','dh_modeling_business_object_external_ref');""") == "0"
        assert sql(db, """SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'
            AND ((table_name='dh_modeling_physical_model' AND column_name IN
                ('name','code','description','model_style','warehouse_layer','layer_id','subject_domain_id',
                 'business_process_id','data_domain_id','table_type','classification_status','theme_id'))
                OR column_name IN ('business_process_id','business_object_id','business_object_version'));""") == "0"
        assert sql(db, """SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='datahub-center' AND t.tgenabled='O'
            AND (c.relname,t.tgname) IN (('dh_modeling_physical_model','modeling_unified_binding_guard'),
                ('dh_modeling_physical_model_version','modeling_unified_version_guard'),
                ('dh_modeling_logic_model_version','modeling_published_design_guard'));""") == "3"
        assert sql(db, """SELECT count(*) FROM system_menu WHERE deleted=0 AND status=0 AND visible
            AND trim(both '/' from component)='modeling/tablemodel/index';""") == "1"
        assert sql(db, """SELECT count(*) FROM system_menu WHERE deleted=0 AND visible
            AND trim(both '/' from component) IN ('modeling/conceptmodel/index','modeling/physicalmodel/index','modeling/modelcheck/index');""") == "0"
        assert sql(db, """SELECT count(*) FROM system_menu WHERE permission IN
            ('modeling:workspace:approval','modeling:workspace:collaboration','modeling:workspace:inbox')
            OR permission LIKE 'modeling:business-process:%' OR permission LIKE 'modeling:business-object:%';""") == "0"
        assert sql(db, "SELECT count(*) FROM dh_modeling_unified_menu_backup WHERE migration_key='UNIFIED_MODEL_V1';") == "5"
        assert sql(db, "SELECT count(*) FROM dh_modeling_logic_model;") == "0"
        assert sql(db, "SELECT count(*) FROM dh_modeling_physical_model;") == "0"
        assert sql(db, "SELECT count(*) FROM dh_modeling_unification_map;") == "0"
        assert sql(db, "SELECT count(*) FROM dh_modeling_concept_model WHERE id=99792901 AND tenant_id=7;") == "1"
        assert sql(db, """SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'
            AND (table_name,column_name) IN (('dh_modeling_logic_model','revision'),('dh_modeling_logic_model','layout_revision'),
                ('dh_modeling_physical_model','revision'),('dh_modeling_physical_model','source_logic_version_id'),
                ('dh_modeling_physical_model_version','design_version_id'),('dh_modeling_physical_model_version','definition_origin'));""") == "6"

    def apply_stage(db: str, number: int) -> None:
        declaration = "SET datahub.modeling_unified_cutover='UNIFIED_MODEL_V1';"
        if number != 124:
            sql(db, declaration, file=files[number])
            return
        # 历史脚本原件不可变：只在临时测试输入中更正已知打字错误，构造成功 0124 阶段。
        body = files[124].read_text(encoding="utf-8")
        typo = "dh_modeling_unificAation_map"
        if body.count(typo) != 1:
            raise RuntimeError("历史 0124 的已知拼写错误数量变化，须先重新核查阶段夹具")
        with tempfile.TemporaryDirectory(prefix="modeling-repair-historic-124-") as temp:
            fixture = Path(temp) / files[124].name
            fixture.write_text(body.replace(typo, "dh_modeling_unification_map"), encoding="utf-8")
            sql(db, declaration, file=fixture)

    # 源数据库所有查询均开启数据库强制只读事务；后续只对新克隆执行脚本。
    assert int(sql(args.source_db, "SHOW server_version_num;", read_only=True)) // 10000 == 18
    assert sql(args.source_db, "SELECT to_regclass('dh_modeling_unification_state') IS NULL;", read_only=True) == "t"
    assert sql(args.source_db, "SELECT count(*) FROM dh_modeling_physical_model;", read_only=True) == "3"
    stage_seed = clone(args.source_db, "seed")
    sql(stage_seed, "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';", file=files[128])
    sql(stage_seed, """
        INSERT INTO dh_modeling_biz_category(id,tenant_id,name,code) VALUES(99792910,7,'保留业务域','repair_preserve_biz');
        INSERT INTO dh_modeling_data_domain(id,tenant_id,name,code) VALUES(99792911,7,'保留数据域','repair_preserve_domain');
        INSERT INTO dh_modeling_subject_domain(id,tenant_id,name,code) VALUES(99792912,7,'保留主题域','repair_preserve_subject');
        INSERT INTO dh_modeling_dw_layer(id,tenant_id,parent_id,level_no,layer_name,node_type,status)
            VALUES(99792913,7,0,1,'保留分层目录','CATEGORY',0);
        INSERT INTO dh_modeling_dw_layer(id,tenant_id,parent_id,level_no,layer_name,layer_code,node_type,table_prefix,default_table_type,status)
            VALUES(99792914,7,99792913,2,'保留有效分层','REPAIR','LAYER','repair_','DETAIL',0);
        INSERT INTO dh_modeling_concept_model(id,tenant_id,name,data_domain_id)
            VALUES(99792901,7,'修复前保留概念',99792911);
        INSERT INTO dh_modeling_concept_entity(id,tenant_id,model_id,name,code,description,attrs)
            VALUES(99792902,7,99792901,'保留实体','repair_entity','原始实体说明','[{"name":"保留属性","code":"old_value","dataType":"STRING"}]');
        INSERT INTO dh_modeling_concept_model_version(id,tenant_id,model_id,version,snapshot)
            VALUES(99792903,7,99792901,1,'{"model":{"name":"原始历史","businessProcessId":991},"entities":[{"businessObjectId":992,"businessObjectVersion":3}]}');
    """)
    preserved = preserve_image(stage_seed)
    sql(stage_seed, file=files[123])
    assert sql(stage_seed, "SELECT phase FROM dh_modeling_unification_state WHERE migration_key='UNIFIED_MODEL_V1';") == "PREPARED"
    passed("explicit_0128_reset_then_0123_prepared_fixture")

    # 建立五种真实提交阶段，不用手写等价 DDL 或修改阶段标记冒充历史执行。
    stages = {123: clone(stage_seed, "stage123")}
    for number in range(124, 128):
        stages[number] = clone(stages[number - 1], "stage" + str(number))
        apply_stage(stages[number], number)
    for number, db in stages.items():
        old_evidence = {table: digest(db, table) for table in
                        ("dh_modeling_unification_map", "dh_modeling_unified_menu_backup", "dh_modeling_workspace_removal_archive")
                        if exists(db, table)}
        old_clear_flag = "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';" if number == 123 else None
        sql(db, old_clear_flag, file=files[129])
        assert_final(db)
        assert preserve_image(db) == preserved, f"阶段 {number} 修改了应保留的概念、规划或跨模块数据"
        assert old_evidence == {table: digest(db, table) for table in old_evidence}, f"阶段 {number} 改写了既有证据"
        passed(f"stage_{number}_repair_preserves_models_planning_and_existing_evidence")
        first = database_image(db)
        sql(db, file=files[129])
        assert_final(db)
        assert database_image(db) == first, f"阶段 {number} 重放修改了原行或最终结构"
        passed(f"stage_{number}_full_file_replay_is_stable")

    no_prepare = clone(args.source_db, "no123")
    before = database_image(no_prepare)
    sql(no_prepare, file=files[129], expected="0123")
    assert database_image(no_prepare) == before
    passed("missing_0123_rejected_atomically")

    unreviewed = clone(args.source_db, "unreviewed")
    sql(unreviewed, file=here / "setup-valid-fixture.sql")
    sql(unreviewed, file=files[123])
    assert sql(unreviewed, "SELECT count(*)>0 FROM dh_modeling_unification_map WHERE review_status<>'CONFIRMED';") == "t"
    before = database_image(unreviewed)
    sql(unreviewed, file=files[129], expected="未映射或未审查")
    assert database_image(unreviewed) == before
    passed("unreviewed_real_mapping_rejected_without_clearing_any_model")

    late_failure = clone(stage_seed, "missing_menu")
    sql(late_failure, "UPDATE system_menu SET component='fixture/missing-physical-menu' WHERE component='modeling/physicalmodel/index';")
    before = database_image(late_failure)
    sql(late_failure, file=files[129], expected="缺少唯一有效旧菜单组件")
    assert database_image(late_failure) == before
    assert sql(late_failure, "SELECT phase FROM dh_modeling_unification_state WHERE migration_key='UNIFIED_MODEL_V1';") == "PREPARED"
    assert sql(late_failure, """SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'
        AND table_name='dh_modeling_physical_model' AND column_name='name';""") == "1"
    passed("later_menu_failure_rolls_back_earlier_cleanup_ddl_and_stage")

    current = stages[127]
    sql(current, """
        INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,description,status,latest_version,table_type,
            data_domain_id,layer_id,classification_status)
            VALUES(99792920,7,'升级后新增设计','repair_new_design','首次说明','PUBLISHED',1,'DETAIL',99792911,99792914,'CONFIRMED');
        INSERT INTO dh_modeling_logic_model_version(id,tenant_id,model_id,version,status,fields,model_snapshot,relation_snapshot)
            VALUES(99792921,7,99792920,1,'PUBLISHED','[{"fieldUid":"repair_new_field","fieldName":"measure","dataType":"varchar"}]',
                '{"snapshotSchemaVersion":2,"modelId":99792920,"name":"冻结设计","modelStyle":"RELATIONAL","tableType":"DETAIL","layerId":99792914}', '[]');
        UPDATE dh_modeling_logic_model SET description='升级后可正常编辑',revision=revision+1 WHERE id=99792920;
    """)
    sql(current, "UPDATE dh_modeling_logic_model SET data_domain_id=99999999 WHERE id=99792920;", expected="模型归属不存在或不属于当前租户")
    new_model = digest(current, "dh_modeling_logic_model")
    new_version = digest(current, "dh_modeling_logic_model_version")
    sql(current, "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';", file=files[129])
    assert new_model == digest(current, "dh_modeling_logic_model")
    assert new_version == digest(current, "dh_modeling_logic_model_version")
    passed("modern_ownership_accepts_new_design_and_replay_never_clears_new_models_even_with_old_reset_flag")

    if args.populated_source_db:
        populated = clone(args.populated_source_db, "populated125")
        assert sql(populated, "SELECT count(*)>0 FROM dh_modeling_logic_model;") == "t", "非空夹具必须实际包含逻辑模型"
        assert sql(populated, "SELECT count(*)>0 FROM dh_modeling_physical_model;") == "t", "非空夹具必须实际包含物理模型"
        assert sql(populated, "SELECT count(*)>0 FROM dh_modeling_unification_map;") == "t", "非空夹具必须实际包含映射证据"
        assert sql(populated, "SELECT count(*) FROM dh_modeling_unification_map WHERE review_status<>'CONFIRMED';") == "0"
        assert sql(populated, "SELECT count(*) FROM dh_modeling_unified_menu_backup;") == "5"
        immutable_tables = ["dh_modeling_logic_model_version", "dh_modeling_physical_model_version",
                            "dh_modeling_unification_map", "dh_modeling_unified_menu_backup",
                            "dh_modeling_physical_model", "dh_modeling_deploy_record", "dh_modeling_schema_baseline"]
        immutable = {table: digest(populated, table) for table in immutable_tables}
        model_identity = sql(populated, """SELECT jsonb_agg(jsonb_build_array(id,tenant_id,name,code,description,latest_version,revision)
            ORDER BY id,tenant_id) FROM dh_modeling_logic_model;""")
        sql(populated, "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';", file=files[129])
        assert immutable == {table: digest(populated, table) for table in immutable_tables}
        assert model_identity == sql(populated, """SELECT jsonb_agg(jsonb_build_array(id,tenant_id,name,code,description,latest_version,revision)
            ORDER BY id,tenant_id) FROM dh_modeling_logic_model;""")
        populated_after = database_image(populated)
        sql(populated, file=files[129])
        assert database_image(populated) == populated_after
        passed("populated_0125_upgrade_and_replay_preserve_model_ids_history_and_confirmed_mapping_payloads")

    assert original_bytes == {number: hashlib.sha256(file.read_bytes()).hexdigest() for number, file in files.items()}, "验证期间生产迁移文件发生变化"
    print(f"PASS {checks} 检查组；仅本次独立克隆库已写入并保留供复核：" + ", ".join(clones), flush=True)


if __name__ == "__main__":
    main()
