"""在独立临时 PostgreSQL 集群验证业务单租户迁移；不接受业务库连接参数。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sql/postgresql/migrations/0169_datahub_remove-business-tenancy.sql"
BASELINE = ROOT / "sql/postgresql/datahub-center.sql"
CURSOR = "dh_governance_quality_exception_expiry_cursor"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-bin", type=Path, help="含 initdb、pg_ctl、psql 的 PostgreSQL bin 目录")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    binary = args.postgres_bin or Path(shutil.which("initdb") or "").parent
    for name in ("initdb", "pg_ctl", "psql"):
        if not (binary / (name + suffix)).is_file():
            raise SystemExit(f"未找到 PostgreSQL {name}；请指定 --postgres-bin")
    environment = {**os.environ, "PGCLIENTENCODING": "UTF8"}
    # 不继承任何现有数据库连接配置，全部连接仅指向本脚本创建的实例。
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

    with tempfile.TemporaryDirectory(prefix="datahub-single-tenant-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments: str, input_text: str | None = None, required: bool = True):
            command = [str(binary / (name + suffix)), *map(str, arguments)]
            if name == "pg_ctl":
                # Windows 后台服务器会继承管道句柄，使用文件避免等待管道 EOF。
                output = directory / "pg_ctl.log"
                with output.open("w", encoding="utf-8") as log:
                    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                            creationflags=creation_flags, timeout=60)
                result.stdout = output.read_text(encoding="utf-8", errors="replace")
                result.stderr = ""
            else:
                result = subprocess.run(
                    command, input=input_text, text=True, encoding="utf-8", errors="replace", capture_output=True,
                    env=environment, creationflags=creation_flags, timeout=180,
                )
            if required and result.returncode:
                raise AssertionError(f"{name} 失败：\n{result.stdout[-1500:]}\n{result.stderr[-4000:]}")
            return result

        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        data = directory / "data"
        run("initdb", "-D", data, "-U", "single_tenant_test", "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str = "upgrade", required: bool = True):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", "single_tenant_test",
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate",
                           input_text=statement, required=required)

            def value(statement: str, database: str = "upgrade"):
                return json.loads(sql(statement, database).stdout.strip())

            def expect_error(statement: str, code: str) -> None:
                result = sql('SET search_path="datahub-center",pg_catalog;\n' + statement, required=False)
                assert result.returncode and code in result.stderr, result.stderr

            def snapshot(database: str = "upgrade"):
                # 比较完整业务/脚手架行内容；仅排除已删除维度和允许重建的内部扫描游标。
                return value(r"""
                    CREATE TEMP TABLE verify_rows(name text, total bigint, digest text);
                    DO $snapshot$ DECLARE t record; BEGIN
                        FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='datahub-center'
                                 AND tablename<>'dh_governance_quality_exception_expiry_cursor' LOOP
                            EXECUTE format('INSERT INTO verify_rows SELECT %L,count(*),md5(COALESCE(string_agg((to_jsonb(r)-''tenant_id'')::text,'''' ORDER BY (to_jsonb(r)-''tenant_id'')::text),'''')) FROM %I.%I r',
                                           t.tablename,'datahub-center',t.tablename);
                        END LOOP;
                    END $snapshot$;
                    SELECT jsonb_object_agg(name,jsonb_build_array(total,digest)) FROM verify_rows;
                """, database)

            tenant_count_sql = """SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'
                AND table_name LIKE 'dh\\_%' ESCAPE '\\' AND column_name='tenant_id';"""
            index_sql = """SELECT jsonb_object_agg(indexname,indexdef) FROM pg_indexes
                WHERE schemaname='datahub-center' AND tablename LIKE 'dh\\_%' ESCAPE '\\';"""
            fk_sql = """SELECT jsonb_object_agg(con.conname,pg_get_constraintdef(con.oid)) FROM pg_constraint con
                JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='datahub-center' AND c.relname LIKE 'dh\\_%' ESCAPE '\\' AND con.contype='f';"""

            def indexes(database: str = "upgrade"):
                # PostgreSQL 18 重解析 varchar 数组时把整数组转 text 改写为逐元素转换。
                def canonical(definition: str) -> str:
                    definition = re.sub(r"\('([^']*)'::character varying\)::text", r"'\1'::text", definition)
                    return re.sub(r"\(ARRAY\[([^\]]*)\]\)::text\[\]",
                                  lambda match: "ARRAY[" + match[1].replace("::character varying", "::text") + "]", definition)
                return {name: canonical(definition) for name, definition in value(index_sql, database).items()}
            migration = MIGRATION.read_text(encoding="utf-8-sig")
            baseline = BASELINE.read_text(encoding="utf-8-sig")
            marker = migration.splitlines()[0]
            assert baseline.count(marker) == 1, "基线必须精确包含一次本迁移"
            legacy = baseline.split(marker, 1)[0]
            assert baseline[baseline.index(marker):].startswith(migration.strip()), "基线中的 0169 区间与独立迁移不一致"
            historical_baseline = legacy + migration
            sql("CREATE DATABASE upgrade; CREATE DATABASE fresh; CREATE DATABASE historical;", "postgres")
            sql(legacy)
            original_tenant_count = value(tenant_count_sql)
            assert original_tenant_count > 200
            before_indexes, before_fk = indexes(), value(fk_sql)

            sql("""INSERT INTO "datahub-center".dh_dataforge_access_checkpoint
                (id,tenant_id,data_access_id,schema_name,table_name,watermark_column,checkpoint_json,owner_run_id)
                VALUES(-900001,1,1,'fixture','rows','stamp','{"version":1}',1);""")
            rejected_material = sql(migration, required=False)
            assert rejected_material.returncode and "P0001" in rejected_material.stderr
            assert value(tenant_count_sql) == original_tenant_count
            assert value('SELECT count(*) FROM "datahub-center".dh_dataforge_access_checkpoint WHERE id=-900001') == 1
            sql('DELETE FROM "datahub-center".dh_dataforge_access_checkpoint WHERE id=-900001')

            # 同名业务值在旧租户下合法，转为全局时必须阻断，且失败不得部分删除列或记录。
            sql("""SET search_path="datahub-center",pg_catalog;
                INSERT INTO dh_governance_master_domain(id,code,name,tenant_id) VALUES
                    (-900001,'SINGLE_TENANT_CONFLICT','单租户冲突甲',900001),
                    (-900002,'SINGLE_TENANT_CONFLICT','单租户冲突乙',900002);""")
            failed = sql(migration, required=False)
            assert failed.returncode and "P0001" in failed.stderr, failed.stderr
            assert value(tenant_count_sql) == original_tenant_count
            assert value("SELECT count(*) FROM \"datahub-center\".dh_governance_master_domain WHERE id IN(-900001,-900002)") == 2
            assert indexes() == before_indexes and value(fk_sql) == before_fk
            sql('DELETE FROM "datahub-center".dh_governance_master_domain WHERE id IN(-900001,-900002)')
            before_rows = snapshot()
            sql(migration)
            assert value(tenant_count_sql) == 0
            assert snapshot() == before_rows, "迁移改动了租户维度以外的业务或脚手架记录"

            def without_tenant(definition: str) -> str:
                definition = definition.replace("tenant_id WITH =, ", "").replace("tenant_id, ", "")
                return definition.replace(", tenant_id", "").replace(" INCLUDE (tenant_id)", "")

            expected_indexes = {name: without_tenant(definition) for name, definition in before_indexes.items()
                                if name not in {"uk_quality_expiry_cursor_tenant", "uk_dh_safety_table_permission_role",
                                                "uk_dh_safety_field_permission_role"}}
            actual_indexes = indexes()
            assert actual_indexes == expected_indexes, json.dumps({
                name: [expected_indexes.get(name), actual_indexes.get(name)]
                for name in expected_indexes.keys() | actual_indexes.keys()
                if expected_indexes.get(name) != actual_indexes.get(name)
            }, ensure_ascii=False)
            assert value(fk_sql) == {name: without_tenant(definition) for name, definition in before_fk.items()}
            sql(migration)
            assert snapshot() == before_rows and indexes() == expected_indexes
            assert value("SELECT json_agg(id) FROM \"datahub-center\".dh_governance_quality_exception_expiry_cursor") == [1]
            assert value("""SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                         WHERE n.nspname='datahub-center' AND p.prosrc ~* 'tenant_id|tenantId'""") == 0
            assert value("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='system_users' AND column_name='tenant_id'") == 1
            assert value("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_dataforge_dev_schedule' AND column_name='tenant_code'") == 1
            print(f"通过：{original_tenant_count} 张业务表迁移及重放；全量行内容和外键保留、索引按最终业务约束转换；旧认证材料和唯一冲突阻断完整回滚", flush=True)

            # 真正写入触发器和约束路径，避免只检查函数定义文本而漏掉延迟解析错误。
            expect_error("INSERT INTO dh_governance_master_code_counter(model_id) VALUES(-900001)", "23503")
            expect_error("INSERT INTO dh_governance_quality_exception_expiry_cursor(id) VALUES(2)", "23514")
            expect_error("INSERT INTO dh_modeling_logic_model(name,data_domain_id) VALUES('不存在的归属',-900001)", "23514")
            sql("""SET search_path="datahub-center",pg_catalog;
                INSERT INTO dh_governance_standard_code_set(id,name,type) VALUES(-900001,'回归码集','single_tenant_test');
                INSERT INTO dh_governance_standard_code_item(id,label,value,dict_type,code_set_id) VALUES
                    (-900001,'父节点','parent','single_tenant_test',-900001);
                INSERT INTO dh_governance_standard_code_item(id,label,value,dict_type,code_set_id,parent_id) VALUES
                    (-900002,'子节点','child','single_tenant_test',-900001,-900001);""")
            expect_error("UPDATE dh_governance_standard_code_item SET parent_id=-900002 WHERE id=-900001", "P0001")
            expect_error("INSERT INTO dh_governance_standard_code_item(id,value,dict_type,code_set_id) VALUES(-900003,'parent','single_tenant_test',-900001)", "23505")
            print("通过：外键、全局游标、模型归属、标准码项防环和全局部分唯一约束", flush=True)

            # 历史迁移只与截至 0169 的相同结构比较，不回放此后已退役的业务能力。
            sql(historical_baseline, "historical")
            assert value(tenant_count_sql, "historical") == 0
            assert indexes("historical") == expected_indexes
            assert value(fk_sql, "historical") == value(fk_sql)
            # 不比较两次安装生成的 CURRENT_TIMESTAMP 审计值，但必须保留相同业务行数。
            assert {name: row[0] for name, row in snapshot("historical").items()} == {name: row[0] for name, row in before_rows.items()}
            sql(baseline, "fresh")
            assert value(tenant_count_sql, "fresh") == 0
            assert value("""SELECT to_json(to_regclass('"datahub-center".dh_governance_standard_code_mapping') IS NULL
                         AND to_regclass('"datahub-center".dh_governance_standard_source_code_set') IS NULL)""", "fresh") is True
            print("通过：0169 新库与升级结构及行数一致；当前完整基线保持单租户，0180 退役码值映射表不存在", flush=True)
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
