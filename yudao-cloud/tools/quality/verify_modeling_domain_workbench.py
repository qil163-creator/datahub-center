"""使用独立临时 PostgreSQL 实例验证数据板块、主题域迁移，不读取业务库连接配置。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sql/postgresql/migrations/0205_modeling_domain-workbench.sql"
OWNERSHIP_MIGRATION = ROOT / "sql/postgresql/migrations/0206_modeling_subject-board-ownership-guard.sql"
ENTITY_MIGRATION = ROOT / "sql/postgresql/migrations/0207_modeling_business-entity-workbench.sql"
BASELINE = ROOT / "sql/postgresql/datahub-center.sql"
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'


def verify_business_entities(sql, value, expect_error, snapshot, migration: str, baseline: str, port: int) -> None:
    """验证独立实体迁移、关系不变量及真实并发，保留已有规划和概念设计。"""
    from concurrent.futures import ThreadPoolExecutor
    import threading
    import psycopg2

    sql("UPDATE dh_modeling_concept_model SET status='ACTIVE',revision=revision+1 WHERE id=3")
    before_tables = snapshot()
    before_models = value("""SELECT jsonb_build_object(
        'logic',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM dh_modeling_logic_model m),
        'concept',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM dh_modeling_concept_model m),
        'subject',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM dh_modeling_subject_domain m),
        'board',(SELECT jsonb_agg(to_jsonb(m)-ARRAY['naming_rules','update_time'] ORDER BY id) FROM dh_modeling_data_domain m))""")
    old_rules = value("SELECT jsonb_object_agg(id,naming_rules) FROM dh_modeling_data_domain")
    sql("CREATE DATABASE custom_rules TEMPLATE upgrade; CREATE DATABASE entity_fresh;", "postgres")
    sql("UPDATE dh_modeling_data_domain SET naming_rules=jsonb_set(naming_rules,'{0,prefix}','\"local_\"'),update_time='2026-10-19 01:02:03' WHERE id=29", "custom_rules")
    sql(migration, "custom_rules")
    assert value("SELECT to_jsonb(naming_rules->0->>'prefix') FROM dh_modeling_data_domain WHERE id=29", "custom_rules") == "local_"
    assert value("SELECT to_jsonb(update_time) FROM dh_modeling_data_domain WHERE id=29", "custom_rules") == "2026-10-19T01:02:03"
    sql(migration)
    assert value("SELECT to_jsonb(update_time) FROM dh_modeling_data_domain WHERE id=29") == "2026-09-28T18:20:49"
    after_tables = snapshot()
    assert {k: v for k, v in after_tables.items() if k in before_tables} == before_tables, "0207 修改了其他业务表"
    after_models = value("""SELECT jsonb_build_object(
        'logic',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM dh_modeling_logic_model m),
        'concept',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM dh_modeling_concept_model m),
        'subject',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM dh_modeling_subject_domain m),
        'board',(SELECT jsonb_agg(to_jsonb(m)-ARRAY['naming_rules','update_time'] ORDER BY id) FROM dh_modeling_data_domain m))""")
    assert after_models == before_models, "0207 覆盖了现有模型或规划定义"
    rules = value("SELECT jsonb_object_agg(id,naming_rules) FROM dh_modeling_data_domain")
    for board_id, old in old_rules.items():
        current = {r["tableType"]: r for r in rules[board_id]}
        assert list(current) == ["ODS", "DIM", "FACT_PROCESS", "FACT_EVENT", "DWS", "ADS"]
        assert all(current[r["tableType"]] == r for r in old), "现有命名规则被覆盖"
    assert value("SELECT jsonb_build_array(count(*),count(*) FILTER(WHERE status=0),count(*) FILTER(WHERE status=1)) FROM dh_modeling_business_entity") == [35, 33, 2]
    assert value("SELECT jsonb_object_agg(entity_subtype,n) FROM (SELECT entity_subtype,count(*) n FROM dh_modeling_business_entity GROUP BY entity_subtype) q") == {"NORMAL": 16, "HIERARCHICAL": 2, "EVENT": 15, "PROCESS": 2}
    assert value("SELECT jsonb_object_agg(relation_type,n) FROM (SELECT relation_type,count(*) n FROM dh_modeling_business_entity_relation GROUP BY relation_type) q") == {"INHERITANCE": 3, "ASSOCIATION": 22, "PROCESS_EVENT": 7}
    assert value("SELECT jsonb_object_agg(id,n) FROM (SELECT source_entity_id id,jsonb_agg(target_entity_id ORDER BY sort_order) n FROM dh_modeling_business_entity_relation WHERE relation_type='PROCESS_EVENT' GROUP BY source_entity_id) q") == {"34": [29, 30, 32, 33], "75": [71, 73, 74]}
    assert value("SELECT jsonb_object_agg(data_domain_id,n) FROM (SELECT data_domain_id,jsonb_build_array(count(*) FILTER(WHERE entity_type='OBJECT'),count(*) FILTER(WHERE entity_type='ACTIVITY')) n FROM dh_modeling_business_entity WHERE deleted=0 GROUP BY data_domain_id) q") == {"12": [8, 7], "29": [10, 10]}
    assert value("SELECT count(*) FROM system_menu WHERE permission LIKE 'modeling:business-entity:%' AND deleted=0") == 5
    installed = value("SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM dh_modeling_business_entity e")
    rejected = sql(migration, required=False)
    assert rejected.returncode and "42P07" in rejected.stderr, rejected.stderr
    assert installed == value("SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM dh_modeling_business_entity e"), "0207 重放阻断失败"

    entity_columns = "id,data_domain_id,subject_domain_id,name,code,entity_type,entity_subtype"
    relation_columns = "source_entity_id,target_entity_id,relation_type"
    entity_insert = f"INSERT INTO dh_modeling_business_entity({entity_columns}) VALUES"
    relation_insert = f"INSERT INTO dh_modeling_business_entity_relation({relation_columns}) VALUES"
    failures = [
        (entity_insert + "(9001,12,76,'同名','customer','OBJECT','NORMAL')", "23505"),
        (entity_insert + "(9001,12,76,'错误类型','WRONG_TYPE','OBJECT','EVENT')", "23514"),
        (entity_insert + "(9001,12,113,'错误归属','WRONG_DOMAIN','OBJECT','NORMAL')", "23514"),
        (entity_insert + "(9001,12,76,'错误编码','1BAD','OBJECT','NORMAL')", "23514"),
        ("UPDATE dh_modeling_business_entity SET data_domain_id=29,subject_domain_id=113 WHERE id=22", "23514"),
        ("UPDATE dh_modeling_business_entity SET entity_subtype='HIERARCHICAL' WHERE id=22", "23514"),
        ("UPDATE dh_modeling_business_entity SET subject_domain_id=77 WHERE id=23", "23514"),
        ("UPDATE dh_modeling_business_entity SET deleted=1 WHERE id=22", "23514"),
        ("UPDATE dh_modeling_subject_domain SET deleted=1 WHERE id=76", "23514"),
        ("UPDATE dh_modeling_data_domain SET deleted=1 WHERE id=12", "23514"),
        (relation_insert + "(22,56,'ASSOCIATION')", "23514"),
        (relation_insert + "(25,22,'INHERITANCE')", "23514"),
        (relation_insert + "(24,22,'ASSOCIATION')", "23514"),
        (relation_insert + "(23,22,'ASSOCIATION')", "23514"),
        (relation_insert + "(49,23,'INHERITANCE')", "23505"),
        (relation_insert + "(25,24,'ASSOCIATION')", "23505"),
        (relation_insert + "(22,23,'INHERITANCE')", "23514"),
        (relation_insert + "(34,22,'PROCESS_EVENT')", "23514"),
        (relation_insert + "(34,28,'PREDECESSOR')", "23514"),
        ("BEGIN; " + entity_insert + "(9001,12,76,'不足两个事件','ONE_EVENT','ACTIVITY','PROCESS'); " + relation_insert + "(9001,28,'PROCESS_EVENT'); COMMIT", "23514"),
        ("BEGIN; " + entity_insert + "(9001,12,76,'事件独占','EVENT_EXCLUSIVE','ACTIVITY','PROCESS'); " + relation_insert + "(9001,29,'PROCESS_EVENT'); COMMIT", "23505"),
        ("BEGIN; DELETE FROM dh_modeling_business_entity_relation WHERE source_entity_id=34 AND relation_type='PROCESS_EVENT' AND target_entity_id<>29; COMMIT", "23514"),
    ]
    for statement, code in failures:
        expect_error(statement, code)
    sql("BEGIN; " + entity_insert + "(9001,12,76,'客户','DUPLICATE_NAME_ALLOWED','OBJECT','NORMAL'); "
        + relation_insert + "(34,22,'ASSOCIATION'); ROLLBACK")
    sql("BEGIN; UPDATE dh_modeling_business_entity_relation SET deleted=1 WHERE source_entity_id=49; "
        "UPDATE dh_modeling_business_entity SET subject_domain_id=77 WHERE id=49; "
        + relation_insert + "(49,25,'INHERITANCE'); SET CONSTRAINTS ALL IMMEDIATE; ROLLBACK")
    assert installed == value("SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM dh_modeling_business_entity e"), "失败或回滚的操作留下实体记录"

    # 构造第二个合法流程，两个连接同时占用同一事件，第二笔必须拒绝而非形成双重归属。
    sql("BEGIN; " + entity_insert + "(9001,12,76,'并发流程','RACE_PROCESS','ACTIVITY','PROCESS'),"
        "(9002,12,76,'事件甲','RACE_EVENT_A','ACTIVITY','EVENT'),(9003,12,76,'事件乙','RACE_EVENT_B','ACTIVITY','EVENT'),"
        "(9004,12,76,'后续流程','SUCCESSOR_PROCESS','ACTIVITY','PROCESS'),"
        "(9005,12,76,'事件丙','RACE_EVENT_C','ACTIVITY','EVENT'),(9006,12,76,'事件丁','RACE_EVENT_D','ACTIVITY','EVENT'); "
        + relation_insert + "(9001,9002,'PROCESS_EVENT'),(9001,9003,'PROCESS_EVENT'),(9004,9005,'PROCESS_EVENT'),(9004,9006,'PROCESS_EVENT'); COMMIT")
    expect_error("BEGIN; " + relation_insert + "(34,9001,'PREDECESSOR'),(9001,34,'PREDECESSOR'); COMMIT", "23514")
    expect_error("BEGIN; " + relation_insert + "(34,9001,'PREDECESSOR'),(9004,9001,'PREDECESSOR'); COMMIT", "23505")

    def connect():
        connection = psycopg2.connect(host="127.0.0.1", port=port, dbname="upgrade", user="domain_workbench_test")
        with connection.cursor() as cursor:
            cursor.execute(PREFIX + "SET lock_timeout='10s'; SET statement_timeout='15s';")
        return connection

    first = connect()
    entered = threading.Event()
    def second_writer():
        connection = connect()
        try:
            entered.set()
            with connection.cursor() as cursor:
                cursor.execute(relation_insert + "(9001,28,'PROCESS_EVENT')")
            connection.commit()
            return "unexpected-success"
        except psycopg2.Error as error:
            connection.rollback()
            return error.pgcode
        finally:
            connection.close()
    try:
        with first.cursor() as cursor:
            cursor.execute(relation_insert + "(34,28,'PROCESS_EVENT')")
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(second_writer)
            assert entered.wait(5)
            first.commit()
            assert future.result(timeout=20) == "23505", "并发占用未被唯一约束拒绝"
    finally:
        first.close()
    assert value("SELECT count(*) FROM dh_modeling_business_entity_relation WHERE target_entity_id=28 AND relation_type='PROCESS_EVENT' AND deleted=0") == 1
    sql("BEGIN; DELETE FROM dh_modeling_business_entity_relation WHERE source_entity_id IN (9001,9004) OR target_entity_id=28 AND relation_type='PROCESS_EVENT'; DELETE FROM dh_modeling_business_entity WHERE id IN (9001,9002,9003,9004,9005,9006); COMMIT")
    assert installed == value("SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM dh_modeling_business_entity e")

    sql(baseline, "entity_fresh")
    for query in (
        "SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM dh_modeling_business_entity e",
        "SELECT jsonb_agg(to_jsonb(r)-ARRAY['create_time','update_time'] ORDER BY id) FROM dh_modeling_business_entity_relation r",
        "SELECT jsonb_object_agg(id,jsonb_build_array(naming_rules,update_time)) FROM dh_modeling_data_domain",
        "SELECT jsonb_agg(to_jsonb(c) ORDER BY table_name,ordinal_position) FROM information_schema.columns c WHERE table_schema='datahub-center' AND table_name LIKE 'dh_modeling_business_entity%'",
        "SELECT jsonb_agg(to_jsonb(i) ORDER BY indexname) FROM pg_indexes i WHERE schemaname='datahub-center' AND tablename LIKE 'dh_modeling_business_entity%'",
    ):
        normalize = lambda result: re.sub(r'"(upgrade|entity_fresh)"', '"database"', json.dumps(result, sort_keys=True))
        assert normalize(value(query)) == normalize(value(query, "entity_fresh")), "独立实体新库基线与升级结果不一致"
    print(json.dumps({"migration": "0207", "entities": 35, "relations": 32, "invalid_operations": len(failures) + 2,
                      "concurrent_event_exclusivity": "passed", "preserved_existing_models": True,
                      "fresh_upgrade_equivalence": "passed", "local_naming_rules_preserved": True}, ensure_ascii=False))


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-bin", type=Path, help="包含 initdb、pg_ctl、psql 的 PostgreSQL 目录")
    parser.add_argument("--mapper-tests", action="store_true", help="在临时数据库上执行模型模块的契约、服务和真实 Mapper 测试")
    parser.add_argument("--mapper-test-name", action="append", help="仅执行指定测试类，可重复指定，需与 --mapper-tests 同用")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    binary = args.postgres_bin or Path(shutil.which("initdb") or "").parent
    for name in ("initdb", "pg_ctl", "psql"):
        if not (binary / (name + suffix)).is_file():
            raise SystemExit(f"未找到 PostgreSQL {name}，请指定 --postgres-bin")
    environment = {**os.environ, "PGCLIENTENCODING": "UTF8"}
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    migration = MIGRATION.read_text(encoding="utf-8-sig")
    ownership_migration = OWNERSHIP_MIGRATION.read_text(encoding="utf-8-sig")
    entity_migration = ENTITY_MIGRATION.read_text(encoding="utf-8-sig")
    baseline = BASELINE.read_text(encoding="utf-8-sig")
    marker = migration.splitlines()[0]
    assert baseline.count(marker) == 1, "新库基线必须且只能包含一次迁移"
    legacy = baseline.split(marker, 1)[0]
    assert baseline[baseline.index(marker):].strip() == "\n\n".join(
        item.strip() for item in (migration, ownership_migration, entity_migration)), "新库基线尾部必须与三次迁移一致"
    planning_baseline = baseline.split(entity_migration.splitlines()[0], 1)[0]

    with tempfile.TemporaryDirectory(prefix="datahub-domain-workbench-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments: object, input_text: str | None = None, required: bool = True):
            command = [str(binary / (name + suffix)), *map(str, arguments)]
            if name == "pg_ctl":
                output = directory / "pg_ctl.log"
                with output.open("w", encoding="utf-8") as log:
                    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                            creationflags=flags, timeout=60)
                result.stdout = output.read_text(encoding="utf-8", errors="replace")
                result.stderr = ""
            else:
                result = subprocess.run(command, input=input_text, text=True, encoding="utf-8", errors="replace",
                                        capture_output=True, env=environment, creationflags=flags, timeout=240)
            if required and result.returncode:
                raise AssertionError(f"{name} 执行失败：\n{result.stdout[-1000:]}\n{result.stderr[-4000:]}")
            return result

        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        data = directory / "data"
        run("initdb", "-D", data, "-U", "domain_workbench_test", "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str = "upgrade", required: bool = True):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", "domain_workbench_test",
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate",
                           input_text=PREFIX + statement, required=required)

            def value(statement: str, database: str = "upgrade"):
                return json.loads(sql(statement, database).stdout.strip())

            def snapshot(database: str = "upgrade"):
                return value("""
                    CREATE TEMP TABLE verify_rows(name text, total bigint, digest text);
                    DO $snapshot$ DECLARE t record; BEGIN
                        FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='datahub-center' AND tablename NOT IN (
                            'dh_modeling_data_domain','dh_modeling_subject_domain','dh_modeling_biz_category_data_domain_rel',
                            'dh_modeling_logic_model','dh_modeling_concept_model','system_menu') LOOP
                            EXECUTE format('INSERT INTO verify_rows SELECT %L,count(*),md5(COALESCE(string_agg(to_jsonb(r)::text,'''' ORDER BY to_jsonb(r)::text),'''')) FROM %I.%I r',
                                           t.tablename,'datahub-center',t.tablename);
                        END LOOP;
                    END $snapshot$;
                    SELECT jsonb_object_agg(name,jsonb_build_array(total,digest)) FROM verify_rows;
                """, database)

            def domains(database: str = "upgrade"):
                return value("""SELECT jsonb_build_object(
                    'data', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM dh_modeling_data_domain d),
                    'subject', (SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM dh_modeling_subject_domain s),
                    'concept', (SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM dh_modeling_concept_model c))""", database)

            def expect_error(statement: str, code: str) -> None:
                result = sql(statement, required=False)
                assert result.returncode and code in result.stderr, result.stderr

            sql("CREATE DATABASE upgrade; CREATE DATABASE fresh;", "postgres")
            sql(legacy)
            # 超出授权范围的概念模型引用必须阻断，失败不得留下新增列或部分清理。
            sql("INSERT INTO dh_modeling_concept_model(id,name,code,data_domain_id) SELECT -900001,'引用保护测试','DOMAIN_GUARD_FIXTURE',id FROM dh_modeling_data_domain ORDER BY id LIMIT 1")
            protected = snapshot()
            old_domains = domains()
            rejected = sql(migration, required=False)
            assert rejected.returncode and "P0001" in rejected.stderr, rejected.stderr
            assert snapshot() == protected and domains() == old_domains, "引用阻断未完整回滚"
            assert value("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_modeling_subject_domain' AND column_name='data_domain_id'") == 0
            sql("DELETE FROM dh_modeling_concept_model WHERE id=-900001")
            before = snapshot()
            old_model = value("SELECT COALESCE(jsonb_agg(to_jsonb(m)-ARRAY['data_domain_id','subject_domain_id','classification_status','revision','updater','update_time'] ORDER BY id),'[]') FROM dh_modeling_logic_model m")
            sql(migration)
            # 已存在的跨板块错配阻断新守卫安装，不擅自修正调用方的归属。
            sql("UPDATE dh_modeling_concept_model SET data_domain_id=12 WHERE subject_domain_id=115")
            mismatched = domains()
            rejected = sql(ownership_migration, required=False)
            assert rejected.returncode and "P0001" in rejected.stderr, rejected.stderr
            assert domains() == mismatched
            sql("UPDATE dh_modeling_concept_model SET data_domain_id=29 WHERE subject_domain_id=115")
            sql(ownership_migration)
            installed = domains()
            sql(ownership_migration)
            assert domains() == installed, "0206 重放修改了业务记录"
            assert snapshot() == before, "迁移改动了范围以外的业务表或审计数据"
            assert old_model == value("SELECT COALESCE(jsonb_agg(to_jsonb(m)-ARRAY['data_domain_id','subject_domain_id','classification_status','revision','updater','update_time'] ORDER BY id),'[]') FROM dh_modeling_logic_model m"), "逻辑模型本体或字段发生改动"
            assert value("SELECT count(*) FROM dh_modeling_logic_model WHERE data_domain_id IS NOT NULL OR subject_domain_id IS NOT NULL") == 0
            assert value("SELECT count(*) FROM dh_modeling_biz_category_data_domain_rel") == 0
            assert value("SELECT count(*) FROM dh_modeling_data_domain") == 10
            assert value("SELECT count(*) FROM dh_modeling_subject_domain") == 20
            assert value("SELECT count(*) FROM dh_modeling_subject_domain s LEFT JOIN dh_modeling_data_domain d ON d.id=s.data_domain_id WHERE d.id IS NULL") == 0
            assert value("SELECT count(*) FROM dh_modeling_subject_domain s JOIN dh_modeling_subject_domain p ON p.id=s.parent_id WHERE s.data_domain_id<>p.data_domain_id") == 0
            assert value("SELECT count(*) FROM dh_modeling_data_domain WHERE jsonb_array_length(naming_rules)<>4") == 0
            assert value("SELECT count(*) FROM dh_modeling_concept_model") == 8
            assert value("SELECT sum(jsonb_array_length(design->'entities')) FROM dh_modeling_concept_model") == 35
            assert value("""SELECT jsonb_object_agg(data_domain_id,count) FROM (
                SELECT data_domain_id,count(*) FROM dh_modeling_subject_domain WHERE deleted=0 GROUP BY data_domain_id) s""") == {
                    "12": 4, "29": 4, "30": 2, "31": 2, "32": 2, "33": 2, "34": 2, "35": 2}
            assert value("""SELECT jsonb_object_agg(id,jsonb_build_array(count,activity_count)) FROM (
                SELECT m.data_domain_id AS id,
                    count(*) FILTER(WHERE COALESCE(e.item->>'entityType','OBJECT')='OBJECT') AS count,
                    count(*) FILTER(WHERE e.item->>'entityType'='ACTIVITY') AS activity_count
                FROM dh_modeling_concept_model m CROSS JOIN LATERAL jsonb_array_elements(m.design->'entities') e(item)
                WHERE m.deleted=0 GROUP BY m.data_domain_id) s""") == {"12": [8, 7], "29": [10, 10]}
            assert value("""SELECT jsonb_object_agg(subject_domain_id,count) FROM (
                SELECT subject_domain_id,COALESCE(sum(jsonb_array_length(design->'entities')),0) AS count
                FROM dh_modeling_concept_model WHERE deleted=0 AND subject_domain_id IS NOT NULL GROUP BY subject_domain_id) s""") == {
                    "76": 4, "77": 2, "78": 6, "79": 3, "113": 5, "114": 2, "115": 6, "117": 7}
            assert value("SELECT naming_rules->1->'includeSubjectAbbreviation' FROM dh_modeling_data_domain WHERE id=12") is False
            assert value("SELECT naming_rules->0->'minuteSuffix' FROM dh_modeling_data_domain WHERE id=1") == "fgfasdf"
            expect_error("INSERT INTO dh_modeling_data_domain(name,code) SELECT '重复编码测试',lower(code) FROM dh_modeling_data_domain LIMIT 1", "23505")
            expect_error("INSERT INTO dh_modeling_data_domain(name,code) SELECT name,'UNIQUE_TEST_CODE' FROM dh_modeling_data_domain LIMIT 1", "23505")
            expect_error("INSERT INTO dh_modeling_subject_domain(data_domain_id,parent_id,name,code,abbreviation) SELECT data_domain_id,parent_id,'重复编码测试',lower(code),'UNIQUE_TEST_ABBR' FROM dh_modeling_subject_domain LIMIT 1", "23505")
            expect_error("INSERT INTO dh_modeling_subject_domain(data_domain_id,parent_id,name,code,abbreviation) SELECT data_domain_id,parent_id,'重复缩写测试','UNIQUE_TEST_CODE',lower(abbreviation) FROM dh_modeling_subject_domain LIMIT 1", "23505")
            expect_error("INSERT INTO dh_modeling_subject_domain(data_domain_id,parent_id,name,code,abbreviation) SELECT data_domain_id,parent_id,name,'UNIQUE_TEST_CODE','UNIQUE_TEST_ABBR' FROM dh_modeling_subject_domain LIMIT 1", "23505")
            expect_error("INSERT INTO dh_modeling_subject_domain(data_domain_id,name,code,abbreviation) VALUES(-999999,'无效板块测试','MISSING_DOMAIN','MISSING_DOMAIN')", "23514")
            expect_error("DELETE FROM dh_modeling_data_domain WHERE id=(SELECT data_domain_id FROM dh_modeling_subject_domain WHERE data_domain_id NOT IN(SELECT data_domain_id FROM dh_modeling_concept_model) LIMIT 1)", "23503")
            expect_error("UPDATE dh_modeling_concept_model SET data_domain_id=12 WHERE subject_domain_id=115", "23514")
            expect_error("INSERT INTO dh_modeling_logic_model(name,create_mode,status,subject_domain_id) VALUES('归属守卫测试','FORWARD','REVISING',115)", "23514")
            expect_error("UPDATE dh_modeling_subject_domain SET data_domain_id=12 WHERE id=115", "23514")
            expect_error("INSERT INTO dh_modeling_subject_domain(data_domain_id,parent_id,name,code,abbreviation) VALUES(12,115,'跨板块子主题测试','WRONG_PARENT','WRONG_PARENT')", "23514")
            sql("BEGIN; UPDATE dh_modeling_concept_model SET subject_domain_id=113 WHERE subject_domain_id=115; ROLLBACK")
            sql(planning_baseline, "fresh")
            assert domains("fresh") == domains(), "新库基线与增量迁移的板块/主题域结果不同"
            for query in (
                "SELECT jsonb_agg(to_jsonb(c) ORDER BY table_name,ordinal_position) FROM information_schema.columns c WHERE table_schema='datahub-center' AND table_name IN ('dh_modeling_data_domain','dh_modeling_subject_domain')",
                "SELECT jsonb_agg(to_jsonb(i) ORDER BY indexname) FROM pg_indexes i WHERE schemaname='datahub-center' AND tablename IN ('dh_modeling_data_domain','dh_modeling_subject_domain')",
            ):
                # information_schema 包含数据库名，消除独立验证库的名称差异。
                assert re.sub(r'"(upgrade|fresh)"', '"database"', json.dumps(value(query), sort_keys=True)) == re.sub(r'"(upgrade|fresh)"', '"database"', json.dumps(value(query, "fresh"), sort_keys=True))
            verify_business_entities(sql, value, expect_error, snapshot, entity_migration, baseline, port)
            if args.mapper_tests:
                maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
                if not maven:
                    raise AssertionError("未找到 Maven，无法执行 Mapper 测试")
                test_names = ("DataDomainMetadataContractTest", "PlanningDomainServiceTest",
                              "PlanningDomainMapperPostgreSqlTest", "PlanningDomainPresentationServiceTest",
                              "PlanningDomainOwnershipTest", "ConceptModelServiceImplTest",
                              "IndicatorModelServiceImplTest", "BusinessConditionServiceImplTest",
                              "LogicModelServiceImplTest")
                if args.mapper_test_name:
                    assert set(args.mapper_test_name) <= set(test_names), "仅允许选择本任务的已登记测试"
                    test_names = tuple(args.mapper_test_name)
                command = [maven, "-o", "-pl", ":yudao-module-modeling-server", "-am",
                           "-Dtest=" + ",".join(test_names), "-Dsurefire.failIfNoSpecifiedTests=false", "test", "-q"]
                if os.name == "nt":
                    command = ["cmd.exe", "/c", *command]
                test_environment = {**environment,
                    "MODEL_OVERVIEW_TEST_JDBC_URL": f"jdbc:postgresql://127.0.0.1:{port}/upgrade",
                    "MODEL_OVERVIEW_TEST_DB_USER": "domain_workbench_test", "MODEL_OVERVIEW_TEST_DB_PASSWORD": "",
                    "MAVEN_OPTS": "-Dfile.encoding=UTF-8"}
                result = subprocess.run(command, cwd=ROOT, env=test_environment, creationflags=flags,
                                        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
                assert result.returncode == 0, result.stdout[-6000:] + result.stderr[-3000:]
                reports = ROOT / "yudao-module-modeling/yudao-module-modeling-server/target/surefire-reports"
                summary = []
                for name in test_names:
                    paths = list(reports.glob(f"TEST-*.{name}.xml"))
                    assert len(paths) == 1, f"缺失测试结果：{name}"
                    report = ET.parse(paths[0]).getroot()
                    counts = {key: int(report.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
                    assert counts["tests"] > 0 and counts["skipped"] == 0 and counts["failures"] == counts["errors"] == 0, counts
                    summary.append({"test": name, **counts})
                print(json.dumps({"maven": "通过", "reports": summary}, ensure_ascii=False))
            print(json.dumps({"result": "通过", "postgresql": "真实独立临时实例", "upgrade": "通过", "fresh_baseline": "通过", "constraints": 11, "reference_rollback": "通过", "ownership_replay": "通过", "scope_snapshot": "未改动其他业务表", "jsonb_statistics": "通过", "skipped": 0}, ensure_ascii=False))
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "immediate", "-w", "stop", required=False)


if __name__ == "__main__":
    main()
