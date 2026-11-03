"""在显式本地隔离 PostgreSQL 克隆库验证 0127，不修改源模板或自动删除测试库。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--source-db", required=True)
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--verify-baseline", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.source_db):
        parser.error("源库名称必须是普通标识符")
    root = next(p for p in Path(__file__).resolve().parents if (p / "pom.xml").exists() and (p / "sql").is_dir())
    migration = root / "sql/postgresql/migrations/0127_modeling_remove-business-process-object.sql"
    flags = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    env = dict(os.environ, PGCLIENTENCODING="UTF8")
    prefix = "modeling_planning_" + uuid.uuid4().hex[:10]
    count = 0

    def command(argv, expected=None):
        result = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None and (result.returncode == 0 or expected not in result.stderr):
            raise RuntimeError("未按预期拒绝：" + result.stderr)
        return result.stdout.strip()

    def sql(db, content=None, file=None, expected=None):
        argv = ["psql", *flags, "-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1", "-d", db]
        if content is not None:
            with tempfile.TemporaryDirectory(prefix="planning-removal-sql-") as temp:
                statement = Path(temp) / "statement.sql"
                statement.write_text('SET search_path="datahub-center",pg_catalog; ' + content, encoding="utf-8")
                return command(argv + ["-f", str(statement)], expected)
        return command(argv + ["-f", str(file)], expected)

    def passed(name):
        nonlocal count
        count += 1
        print("PASS " + name, flush=True)

    def assert_final(db):
        assert sql(db, """SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='datahub-center' AND c.relname ~ '^dh_modeling_(business_(process|object)|subject_domain_process)'""") == "0"
        assert sql(db, """SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'
            AND column_name IN ('business_process_id','business_object_id','business_object_version')""") == "0"
        assert sql(db, """SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='datahub-center' AND p.prosrc ~ 'dh_modeling_(business_(process|object)|subject_domain_process)'""") == "0"
        assert sql(db, """SELECT count(*) FROM system_menu WHERE permission LIKE 'modeling:business-process:%'
            OR permission LIKE 'modeling:business-object:%'
            OR trim(both '/' from COALESCE(component,'')) IN ('modeling/businessprocess/index','modeling/businessobject/index')""") == "0"
        assert sql(db, """SELECT count(*) FROM dh_modeling_unification_state
            WHERE migration_key='BUSINESS_PLANNING_REMOVAL_V1' AND phase='CLEANED'""") == "1"
        assert sql(db, """SELECT count(*) FROM information_schema.tables WHERE table_schema='datahub-center'
            AND table_name IN ('dh_modeling_biz_category','dh_modeling_data_domain','dh_modeling_subject_domain','dh_modeling_dw_layer')""") == "4"

    # 只允许从已完成 0126、尚未执行 0127 且无模型的专用空测试库复制。
    assert sql(args.source_db, """SELECT EXISTS(SELECT 1 FROM dh_modeling_unification_state
        WHERE migration_key='WORKSPACE_REMOVAL_V1' AND phase='CLEANED')
        AND NOT EXISTS(SELECT 1 FROM dh_modeling_unification_state WHERE migration_key='BUSINESS_PLANNING_REMOVAL_V1')
        AND NOT EXISTS(SELECT 1 FROM dh_modeling_logic_model)
        AND NOT EXISTS(SELECT 1 FROM dh_modeling_concept_model)
        AND NOT EXISTS(SELECT 1 FROM dh_modeling_physical_model)""") == "t"
    db = prefix + "_upgrade"
    command(["createdb", *flags, "-T", args.source_db, db])
    sql(db, """
    INSERT INTO dh_modeling_biz_category(id,tenant_id,name,code) VALUES(997700,7,'保留业务域','retain_biz');
    INSERT INTO dh_modeling_data_domain(id,tenant_id,name,code,deleted)
      VALUES(997701,7,'原隐式数据域','implicit_domain',0),(997702,7,'明确数据域','explicit_domain',0),
            (997703,7,'已删除数据域','deleted_domain',1),(997704,8,'其他租户数据域','other_tenant_domain',0);
    INSERT INTO dh_modeling_subject_domain(id,tenant_id,name,code) VALUES(997705,7,'保留主题域','retain_subject');
    INSERT INTO dh_modeling_data_domain(id,tenant_id,name,code,status) VALUES(997706,7,'停用数据域','disabled_domain',1);
    INSERT INTO dh_modeling_subject_domain(id,tenant_id,name,code,status) VALUES(997707,7,'停用主题域','disabled_subject',1);
    INSERT INTO dh_modeling_dw_layer(id,tenant_id,parent_id,level_no,layer_name,node_type,status)
      VALUES(997790,7,0,1,'测试分类','CATEGORY',0);
    INSERT INTO dh_modeling_dw_layer(id,tenant_id,parent_id,level_no,layer_name,layer_code,node_type,table_prefix,default_table_type,status)
      VALUES(997791,7,997790,2,'有效分层','FIXTURE','LAYER','fx_','DETAIL',0),
            (997792,7,997790,2,'停用分层','DISABLED','LAYER','dx_','DETAIL',1);
    INSERT INTO dh_modeling_business_process(id,tenant_id,biz_category_id,name,code,activity_definition,canonical_grain_definition,data_domain_id)
      VALUES(997711,7,997700,'有效过程','valid_process','活动','一次活动',997701),
            (997712,7,997700,'删除过程','deleted_process','活动','一次活动',997701),
            (997714,8,997700,'其他租户过程','other_tenant_process','活动','一次活动',997704);
    INSERT INTO dh_modeling_concept_model(id,tenant_id,name,business_process_id) VALUES(997721,7,'保留概念模型',997711);
    INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,business_process_id,status,table_type)
      VALUES(997722,7,'隐式归属设计','implicit_design',997711,'DRAFT','DETAIL');
    INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,data_domain_id,subject_domain_id,status,table_type)
      VALUES(997723,7,'明确归属设计','explicit_design',997702,997705,'DRAFT','DETAIL');
    INSERT INTO dh_modeling_concept_entity(id,tenant_id,model_id,name,code,description,attrs,business_object_id,business_object_version)
      VALUES(997731,7,997721,'原有实体','entity','保留实体说明','[{"name":"原有属性","code":"field_a","dataType":"STRING"}]',997741,3);
    INSERT INTO dh_modeling_concept_model_version(id,tenant_id,model_id,version,snapshot)
      VALUES(997732,7,997721,1,'{"model":{"businessProcessId":997711,"name":"历史概念模型"},"entities":[{"businessObjectId":997741,"businessObjectVersion":3,"name":"历史实体"}]}');
    INSERT INTO dh_modeling_logic_model_version(id,tenant_id,model_id,version,status,fields,model_snapshot)
      VALUES(997733,7,997722,1,'PUBLISHED','[{"name":"历史字段","fieldUid":"stable_uid"}]',
        '{"modelId":997722,"snapshotSchemaVersion":2,"businessProcessId":997711,"name":"历史设计"}');
    INSERT INTO system_tenant_package(id,name,status,menu_ids)
      SELECT 997751,'退役验证套餐',0,jsonb_agg(to_jsonb(id))::text FROM system_menu
      WHERE id=6027 OR permission LIKE 'modeling:business-process:%' OR permission LIKE 'modeling:business-object:%'
         OR component='modeling/businessobject/index';
    INSERT INTO system_role_menu(id,tenant_id,role_id,menu_id)
      SELECT 997752,7,997753,id FROM system_menu WHERE permission='modeling:business-process:query';
    INSERT INTO system_menu(id,name,type,parent_id,path,permission)
      SELECT 997754,'历史子按钮',3,id,'','fixture:child' FROM system_menu WHERE permission='modeling:business-process:create';
    INSERT INTO system_dict_type(id,name,type) VALUES(997761,'测试过程专属状态','modeling_business_process_fixture');
    INSERT INTO system_dict_data(id,sort,label,value,dict_type) VALUES(997762,0,'测试值','0','modeling_business_process_fixture');
    -- 以下仅在新建测试克隆内模拟历史无效引用，恢复触发器后再执行迁移。
    ALTER TABLE dh_modeling_business_process DISABLE TRIGGER modeling_ownership_reference_guard;
    UPDATE dh_modeling_business_process SET deleted=1 WHERE id=997712;
    INSERT INTO dh_modeling_business_process(id,tenant_id,biz_category_id,name,code,activity_definition,canonical_grain_definition,data_domain_id)
      VALUES(997713,7,997700,'无效数据域过程','invalid_domain_process','活动','一次活动',997703);
    ALTER TABLE dh_modeling_business_process ENABLE TRIGGER modeling_ownership_reference_guard;
    ALTER TABLE dh_modeling_logic_model DISABLE TRIGGER modeling_ownership_reference_guard;
    UPDATE dh_modeling_logic_model SET business_process_id=997711 WHERE id=997723;
    INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,business_process_id,status,table_type)
      VALUES(997724,7,'无效过程设计','deleted_process_design',997712,'DRAFT','DETAIL'),
            (997725,7,'无效数据域设计','invalid_domain_design',997713,'DRAFT','DETAIL'),
            (997726,7,'跨租户过程设计','other_tenant_design',997714,'DRAFT','DETAIL');
    ALTER TABLE dh_modeling_logic_model DISABLE TRIGGER trg_modeling_layer_reference;
    UPDATE dh_modeling_logic_model SET layer_id=997791,classification_status='UNCLASSIFIED' WHERE id IN (997722,997723);
    UPDATE dh_modeling_logic_model SET layer_id=997791,classification_status='CONFIRMED' WHERE id IN (997724,997725,997726);
    INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,data_domain_id,layer_id,table_type,subject_domain_id,classification_status,deleted)
      VALUES(997780,7,'旧缺过程','formerly_no_process',997701,997791,'DETAIL',NULL,'UNCLASSIFIED',0),
            (997781,7,'停用数据域','disabled_domain_model',997706,997791,'DETAIL',NULL,'CONFIRMED',0),
            (997782,7,'停用分层','disabled_layer_model',997701,997792,'DETAIL',NULL,'CONFIRMED',0),
            (997783,7,'分层目录','category_model',997701,997790,'DETAIL',NULL,'CONFIRMED',0),
            (997784,7,'无效用途','invalid_type_model',997701,997791,'INVALID',NULL,'CONFIRMED',0),
            (997785,7,'停用主题','disabled_subject_model',997701,997791,'DETAIL',997707,'CONFIRMED',0),
            (997786,7,'失效直接域','dangling_domain_model',99999999,997791,'DETAIL',NULL,'CONFIRMED',0),
            (997787,7,'跨租户直接域','foreign_domain_model',997704,997791,'DETAIL',NULL,'CONFIRMED',0),
            (997788,7,'已删设计','deleted_model',NULL,NULL,'INVALID',NULL,'CONFIRMED',1),
            (997789,7,'原已归属','already_classified',997701,997791,'DETAIL',NULL,'CONFIRMED',0);
    ALTER TABLE dh_modeling_logic_model ENABLE TRIGGER trg_modeling_layer_reference;
    ALTER TABLE dh_modeling_logic_model ENABLE TRIGGER modeling_ownership_reference_guard;
    """)
    preserved_query = """SELECT jsonb_build_array(
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_concept_model_version v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_logic_model_version v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_physical_model_version v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_planning_change v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY source_table,row_key) FROM dh_modeling_workspace_removal_archive v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_biz_category v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_data_domain v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_subject_domain v),
        (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dh_modeling_dw_layer v))"""
    preserved = sql(db, preserved_query)
    entity = sql(db, "SELECT to_jsonb(e)-ARRAY['business_object_id','business_object_version'] FROM dh_modeling_concept_entity e WHERE id=997731")
    unchanged_revisions = sql(db, "SELECT jsonb_agg(jsonb_build_array(id,xmin::text) ORDER BY id) FROM dh_modeling_logic_model WHERE id IN (997788,997789)")
    sql(db, """INSERT INTO system_menu(id,name,type,parent_id,path,component) SELECT 997771,'其他功能页面',2,id,'unrelated','fixture/unrelated'
        FROM system_menu WHERE permission='modeling:business-process:query'""")
    sql(db, file=migration, expected="包含其他页面或目录")
    assert sql(db, "SELECT data_domain_id IS NULL FROM dh_modeling_logic_model WHERE id=997722") == "t"
    assert sql(db, "SELECT to_regclass('dh_modeling_business_process') IS NOT NULL") == "t"
    sql(db, "DELETE FROM system_menu WHERE id=997771")
    passed("unexpected_child_page_blocks_whole_transaction")
    sql(db, "CREATE VIEW fixture_process_dependency AS SELECT id FROM dh_modeling_business_process")
    sql(db, file=migration, expected="fixture_process_dependency")
    assert sql(db, "SELECT count(*) FROM system_menu WHERE permission='modeling:business-process:query'") == "1"
    assert sql(db, "SELECT data_domain_id IS NULL FROM dh_modeling_logic_model WHERE id=997722") == "t"
    sql(db, "DROP VIEW fixture_process_dependency")
    passed("unknown_database_dependency_blocks_without_cascade")
    sql(db, file=migration)
    assert_final(db)
    passed("tables_sequences_columns_permissions_and_dangling_functions_removed")
    assert sql(db, "SELECT data_domain_id FROM dh_modeling_concept_model WHERE id=997721") == "997701"
    assert sql(db, "SELECT data_domain_id FROM dh_modeling_logic_model WHERE id=997722") == "997701"
    assert sql(db, "SELECT data_domain_id FROM dh_modeling_logic_model WHERE id=997723") == "997702"
    assert sql(db, "SELECT count(*) FROM dh_modeling_logic_model WHERE id IN (997724,997725,997726) AND data_domain_id IS NULL") == "3"
    assert sql(db, "SELECT count(*) FROM dh_modeling_logic_model WHERE id IN (997722,997723,997780,997789) AND classification_status='CONFIRMED'") == "4"
    assert sql(db, "SELECT count(*) FROM dh_modeling_logic_model WHERE id IN (997724,997725,997726,997781,997782,997783,997784,997785,997786,997787) AND classification_status='UNCLASSIFIED'") == "10"
    assert unchanged_revisions == sql(db, "SELECT jsonb_agg(jsonb_build_array(id,xmin::text) ORDER BY id) FROM dh_modeling_logic_model WHERE id IN (997788,997789)")
    passed("domain_backfill_and_classification_match_direct_ownership_without_touching_unchanged_or_deleted_rows")
    assert preserved == sql(db, preserved_query)
    assert entity == sql(db, "SELECT to_jsonb(e) FROM dh_modeling_concept_entity e WHERE id=997731")
    passed("entities_fields_snapshots_evidence_and_remaining_planning_rows_unchanged")
    assert json.loads(sql(db, "SELECT menu_ids FROM system_tenant_package WHERE id=997751")) == [6027]
    assert sql(db, "SELECT count(*) FROM system_role_menu WHERE id=997752") == "0"
    assert sql(db, "SELECT count(*) FROM system_menu WHERE id=997754") == "0"
    assert sql(db, "SELECT count(*) FROM system_dict_type WHERE id=997761") == "0"
    assert sql(db, "SELECT count(*) FROM system_dict_data WHERE id=997762") == "0"
    passed("role_package_dictionary_cleanup_keeps_business_architecture_parent")
    sql(db, "UPDATE dh_modeling_logic_model SET data_domain_id=997704 WHERE id=997722", expected="不属于当前租户")
    sql(db, "UPDATE dh_modeling_logic_model SET subject_domain_id=99999999 WHERE id=997722", expected="模型归属不存在")
    sql(db, "DELETE FROM dh_modeling_data_domain WHERE id=997701", expected="仍被模型引用")
    sql(db, "DELETE FROM dh_modeling_subject_domain WHERE id=997705", expected="仍被模型引用")
    passed("remaining_domain_subject_tenant_and_delete_guards_still_enforced")
    state_before = sql(db, "SELECT jsonb_agg(to_jsonb(s) ORDER BY migration_key) FROM dh_modeling_unification_state s")
    package_before = sql(db, "SELECT to_jsonb(p) FROM system_tenant_package p WHERE id=997751")
    sql(db, file=migration)
    assert_final(db)
    assert state_before == sql(db, "SELECT jsonb_agg(to_jsonb(s) ORDER BY migration_key) FROM dh_modeling_unification_state s")
    assert package_before == sql(db, "SELECT to_jsonb(p) FROM system_tenant_package p WHERE id=997751")
    assert preserved == sql(db, preserved_query)
    passed("replay_preserves_first_results_and_history")
    if args.verify_baseline:
        fresh = prefix + "_fresh"
        command(["createdb", *flags, fresh])
        sql(fresh, file=root / "sql/postgresql/datahub-center.sql")
        assert_final(fresh)
        passed("complete_fresh_baseline_reaches_same_final_structure")
    print(f"PASS {count} checks; isolated databases retained: {prefix}_*", flush=True)


if __name__ == "__main__":
    main()
