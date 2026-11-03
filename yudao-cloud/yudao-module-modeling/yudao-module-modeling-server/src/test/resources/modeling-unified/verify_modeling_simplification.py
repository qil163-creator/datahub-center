"""仅在本机 PG18 唯一克隆库验证 0131；模板只读，保留克隆供复核。"""
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
    parser.add_argument('--port', required=True, type=int)
    parser.add_argument('--source-db', required=True)
    parser.add_argument('--populated-source-db', required=True)
    parser.add_argument('--pg-bin', type=Path, required=True)
    parser.add_argument('--user', default='postgres')
    args = parser.parse_args()
    sources = {args.source_db, args.populated_source_db}
    if any(not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,62}', name) for name in sources):
        parser.error('模板名称须为普通标识符')
    here = Path(__file__).resolve().parent
    repo = next(p for p in here.parents if (p / 'pom.xml').is_file() and (p / 'sql').is_dir())
    files = {n: next((repo / 'sql/postgresql/migrations').glob(f'{n:04d}_*.sql')) for n in (128, 131)}
    original = {n: hashlib.sha256(p.read_bytes()).hexdigest() for n, p in files.items()}
    prefix = 'modeling_slim_' + uuid.uuid4().hex[:10]
    clones: list[str] = []
    flags = ['-h', '127.0.0.1', '-p', str(args.port), '-U', args.user]
    env = dict(os.environ, PGCLIENTENCODING='UTF8', PGCONNECT_TIMEOUT='10')
    checks = 0

    def binary(name: str) -> str:
        return str(args.pg_bin / (name + ('.exe' if os.name == 'nt' else '')))

    def run(argv: list[str], expected: str | None = None) -> str:
        result = subprocess.run(argv, capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None:
            if result.returncode == 0 or expected not in result.stderr:
                raise RuntimeError('未按预期拒绝：' + result.stderr)
            print('EXPECTED REJECTION ' + expected, flush=True)
        return result.stdout.strip()

    def sql(db: str, content: str = '', file: Path | None = None, expected: str | None = None, read_only: bool = False) -> str:
        if db not in clones and (not read_only or db not in sources):
            raise RuntimeError('只能写入本次创建的独立测试克隆')
        if read_only and file:
            raise RuntimeError('源模板不得执行脚本')
        prelude = 'SET search_path="datahub-center",pg_catalog;\n' + ('BEGIN READ ONLY;\n' if read_only else '')
        with tempfile.TemporaryDirectory(prefix='modeling-slim-sql-') as temp:
            script = Path(temp) / 'statement.sql'
            script.write_text(prelude + content + ('\nCOMMIT;' if read_only else ''), encoding='utf-8')
            argv = [binary('psql'), *flags, '-d', db, '-X', '-Atq', '-v', 'ON_ERROR_STOP=1', '-f', str(script)]
            if file:
                argv += ['-f', str(file)]
            return run(argv, expected)

    def clone(source: str, suffix: str) -> str:
        if source not in sources and source not in clones:
            raise RuntimeError('克隆来源未授权')
        db = prefix + '_' + suffix
        run([binary('createdb'), *flags, '-T', source, db])
        clones.append(db)
        print('CLONE ' + db, flush=True)
        return db

    def passed(name: str) -> None:
        nonlocal checks
        checks += 1
        print('PASS ' + name, flush=True)

    def digest(db: str, table: str, omitted: tuple[str, ...] = ()) -> str:
        assert re.fullmatch(r'[a-z_]+', table)
        image = 'to_jsonb(t)'
        if omitted:
            image += '-ARRAY[' + ','.join("'" + col + "'" for col in omitted) + ']'
        return sql(db, f"SELECT md5(COALESCE(jsonb_agg({image} ORDER BY ({image})::text)::text,'[]')) FROM {table} t;")

    def image(db: str) -> str:
        # pg_dump 的 schema-only 排除易变时间，数据用稳定排序摘要；整体回滚需同时比较两者。
        tables = sql(db, "SELECT tablename FROM pg_tables WHERE schemaname='datahub-center' AND (tablename LIKE 'dh_modeling_%' OR tablename IN('system_menu','system_role_menu','system_tenant_package','system_dict_type','system_dict_data')) ORDER BY tablename;").splitlines()
        rows = {table: digest(db, table) for table in tables}
        ddl = run([binary('pg_dump'), *flags, '-d', db, '--schema-only', '--schema=datahub-center', '--no-owner', '--no-privileges'])
        ddl = '\n'.join(line for line in ddl.splitlines() if not line.startswith(('\\restrict ', '\\unrestrict ', '--')))
        return json.dumps([rows, ddl], ensure_ascii=False, sort_keys=True)

    def assert_final(db: str) -> None:
        assert sql(db, "SELECT count(*) FROM pg_tables WHERE schemaname='datahub-center' AND tablename LIKE 'dh_modeling_%';") == '18'
        assert sql(db, "SELECT phase FROM dh_modeling_unification_state WHERE migration_key='MODEL_SIMPLIFICATION_V1';") == 'CLEANED'
        assert sql(db, "SELECT to_regclass('dh_modeling_schema_baseline') IS NULL;") == 't'
        assert sql(db, "SELECT count(*) FROM system_menu WHERE permission IN('modeling:model:check-config','modeling:workspace:environment');") == '0'
        assert sql(db, "SELECT count(*) FROM system_role_menu r LEFT JOIN system_menu m ON m.id=r.menu_id WHERE m.id IS NULL AND r.menu_id IN(6701,6706,6707,6708,6720,6721,6723,6724,6725,6930,6945);") == '0'
        assert sql(db, "SELECT count(*) FROM system_menu WHERE permission='modeling:model:check' AND name='检查模型设计';") == '1'
        assert sql(db, "SELECT count(*) FROM system_menu WHERE component='modeling/subjectdomain/index' AND name='分析主题';") == '1'
        assert sql(db, "SELECT data_type FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_modeling_logic_model' AND column_name='layout_revision';") == 'bigint'
        assert sql(db, "SELECT count(*) FROM pg_index WHERE indexrelid=to_regclass('uq_modeling_active_deploy_target') AND indisunique AND indisvalid AND indpred IS NOT NULL;") == '1'

    def replay(db: str) -> None:
        assert_final(db)
        old = image(db)
        sql(db, file=files[131])
        assert image(db) == old, '重复执行改写原行或最终结构'

    assert int(sql(args.source_db, 'SHOW server_version_num;', read_only=True)) // 10000 == 18
    assert sql(args.source_db, "SELECT to_regclass('dh_modeling_unification_state') IS NULL;", read_only=True) == 't'

    populated = clone(args.populated_source_db, 'populated')
    category_menus_sql = "WITH menus AS (SELECT * FROM system_menu WHERE permission LIKE 'modeling:biz-category:%' OR component='modeling/bizcategory/index') SELECT jsonb_build_object('menus',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM menus m),'roles',(SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM system_role_menu r WHERE menu_id IN(SELECT id FROM menus)));"
    category_menus = sql(populated, category_menus_sql)
    baseline_rows = sql(populated, "SELECT jsonb_agg(to_jsonb(b) ORDER BY tenant_id,id) FROM dh_modeling_schema_baseline b;")
    version_tables = ('dh_modeling_logic_model_version', 'dh_modeling_physical_model_version', 'dh_modeling_logic_relation',
                      'dh_modeling_physical_model', 'dh_modeling_unification_map', 'dh_modeling_unified_menu_backup',
                      'dh_modeling_workspace_removal_archive', 'dh_modeling_legacy_adaptation_run',
                      'dh_modeling_legacy_adaptation_backup', 'dh_modeling_legacy_adaptation_issue', 'dh_modeling_biz_category')
    original_rows = {table: digest(populated, table) for table in version_tables}
    deployed = digest(populated, 'dh_modeling_deploy_record', ('observed_schema_snapshot', 'baseline_saved_at', 'baseline_status'))
    sql(populated, file=files[131])
    assert {table: digest(populated, table) for table in version_tables} == original_rows
    assert sql(populated, category_menus_sql) == category_menus
    assert deployed == digest(populated, 'dh_modeling_deploy_record', ('observed_schema_snapshot', 'baseline_saved_at', 'baseline_status'))
    assert sql(populated, "SELECT migration_evidence->'legacyBaselines' FROM dh_modeling_unification_state WHERE migration_key='MODEL_SIMPLIFICATION_V1';") == baseline_rows
    assert sql(populated, "SELECT observed_schema_snapshot IS NOT NULL FROM dh_modeling_deploy_record WHERE id=2;") == 't'
    assert sql(populated, "SELECT observed_schema_snapshot IS NULL FROM dh_modeling_deploy_record WHERE id=1;") == 't'
    replay(populated)
    passed('completed_0130_keeps_core_versions_evidence_and_merges_only_proven_baselines')

    seed = clone(args.source_db, 'seed')
    sql(seed, "SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';", file=files[128])
    sql(seed, "INSERT INTO dh_modeling_model_check(id,task_status,tenant_id) VALUES(99793101,'RUNNING',99793101),(99793102,'QUEUED',99793102); INSERT INTO dh_modeling_model_check_result(id,check_id,model_id,tenant_id) VALUES(99793101,99793101,99793101,99793101);")
    inspection = sql(seed, "SELECT jsonb_object_agg(t,rows) FROM (SELECT 'dh_modeling_model_check' AS t,jsonb_agg(to_jsonb(c) ORDER BY tenant_id,id) AS rows FROM dh_modeling_model_check c UNION ALL SELECT 'dh_modeling_model_check_result',jsonb_agg(to_jsonb(c) ORDER BY tenant_id,id) FROM dh_modeling_model_check_result c UNION ALL SELECT 'dh_modeling_model_check_config',COALESCE(jsonb_agg(to_jsonb(c) ORDER BY tenant_id,id),'[]'::jsonb) FROM dh_modeling_model_check_config c) q;")
    direct = clone(seed, 'old_complete')
    sql(direct, file=files[131])
    assert sql(direct, "SELECT count(*) FROM dh_modeling_unification_state WHERE migration_key IN('UNIFIED_MODEL_V1','WORKSPACE_REMOVAL_V1','BUSINESS_PLANNING_REMOVAL_V1') AND phase='CLEANED';") == '3'
    assert sql(direct, "SELECT migration_evidence->'retiredInspectionRows' FROM dh_modeling_unification_state WHERE migration_key='MODEL_SIMPLIFICATION_V1';") == inspection
    replay(direct)
    passed('old_complete_empty_schema_runs_real_embedded_preparation_without_manual_sequence')

    partial = clone(seed, 'empty_partial')
    sql(partial, 'DROP TABLE dh_modeling_collaboration; ALTER TABLE dh_modeling_physical_model DROP COLUMN name;')
    sql(partial, file=files[131])
    assert sql(partial, "SELECT count(*) FROM dh_modeling_unification_state WHERE migration_key IN('UNIFIED_MODEL_V1','WORKSPACE_REMOVAL_V1','BUSINESS_PLANNING_REMOVAL_V1');") == '0'
    assert sql(partial, "SELECT phase FROM dh_modeling_unification_state WHERE migration_key='EMPTY_MODEL_FINALIZATION_V1';") == 'CLEANED'
    assert sql(partial, "SELECT migration_evidence->'retiredInspectionRows' FROM dh_modeling_unification_state WHERE migration_key='MODEL_SIMPLIFICATION_V1';") == inspection
    replay(partial)
    passed('verified_empty_partial_schema_finalizes_without_fabricating_historical_markers')

    orphan_history = clone(seed, 'empty_orphan_history')
    deploy_json = sql(args.populated_source_db, "SELECT jsonb_agg(to_jsonb(d) ORDER BY tenant_id,id) FROM dh_modeling_deploy_record d;", read_only=True)
    old_baselines = sql(args.populated_source_db, "SELECT jsonb_agg(to_jsonb(b) ORDER BY tenant_id,id) FROM dh_modeling_schema_baseline b;", read_only=True)
    sql(orphan_history, "DROP TABLE dh_modeling_collaboration; ALTER TABLE dh_modeling_physical_model DROP COLUMN name; INSERT INTO dh_modeling_deploy_record SELECT * FROM jsonb_populate_recordset(NULL::dh_modeling_deploy_record,'" + deploy_json.replace("'", "''") + "'::jsonb); INSERT INTO dh_modeling_schema_baseline SELECT * FROM jsonb_populate_recordset(NULL::dh_modeling_schema_baseline,'" + old_baselines.replace("'", "''") + "'::jsonb);")
    sql(orphan_history, "UPDATE dh_modeling_deploy_record SET status='UNKNOWN' WHERE id=2;")
    old = image(orphan_history)
    sql(orphan_history, file=files[131], expected='孤儿部署仍有未结束状态')
    assert image(orphan_history) == old
    sql(orphan_history, "UPDATE dh_modeling_deploy_record SET status='SUCCEEDED' WHERE id=2;")
    orphan_digest = digest(orphan_history, 'dh_modeling_deploy_record', ('observed_schema_snapshot',))
    sql(orphan_history, file=files[131])
    assert digest(orphan_history, 'dh_modeling_deploy_record', ('observed_schema_snapshot',)) == orphan_digest
    assert sql(orphan_history, "SELECT migration_evidence->'legacyBaselines' FROM dh_modeling_unification_state WHERE migration_key='MODEL_SIMPLIFICATION_V1';") == old_baselines
    assert sql(orphan_history, "SELECT migration_evidence->>'retainedOrphanDeploymentCount' FROM dh_modeling_unification_state WHERE migration_key='EMPTY_MODEL_FINALIZATION_V1';") == '2'
    assert sql(orphan_history, 'SELECT count(*) FROM dh_modeling_logic_model;') == '0'
    assert sql(orphan_history, 'SELECT count(*) FROM dh_modeling_deploy_record WHERE observed_schema_snapshot IS NOT NULL;') == '0'
    replay(orphan_history)
    passed('empty_design_preserves_terminal_orphan_deployments_and_baselines_but_rejects_unknown_results')

    conflict = clone(seed, 'ledger_conflict')
    sql(conflict, "CREATE TABLE dh_modeling_unification_state(migration_key varchar(32) PRIMARY KEY,phase varchar(20),operator_name varchar(128),create_time timestamp DEFAULT CURRENT_TIMESTAMP,update_time timestamp DEFAULT CURRENT_TIMESTAMP); INSERT INTO dh_modeling_unification_state(migration_key,phase,operator_name) VALUES('WORKSPACE_REMOVAL_V1','CLEANED',current_user);")
    old = image(conflict)
    sql(conflict, file=files[131], expected='历史账本不一致')
    assert image(conflict) == old
    passed('inconsistent_history_is_rejected_without_synthetic_unified_completion')

    unsafe = clone(args.populated_source_db, 'unproved_baseline')
    sql(unsafe, 'UPDATE dh_modeling_deploy_record SET observed_schema_hash=NULL WHERE id=1;')
    old = image(unsafe)
    sql(unsafe, file=files[131], expected='禁止丢弃保护语义')
    assert image(unsafe) == old
    passed('active_target_without_verifiable_baseline_blocks_atomically')

    mismatch = clone(args.populated_source_db, 'snapshot_conflict')
    sql(mismatch, "ALTER TABLE dh_modeling_deploy_record ADD COLUMN observed_schema_snapshot text; UPDATE dh_modeling_deploy_record SET observed_schema_snapshot='[{\"fieldName\":\"conflicting\",\"dataType\":\"varchar\"}]' WHERE id=2;")
    old = image(mismatch)
    sql(mismatch, file=files[131], expected='成功部署已有快照与旧基线不一致')
    assert image(mismatch) == old
    passed('existing_snapshot_conflict_cannot_be_overwritten')

    orphan = clone(args.populated_source_db, 'orphan_baseline')
    sql(orphan, "INSERT INTO dh_modeling_schema_baseline(id,model_id,version_no,table_name,fields,tenant_id,database_id) VALUES(99793100,99793100,1,'orphan','[]',7,99793100);")
    sql(orphan, file=files[131])
    assert sql(orphan, "SELECT count(*) FROM dh_modeling_unification_state, jsonb_array_elements(migration_evidence->'legacyBaselines') b WHERE migration_key='MODEL_SIMPLIFICATION_V1' AND b->>'id'='99793100';") == '1'
    assert sql(orphan, 'SELECT count(*) FROM dh_modeling_deploy_record WHERE model_id=99793100;') == '0'
    passed('orphan_test_baseline_is_archived_without_fabricating_successful_deployment')

    invalid = clone(args.populated_source_db, 'invalid_fields')
    sql(invalid, "UPDATE dh_modeling_schema_baseline SET fields='[]' WHERE id=3; UPDATE dh_modeling_schema_baseline SET fields='[{\"fieldName\":\"id\",\"dataType\":\"varchar\"},{\"fieldName\":\"ID\",\"dataType\":\"int\"}]' WHERE id=1;")
    sql(invalid, file=files[131])
    assert sql(invalid, 'SELECT observed_schema_snapshot IS NULL FROM dh_modeling_deploy_record WHERE id=2;') == 't'
    assert sql(invalid, "SELECT migration_evidence->'baselineMergeSummary'->>'archivedOnly' FROM dh_modeling_unification_state WHERE migration_key='MODEL_SIMPLIFICATION_V1';") == '2'
    corrupt = clone(args.populated_source_db, 'corrupt_snapshot')
    sql(corrupt, "ALTER TABLE dh_modeling_deploy_record ADD COLUMN observed_schema_snapshot text; UPDATE dh_modeling_deploy_record SET observed_schema_snapshot='[]' WHERE id=2;")
    old = image(corrupt)
    sql(corrupt, file=files[131], expected='成功部署已有快照无法解码')
    assert image(corrupt) == old
    passed('invalid_or_empty_legacy_fields_never_replace_valid_hash_and_bad_existing_snapshot_is_rejected')

    whitespace = clone(args.populated_source_db, 'whitespace_snapshot')
    sql(whitespace, "ALTER TABLE dh_modeling_deploy_record ADD COLUMN observed_schema_snapshot text; UPDATE dh_modeling_deploy_record SET observed_schema_snapshot='   ' WHERE id=2;")
    old = image(whitespace)
    sql(whitespace, file=files[131], expected='成功部署已有快照无法解码')
    assert image(whitespace) == old
    passed('non_null_whitespace_snapshot_cannot_fall_back_to_valid_legacy_hash')

    unknown = clone(args.populated_source_db, 'unknown_deploy')
    sql(unknown, "UPDATE dh_modeling_deploy_record SET status='UNKNOWN' WHERE id=2;")
    old = image(unknown)
    sql(unknown, file=files[131], expected='部署、可靠登记或基线保存尚未完成')
    assert image(unknown) == old
    passed('retired_inspection_does_not_relax_unknown_real_deployment_guard')

    no_success = clone(args.populated_source_db, 'no_success')
    sql(no_success, "INSERT INTO dh_modeling_schema_baseline(id,model_id,version_no,table_name,fields,tenant_id,database_id) VALUES(99793101,2,1,'dddd','[{\"fieldName\":\"id\",\"dataType\":\"varchar\"}]',1,1);")
    old = image(no_success)
    sql(no_success, file=files[131], expected='禁止丢弃保护语义')
    assert image(no_success) == old
    passed('active_target_baseline_without_any_successful_deployment_blocks_atomically')

    fresh = prefix + '_fresh'
    run([binary('createdb'), *flags, '-T', 'template0', fresh])
    clones.append(fresh)
    print('CLONE ' + fresh + ' from PostgreSQL empty template0', flush=True)
    sql(fresh, file=repo / 'sql/postgresql/datahub-center.sql')
    replay(fresh)
    passed('fresh_full_baseline_has_final_18_tables_and_supports_0131_replay')

    sql(populated, "UPDATE dh_modeling_unification_state SET migration_evidence='{}' WHERE migration_key='MODEL_SIMPLIFICATION_V1';", expected='原始迁移证据不可修改')
    sql(populated, "TRUNCATE dh_modeling_unification_state;", expected='原始迁移证据不可修改')
    sql(populated, "UPDATE dh_modeling_logic_model SET data_domain_id=999999999 WHERE id=3;", expected='模型归属不存在或不属于当前租户')
    sql(populated, "UPDATE dh_modeling_logic_model_version SET fields='[]' WHERE status='PUBLISHED';", expected='已发布统一设计内容不可修改')
    sql(populated, "INSERT INTO dh_modeling_biz_category(id,name,code,tenant_id,creator) VALUES(99793111,'分类精简验证','VERIFY_0131_CATEGORY',1,'100'); UPDATE dh_modeling_biz_category SET responsibility_scope='范围更新',updater='100',update_time=CURRENT_TIMESTAMP WHERE id=99793111; DELETE FROM dh_modeling_biz_category WHERE id=99793111;")
    assert sql(populated, "SELECT count(*) FROM dh_modeling_planning_change WHERE kind='BIZ_CATEGORY' AND object_id=99793111;") == '3'
    assert sql(populated, "SELECT count(*) FROM dh_modeling_planning_change WHERE kind='BIZ_CATEGORY' AND object_id=99793111 AND action='UPDATE' AND changes ? 'responsibility_scope';") == '1'
    passed('final_ownership_frozen_versions_and_migration_evidence_guards_remain_enforced')

    assert original == {n: hashlib.sha256(p.read_bytes()).hexdigest() for n, p in files.items()}, '运行期间迁移文件变化'
    print(f'PASS {checks} 检查组；0131 SHA256={original[131]}', flush=True)
    print('克隆保留：' + ', '.join(clones), flush=True)


if __name__ == '__main__':
    main()
