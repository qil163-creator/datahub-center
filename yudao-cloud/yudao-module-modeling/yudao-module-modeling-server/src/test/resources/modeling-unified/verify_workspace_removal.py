"""只验证历史 0126：克隆显式本地 0125 空测试库，不修改源库。

引用回执/变更夹具用于证明 0126 发布时的归档与阻断行为；不适用于当前新库基线。
"""
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
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.source_db):
        parser.error("源库名称必须是普通标识符")
    root = next(p for p in Path(__file__).resolve().parents if (p / "pom.xml").exists() and (p / "sql").is_dir())
    migration = root / "sql/postgresql/migrations/0126_modeling_remove-workspace.sql"
    flags = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    env = dict(os.environ, PGCLIENTENCODING="UTF8")
    prefix = "modeling_remove_" + uuid.uuid4().hex[:10]
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
            with tempfile.TemporaryDirectory(prefix="modeling-removal-sql-") as temp:
                statement = Path(temp) / "statement.sql"
                statement.write_text('SET search_path="datahub-center",pg_catalog; ' + content, encoding="utf-8")
                return command(argv + ["-f", str(statement)], expected)
        if file is not None:
            argv += ["-f", str(file)]
        return command(argv, expected)

    def clone(suffix):
        db = prefix + "_" + suffix
        command(["createdb", *flags, "-T", args.source_db, db])
        return db

    def passed(name):
        nonlocal count
        count += 1
        print("PASS " + name, flush=True)

    db = clone("positive")
    if sql(db, "SELECT count(*) FROM dh_modeling_physical_model") != "0":
        raise RuntimeError("必须使用明确的 0125 空测试库，不能对业务快照写固定编号夹具")
    fixtures = """
    INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,status,table_type) VALUES(990001,7,'退役验证设计','fixture_removal','DRAFT','DETAIL');
    INSERT INTO dh_modeling_physical_model(id,tenant_id,logic_model_id,database_id,table_name,status) VALUES(990002,7,990001,990003,'fixture_target','DRAFT');
    INSERT INTO dh_modeling_logic_model_version(id,tenant_id,model_id,version,status,fields) VALUES(990004,7,990001,1,'REVISING','[]');
    INSERT INTO dh_modeling_collaboration(id,tenant_id,kind,model_id,owner_user_id,visibility) VALUES(990010,7,'LOGIC',990001,42,'RESTRICTED');
    INSERT INTO dh_modeling_collaborator(id,tenant_id,kind,model_id,principal_type,principal_id,actions) VALUES(990011,7,'LOGIC',990001,'USER',42,'READ,EDIT');
    INSERT INTO dh_modeling_approval(id,tenant_id,kind,model_id,version_no,digest,snapshot,business_key,process_definition_key,applicant_id,status,reason)
      VALUES(990012,7,'LOGIC',990001,1,repeat('a',64),'{}','fixture_removal','fixture',42,'CANCELLED','测试正式撤回');
    INSERT INTO dh_modeling_design_report(id,tenant_id,kind,model_id,digest,passed,report) VALUES(990013,7,'LOGIC',990001,repeat('a',64),true,'{}');
    INSERT INTO dh_modeling_object_change(id,tenant_id,business_object_id,version_no,model_id,entity_id,summary,status) VALUES(990014,7,1,1,1,1,'待投递原文','PENDING');
    INSERT INTO dh_modeling_inbox(id,tenant_id,event_id,recipient_user_id,business_object_id,version_no,model_id,entity_id,summary,status,delivered_at)
      VALUES(990015,7,990014,42,1,1,1,1,'已送达原文','READ',CURRENT_TIMESTAMP);
    INSERT INTO dh_modeling_reference_receipt(tenant_id,operation_id,purpose,state) VALUES(7,'fixture-finished','fixture','FINISHED');
    INSERT INTO system_role_menu(id,tenant_id,role_id,menu_id) SELECT 990016,7,990017,id FROM system_menu WHERE permission='modeling:workspace:approval';
    INSERT INTO system_tenant_package(id,name,status,menu_ids) SELECT 990018,'退役验证套餐',0,jsonb_build_array(id,99999999)::text FROM system_menu WHERE permission='modeling:workspace:approval';
    """
    sql(db, fixtures)
    tables = ["dh_modeling_collaboration", "dh_modeling_collaborator", "dh_modeling_approval", "dh_modeling_design_report", "dh_modeling_object_change", "dh_modeling_inbox", "dh_modeling_reference_receipt"]
    before = {t: json.loads(sql(db, f"SELECT jsonb_agg(to_jsonb(t)) FROM {t} t")) for t in tables}
    preserved = sql(db, "SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(t)) FROM dh_modeling_unification_map t),(SELECT jsonb_agg(to_jsonb(t)) FROM dh_modeling_physical_model t),(SELECT jsonb_agg(to_jsonb(t)) FROM dh_modeling_logic_model_version t))")
    sql(db, file=migration)
    for table in tables:
        assert sql(db, f"SELECT to_regclass('{table}') IS NULL") == "t"
        images = json.loads(sql(db, f"SELECT jsonb_agg(row_image) FROM dh_modeling_workspace_removal_archive WHERE source_table='{table}'"))
        assert before[table] == images, table
    assert preserved == sql(db, "SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(t)) FROM dh_modeling_unification_map t),(SELECT jsonb_agg(to_jsonb(t)) FROM dh_modeling_physical_model t),(SELECT jsonb_agg(to_jsonb(t)) FROM dh_modeling_logic_model_version t))")
    assert sql(db, "SELECT layout_revision FROM dh_modeling_logic_model WHERE id=990001") == "0"
    passed("seven_tables_archived_exactly_and_model_ids_versions_preserved")
    assert sql(db, "SELECT count(*) FROM system_menu WHERE permission IN ('modeling:workspace:approval','modeling:workspace:collaboration','modeling:workspace:inbox')") == "0"
    assert sql(db, "SELECT count(*) FROM system_role_menu WHERE id=990016") == "0"
    assert json.loads(sql(db, "SELECT menu_ids FROM system_tenant_package WHERE id=990018")) == [99999999]
    assert sql(db, "SELECT count(*) FROM dh_modeling_workspace_removal_archive WHERE source_table='system_role_menu' AND row_key='7:990016'") == "1"
    passed("removed_permissions_roles_packages_archived_without_grant_expansion")
    archive = sql(db, "SELECT md5(jsonb_agg(to_jsonb(a) ORDER BY source_table,row_key)::text) FROM dh_modeling_workspace_removal_archive a")
    sql(db, file=migration)
    assert archive == sql(db, "SELECT md5(jsonb_agg(to_jsonb(a) ORDER BY source_table,row_key)::text) FROM dh_modeling_workspace_removal_archive a")
    passed("replay_preserves_first_archive")
    for statement in ["UPDATE dh_modeling_workspace_removal_archive SET row_image='{}'", "DELETE FROM dh_modeling_workspace_removal_archive", "TRUNCATE dh_modeling_workspace_removal_archive", "INSERT INTO dh_modeling_workspace_removal_archive VALUES('fake','fake','{}','fake',CURRENT_TIMESTAMP)"]:
        sql(db, statement, expected="工作台退役原始证据禁止")
    passed("archive_cannot_be_forged_or_overwritten")
    duplicate = "INSERT INTO dh_modeling_physical_model(id,tenant_id,logic_model_id,database_id,table_name,status) VALUES(990005,7,990001,990003,'fixture_target','DRAFT')"
    sql(db, duplicate, expected="uq_modeling_active_deploy_target")
    passed("database_unique_constraint_rejects_competing_target")
    for suffix, setup, expected in [
        ("approval", "INSERT INTO dh_modeling_approval(id,tenant_id,kind,model_id,version_no,digest,snapshot,business_key,process_definition_key,applicant_id,status,reason) VALUES(990012,7,'LOGIC',990001,1,repeat('a',64),'{}','fixture','fixture',42,'RUNNING','未结束')", "未终结建模审批"),
        ("mutation", "INSERT INTO dh_dataforge_reference_mutation(tenant_id,operation_id,owner,state,revision,purpose,request_hash) VALUES(7,'fixture-active','modeling','ACTIVE',0,'fixture','fixture')", "活动建模引用变更"),
        ("duplicate", "INSERT INTO dh_modeling_logic_model(id,tenant_id,name,code,status,table_type) VALUES(990001,7,'设计','fixture','DRAFT','DETAIL'); INSERT INTO dh_modeling_physical_model(id,tenant_id,logic_model_id,database_id,table_name,status) VALUES(990002,7,990001,990003,'fixture_target','DRAFT'),(990005,7,990001,990003,'fixture_target','DRAFT')", "部署目标存在重复绑定"),
    ]:
        negative = clone(suffix)
        sql(negative, setup)
        sql(negative, file=migration, expected=expected)
        assert sql(negative, "SELECT to_regclass('dh_modeling_approval') IS NOT NULL AND to_regclass('dh_modeling_workspace_removal_archive') IS NULL") == "t"
        passed(suffix + "_blocks_atomically")
    print(f"PASS {count} checks; isolated databases retained: {prefix}_*", flush=True)


if __name__ == "__main__":
    main()
