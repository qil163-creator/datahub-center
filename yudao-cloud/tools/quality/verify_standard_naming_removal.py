"""在独立临时 PostgreSQL 集群验证命名功能删除；不接受业务库连接参数。"""
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
BASELINE_PATH = "sql/postgresql/datahub-center.sql"
MIGRATION = ROOT / "sql/postgresql/migrations/0194_governance_remove-standard-naming.sql"
ACCESS_TABLES = ("system_menu", "system_role_menu", "system_tenant_package")
BUSINESS_TABLES = ("dh_governance_standard_word_root", "dh_governance_standard_naming_policy")
OBJECTS = BUSINESS_TABLES + tuple(table + "_seq" for table in BUSINESS_TABLES)
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'
IDENTITY = """permission LIKE 'governance:standard-word-root:%'
    OR permission LIKE 'governance:standard-naming-policy:%'
    OR trim(both '/' from coalesce(component,'')) = 'governance/standardwordroot'
    OR trim(both '/' from coalesce(component,'')) LIKE 'governance/standardwordroot/%'
    OR component_name='GovernanceStandardWordRoot'
    OR (type=2 AND name='命名词根' AND trim(both '/' from coalesce(path,''))='standard-word-root')"""


def previous_baseline() -> str:
    """读取最近仍包含两张旧表的版本，确保提交删除后仍能复现升级前状态。"""
    revisions = subprocess.run(["git", "rev-list", "HEAD", "--", BASELINE_PATH], cwd=ROOT,
                               check=True, capture_output=True, text=True).stdout.splitlines()
    for revision in revisions:
        result = subprocess.run(["git", "show", f"{revision}:{BASELINE_PATH}"], cwd=ROOT,
                                check=True, capture_output=True)
        content = result.stdout.decode("utf-8-sig")
        if all(f'CREATE TABLE "datahub-center"."{table}"' in content for table in BUSINESS_TABLES):
            return content
    raise AssertionError("Git 历史中找不到命名功能删除前的完整基线")


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
    # 清除连接配置，所有连接固定到脚本创建的本地临时实例。
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    old_baseline = previous_baseline()
    baseline = (ROOT / BASELINE_PATH).read_text(encoding="utf-8-sig")
    migration = MIGRATION.read_text(encoding="utf-8-sig")

    def definition(table: str) -> str:
        match = re.search(rf'CREATE TABLE "datahub-center"\."{table}" \(.*?\n\)\s*;', old_baseline, re.DOTALL)
        assert match, f"旧基线未找到 {table}"
        # 旧导出先建业务租户列，再由后续迁移删除；夹具直接采用最终单租户结构。
        return re.sub(r',\n  "tenant_id" int8 NOT NULL DEFAULT 0(?=\n\))', '', match[0]) if table in BUSINESS_TABLES else match[0]

    with tempfile.TemporaryDirectory(prefix="datahub-naming-removal-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments, input_text: str | None = None, required: bool = True):
            command = [str(binary / (name + suffix)), *map(str, arguments)]
            if name == "pg_ctl":
                # Windows 后台进程继承管道句柄，使用文件防止等待管道 EOF。
                output = directory / "pg_ctl.log"
                with output.open("w", encoding="utf-8") as log:
                    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                            creationflags=creation_flags, timeout=60)
                result.stdout = output.read_text(encoding="utf-8", errors="replace")
                result.stderr = ""
            else:
                result = subprocess.run(command, input=input_text, text=True, encoding="utf-8", errors="replace",
                                        capture_output=True, env=environment, creationflags=creation_flags, timeout=180)
            if required and result.returncode:
                raise AssertionError(f"{name} 失败：\n{result.stdout[-1500:]}\n{result.stderr[-4000:]}")
            return result

        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        data = directory / "data"
        run("initdb", "-D", data, "-U", "naming_removal_test", "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str = "fixture", required: bool = True):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", "naming_removal_test",
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                           input_text=PREFIX + statement, required=required)

            def value(statement: str, database: str = "fixture"):
                return json.loads(sql(statement, database).stdout.strip())

            def rows(table: str, database: str = "fixture"):
                assert table in ACCESS_TABLES + BUSINESS_TABLES
                return value(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM {table} t", database)

            def present(database: str = "fixture"):
                names = ",".join(f"'{name}'" for name in OBJECTS)
                return value(f"""SELECT coalesce(jsonb_agg(relname ORDER BY relname),'[]') FROM pg_class
                    WHERE relnamespace='"datahub-center"'::regnamespace AND relname IN ({names})""", database)

            def snapshot(database: str = "fixture"):
                objects = present(database)
                return {"objects": objects, "rows": {table: rows(table, database) for table in ACCESS_TABLES + BUSINESS_TABLES
                                                      if table in ACCESS_TABLES or table in objects}}

            checks = 0

            def passed(description: str) -> None:
                nonlocal checks
                checks += 1
                print(f"通过 {checks:02d}：{description}", flush=True)

            def replay(database: str = "fixture") -> None:
                before = snapshot(database)
                sql(migration, database)
                assert snapshot(database) == before, "重放改动了权限或审计字段"

            sql("CREATE DATABASE upgrade; CREATE DATABASE fixture; CREATE DATABASE fresh;", "postgres")
            sql(old_baseline, "upgrade")
            assert sorted(present("upgrade")) == sorted(OBJECTS)
            retired = value(f"SELECT jsonb_agg(id ORDER BY id) FROM system_menu WHERE {IDENTITY}", "upgrade")
            assert retired
            before = snapshot("upgrade")
            sql(migration, "upgrade")
            assert present("upgrade") == []
            after = snapshot("upgrade")
            assert after["rows"]["system_menu"] == [row for row in before["rows"]["system_menu"] if row["id"] not in retired]
            assert after["rows"]["system_role_menu"] == [row for row in before["rows"]["system_role_menu"] if row["menu_id"] not in retired]
            for package in after["rows"]["system_tenant_package"]:
                original = next(row for row in before["rows"]["system_tenant_package"] if row["id"] == package["id"])
                expected = [item for item in json.loads(original["menu_ids"]) if str(item) not in set(map(str, retired))]
                assert json.loads(package["menu_ids"]) == expected
            replay("upgrade")
            passed("删除前完整基线升级删除两表、序列和全部目标授权，保留其他菜单，重复执行不变")

            schema = 'CREATE SCHEMA "datahub-center"; CREATE SEQUENCE "datahub-center".system_menu_id_seq;\n'
            for table in ACCESS_TABLES:
                schema += definition(table) + f'\nALTER TABLE "datahub-center"."{table}" ADD PRIMARY KEY(id);\n'
            sql(schema)

            def reset(extra: str = "", menu_id: int = 6394) -> None:
                setup = """DROP VIEW IF EXISTS naming_dependency;
                    DROP TABLE IF EXISTS naming_external_fk;
                    TRUNCATE system_menu,system_role_menu,system_tenant_package;
                """
                for table in BUSINESS_TABLES:
                    setup += f'DROP TABLE IF EXISTS "{table}"; DROP SEQUENCE IF EXISTS "{table}_seq";\n'
                    setup += f'CREATE SEQUENCE "{table}_seq";\n' + definition(table)
                    setup += f'\nALTER TABLE "{table}" ADD PRIMARY KEY(id);\n'
                setup += f"""
                    INSERT INTO dh_governance_standard_word_root(id,name,root_code,status,deleted) VALUES
                        (1,'客户','cust',0,0),(2,'旧词根','legacy',1,1);
                    INSERT INTO dh_governance_standard_naming_policy(id,scope_type,scope_id,object_type,version_no,
                        case_style,separator,max_length,content_hash,status,deleted) VALUES
                        (1,'GLOBAL',0,'STANDARD_ELEMENT',1,'LOWER','UNDERSCORE',64,repeat('a',64),'ACTIVE',0),
                        (2,'GLOBAL',0,'STANDARD_ELEMENT',2,'LOWER','UNDERSCORE',64,repeat('b',64),'RETIRED',1);
                    INSERT INTO system_menu(id,name,type,parent_id,permission,component,component_name,path) VALUES
                        (5994,'数据标准',1,0,'','','','standard'),
                        (6380,'标准数据元',2,5994,'governance:standard-element:query','governance/standardelement/index','','standard-element'),
                        ({menu_id},'命名词根',2,5994,'','governance/standardwordroot/index','GovernanceStandardWordRoot','standard-word-root'),
                        (16395,'词根查询',3,{menu_id},'governance:standard-word-root:query','','',''),
                        (16396,'策略发布',3,{menu_id},'governance:standard-naming-policy:activate','','',''),
                        (16397,'无权限按钮',3,{menu_id},'','','',''),
                        (16398,'嵌套无权限按钮',3,16397,'','','','');
                    INSERT INTO system_role_menu(id,role_id,menu_id,deleted,tenant_id) VALUES
                        (1,1,{menu_id},0,1),(2,1,16395,1,1),(3,1,16396,0,2),(4,1,16397,0,1),(5,1,16398,0,1),(6,1,6380,0,1);
                    INSERT INTO system_tenant_package(id,name,menu_ids,status,deleted) VALUES
                        (1,'混合套餐','[7,"{menu_id}",{menu_id},16395,"16396",16397,16398,"7",6380,6380]',0,0),
                        (2,'停用套餐','[16395,6380]',1,0),(3,'删除套餐','["16396",7]',0,1),
                        (4,'不变套餐','[7,"7",6380,6380]',0,0),(5,'空套餐','[]',0,0);
                """
                sql(setup + extra)

            def assert_removed() -> None:
                assert present() == []
                assert [row["id"] for row in rows("system_menu")] == [5994, 6380]
                assert [row["id"] for row in rows("system_role_menu")] == [6]
                assert [json.loads(row["menu_ids"]) for row in rows("system_tenant_package")] == [
                    [7,"7",6380,6380], [6380], [7], [7,"7",6380,6380], []]
                replay()

            reset()
            untouched = rows("system_tenant_package")[3:]
            sql(migration)
            assert_removed()
            assert rows("system_tenant_package")[3:] == untouched
            passed("清理停用与逻辑删除数据、跨租户角色、递归无权限按钮；套餐保留类型顺序与重复项，重放不变")

            reset("""INSERT INTO system_menu(id,name,type,parent_id,permission,component) VALUES
                (6394,'业务术语',2,5994,'governance:standard-term:query','governance/standardterm/index');
                INSERT INTO system_role_menu(id,role_id,menu_id) VALUES(99,9,6394);
                INSERT INTO system_tenant_package(id,name,menu_ids) VALUES(99,'旧编号占用','[6394,"6394",16394,6380]');""", 16394)
            preserved = {table: [row for row in rows(table) if row["id"] in (6394, 99)] for table in ACCESS_TABLES}
            sql(migration)
            assert present() == []
            assert {table: [row for row in rows(table) if row["id"] in (6394, 99)] for table in ACCESS_TABLES} == {
                **preserved, "system_tenant_package": [{**preserved["system_tenant_package"][0], "menu_ids": '[6394,"6394",6380]',
                                                         "updater": 'migration:0194', "update_time": rows("system_tenant_package")[-1]["update_time"]}]}
            replay()
            passed("目标菜单改号后仍按身份删除，历史 6394 被其他功能占用时保留菜单和授权")

            identities = (
                ("组件根路径", "name='自定义入口',component='/governance/standardwordroot/',component_name='',path='custom'"),
                ("组件子路径", "name='自定义入口',component='/governance/standardwordroot/custom/',component_name='',path='custom'"),
                ("组件名称", "name='自定义入口',component='',component_name='GovernanceStandardWordRoot',path='custom'"),
                ("名称与路由", "component='',component_name='',path='/standard-word-root/'"),
                ("词根权限", "name='自定义入口',component='',component_name='',path='custom',permission='governance:standard-word-root:query'"),
                ("策略权限", "name='自定义入口',component='',component_name='',path='custom',permission='governance:standard-naming-policy:query'"),
            )
            for label, assignment in identities:
                reset(f"UPDATE system_menu SET {assignment} WHERE id=6394;")
                sql(migration)
                assert_removed()
                passed(f"独立使用{label}识别入口且可重放")

            def rejected(description: str, extra: str, expected: str) -> None:
                reset(extra)
                before = snapshot()
                result = sql(migration, required=False)
                assert result.returncode and expected in result.stderr, result.stderr
                assert snapshot() == before, f"{description} 未完整回滚"
                passed(description + "拒绝执行且完整回滚")

            rejected("其他功能页签", """INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES
                (17000,'业务术语',2,6394,'governance:standard-term:query');""", "P0001")
            rejected("递归按钮下的其他功能", """INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES
                (17000,'其他权限',3,16398,'governance:standard-term:query');""", "P0001")
            for table in BUSINESS_TABLES:
                rejected(f"{table} 的视图依赖", f"CREATE VIEW naming_dependency AS SELECT id FROM {table};", "2BP01")
                assert value("SELECT count(*) FROM naming_dependency") == 2
                rejected(f"{table} 的外键依赖", f"""CREATE TABLE naming_external_fk(id bigint PRIMARY KEY,
                    naming_id bigint REFERENCES {table}(id)); INSERT INTO naming_external_fk VALUES(1,1);""", "2BP01")
                assert value("SELECT count(*) FROM naming_external_fk") == 1
                rejected(f"{table}_seq 的外部默认值依赖", f"""CREATE TABLE naming_external_fk(
                    id bigint DEFAULT nextval('{table}_seq')); INSERT INTO naming_external_fk DEFAULT VALUES;""", "2BP01")
                assert value("SELECT count(*) FROM naming_external_fk") == 1

            for content in ("{}", "not-json"):
                rejected(f"套餐非法 JSON {content}", f"UPDATE system_tenant_package SET menu_ids='{content}' WHERE id=1;",
                         "ERROR")

            sql(baseline, "fresh")
            assert present("fresh") == []
            assert value(f"SELECT count(*) FROM system_menu WHERE {IDENTITY}", "fresh") == 0
            for table in ("dh_governance_standard_element", "dh_governance_category_datameta", "dh_governance_standard_term"):
                assert value(f"SELECT to_jsonb(to_regclass('\"datahub-center\".{table}') IS NOT NULL)", "fresh"), table
            assert value("SELECT count(*) FROM system_menu WHERE permission='governance:standard-element:query'", "fresh") > 0
            assert value("SELECT count(*) FROM system_menu WHERE permission='governance:category-datameta:query'", "fresh") > 0
            assert value("SELECT count(*) FROM system_menu WHERE permission='governance:standard-term:query'", "fresh") > 0
            replay("fresh")
            passed("新库完整基线可执行且两表、序列、菜单均不存在，其他标准功能保留，重放不变")
            print(f"完成：{checks} 项真实 PostgreSQL 回归，跳过 0 项；临时集群将停止并清理。", flush=True)
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
