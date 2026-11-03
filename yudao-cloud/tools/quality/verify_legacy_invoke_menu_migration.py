"""在独立临时 PostgreSQL 集群验证旧调用按钮迁移；不接受业务库连接参数。"""
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
MIGRATIONS = ROOT / "sql/postgresql/migrations"
ORIGINAL = MIGRATIONS / "0168_supply_retire-legacy-invoke-menu.sql"
MIGRATION = MIGRATIONS / "0170_supply_retire-legacy-invoke-menu-by-identity.sql"
BASELINE = ROOT / "sql/postgresql/datahub-center.sql"
TABLES = ("system_menu", "system_role_menu", "system_tenant_package")
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'


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
    # 不继承既有连接配置，所有连接只能指向本脚本创建的临时实例。
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    baseline = BASELINE.read_text(encoding="utf-8-sig")
    migration = MIGRATION.read_text(encoding="utf-8-sig")
    original = ORIGINAL.read_text(encoding="utf-8-sig")

    with tempfile.TemporaryDirectory(prefix="datahub-invoke-menu-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments: str, input_text: str | None = None, required: bool = True):
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
        run("initdb", "-D", data, "-U", "invoke_menu_test", "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str = "upgrade", required: bool = True):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", "invoke_menu_test",
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                           input_text=PREFIX + statement, required=required)

            def value(statement: str, database: str = "upgrade"):
                return json.loads(sql(statement, database).stdout.strip())

            def rows(table: str, where: str = "TRUE", database: str = "upgrade"):
                assert table in TABLES
                return value(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM {table} t WHERE {where}", database)

            def snapshot(database: str = "upgrade"):
                return {table: rows(table, database=database) for table in TABLES}

            def reset(extra: str = "") -> None:
                sql("""TRUNCATE system_menu,system_role_menu,system_tenant_package;
                    INSERT INTO system_menu(id,name,type,parent_id,permission,component) VALUES
                        (6304,'接口测试',2,5990,'supply:data-api:invoke','supply/apitest/index'),
                        (6302,'API 管理',2,5990,'supply:data-api:query','supply/dataapi/index');
                """ + extra)

            def old_menu(menu_id: int = 6911, status: int = 0, deleted: int = 0) -> str:
                return f"""INSERT INTO system_menu(id,name,type,parent_id,permission,component,status,deleted)
                    VALUES({menu_id},'旧调用按钮',3,6302,'supply:data-api:invoke','',{status},{deleted});"""

            collision = """INSERT INTO system_menu(id,name,type,parent_id,permission,component,status,deleted)
                VALUES(6911,'工单协调',3,6353,'governance:quality-work-order:coordinate',NULL,0,0);
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(91,91,6911,91);
                INSERT INTO system_tenant_package(id,name,menu_ids) VALUES(91,'协调套餐','[7,"6911",6911,6304]');"""

            checks = 0

            def passed(description: str) -> None:
                nonlocal checks
                checks += 1
                print(f"通过 {checks:02d}：{description}", flush=True)

            def replay() -> None:
                before = snapshot()
                sql(migration)
                assert snapshot() == before, "重放改动了权限或审计字段"

            def rejected(description: str, extra: str) -> None:
                reset(old_menu() + """
                    INSERT INTO system_role_menu(id,role_id,menu_id) VALUES(1,1,6911);
                    INSERT INTO system_tenant_package(id,name,menu_ids) VALUES(1,'旧套餐','[6911,7]');
                """ + extra)
                before = snapshot()
                result = sql(migration, required=False)
                assert result.returncode, f"{description} 未阻断"
                assert snapshot() == before, f"{description} 未完整回滚"
                passed(description + "完整回滚")

            sql("CREATE DATABASE upgrade; CREATE DATABASE fresh;", "postgres")
            # 直接使用基线三张表的实际字段定义，避免模拟类型掩盖 PostgreSQL 行为。
            schema = 'CREATE SCHEMA "datahub-center"; CREATE SEQUENCE "datahub-center".system_menu_id_seq;\n'
            for table in TABLES:
                match = re.search(rf'CREATE TABLE "datahub-center"\."{table}" \(.*?\n\)\s*;', baseline, re.DOTALL)
                assert match, f"基线未找到 {table}"
                schema += match[0] + f'\nALTER TABLE "datahub-center"."{table}" ADD PRIMARY KEY(id);\n'
            sql(schema)

            # 精确复现用户提供的 6911 工单协调记录，证明旧脚本会失败且不会部分提交。
            reset(collision)
            before = snapshot()
            result = sql(original, required=False)
            assert result.returncode and "P0001" in result.stderr, result.stderr
            assert "旧 6911 菜单已改为其他用途或停用" in result.stderr, result.stderr
            assert snapshot() == before
            passed("0168 在 6911 已被工单协调占用时拒绝执行，权限表不变")
            sql(migration)
            assert snapshot() == before, "0170 误改工单协调菜单或授权"
            replay()
            passed("0170 保留工单协调菜单、角色与套餐及其重放结果")

            for old_id in (6911, 16911):
                reset((collision if old_id != 6911 else "") + old_menu(old_id) + f"""
                    INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
                        (1,1,{old_id},1,0),(2,1,{old_id},1,0),(3,1,{old_id},2,0),
                        (4,2,{old_id},1,0),(5,2,6304,1,0),(6,3,{old_id},1,1),
                        (7,4,{old_id},1,0),(8,4,6304,1,1),(9,5,7777,1,0);
                    INSERT INTO system_tenant_package(id,name,menu_ids,status,deleted) VALUES
                        (1,'字符串先出现','[7,"{old_id}",9,{old_id},"9"]',0,0),
                        (2,'已有目标','[{old_id},"6304",4,6304]',0,0),
                        (3,'逻辑删除套餐','[{old_id},7]',0,1),
                        (4,'停用套餐','[{old_id},8]',1,0),
                        (5,'无旧项保留','[6304,"6304",7,7]',0,0),
                        (6,'空套餐','[]',0,0);
                """)
                preserved = {table: rows(table, "id=6911" if table == "system_menu" else "id=91")
                             for table in TABLES} if old_id != 6911 else None
                sql(migration)
                assert not rows("system_menu", f"id={old_id}")
                assert not rows("system_role_menu", f"menu_id={old_id}")
                grants = rows("system_role_menu", "id<90")
                assert [(r["id"], r["role_id"], r["menu_id"], r["tenant_id"], r["deleted"]) for r in grants] == [
                    (1,1,6304,1,0),(3,1,6304,2,0),(5,2,6304,1,0),(7,4,6304,1,0),
                    (8,4,6304,1,1),(9,5,7777,1,0)], grants
                packages = {r["id"]: json.loads(r["menu_ids"]) for r in rows("system_tenant_package", "id<90")}
                assert packages == {1:[7,"6304",9,"9"],2:["6304",4],3:[7],4:[6304,8],
                                    5:[6304,"6304",7,7],6:[]}, packages
                if preserved is not None:
                    assert {table: rows(table, "id=6911" if table == "system_menu" else "id=91")
                            for table in TABLES} == preserved
                replay()
                passed(f"旧按钮 {old_id} 按身份删除；有效角色去重且保留主键、套餐类型与顺序、重放不变")

            for status, deleted in ((1,0), (0,1), (1,1)):
                reset(old_menu(status=status, deleted=deleted) + """
                    INSERT INTO system_role_menu(id,role_id,menu_id) VALUES(1,1,6911),(2,2,6304);
                    INSERT INTO system_tenant_package(id,name,menu_ids) VALUES
                        (1,'旧套餐','[6911,"6911",7]'),(2,'已有目标','[6911,"6304",8,6304]');
                """)
                sql(migration)
                assert not rows("system_menu", "id=6911")
                assert [(r["id"],r["menu_id"]) for r in rows("system_role_menu")] == [(2,6304)]
                assert [json.loads(r["menu_ids"]) for r in rows("system_tenant_package")] == [[7],["6304",8]]
                replay()
                passed(f"旧菜单 status={status}/deleted={deleted} 只清理、不恢复授权，重放不变")

            reset("""INSERT INTO system_role_menu(id,role_id,menu_id) VALUES(1,1,6911),(2,2,6304);
                INSERT INTO system_tenant_package(id,name,menu_ids) VALUES(1,'孤立套餐','[7,6911,"6911",8]');""")
            before = snapshot()
            sql(migration)
            assert snapshot() == before
            replay()
            passed("旧菜单和 6911 都缺失时保留无法证明来源的孤立引用，重放不变")

            reset(old_menu(16911) + """INSERT INTO system_role_menu(id,role_id,menu_id) VALUES
                (1,1,16911),(2,2,6911);
                INSERT INTO system_tenant_package(id,name,menu_ids) VALUES(1,'混合套餐','[7,16911,6911,"6911",8]');""")
            sql(migration)
            assert [(r["id"],r["menu_id"]) for r in rows("system_role_menu")] == [(1,6304),(2,6911)]
            assert json.loads(rows("system_tenant_package")[0]["menu_ids"]) == [7,6304,6911,"6911",8]
            replay()
            passed("旧按钮改号且 6911 有孤立引用时只迁移确认的按钮授权，重放不变")

            for first, second, expected in (("6911",6911,"6304"), (6911,"6911",6304)):
                entries = [0] * 2041 + [first,second]
                packed = json.dumps(entries, separators=(",", ":"))
                assert len(packed) == 4095
                reset(old_menu() + f"""INSERT INTO system_tenant_package(id,name,menu_ids)
                    VALUES(1,'临近字段上限','{packed}');""")
                sql(migration)
                changed = rows("system_tenant_package")[0]["menu_ids"]
                assert len(changed) <= 4096 and json.loads(changed) == [0] * 2041 + [expected]
                replay()
                passed(f"4095 字符套餐首个旧编号类型为 {type(first).__name__}，归并不超限且重放不变")

            rejected("多个旧按钮身份候选", old_menu(16911))
            rejected("旧按钮仍有子项", "INSERT INTO system_menu(id,name,type,parent_id) VALUES(17000,'子项',3,6911);")
            rejected("API 管理父菜单身份错位", "UPDATE system_menu SET component='supply/other/index' WHERE id=6302;")
            for field in ("status", "deleted"):
                rejected(f"旧按钮 {field}=2", f"UPDATE system_menu SET {field}=2 WHERE id=6911;")
            for assignment in ("type=2", "parent_id=9999", "component='supply/other/index'"):
                rejected(f"相同调用权限但身份错位 {assignment}", f"UPDATE system_menu SET {assignment} WHERE id=6911;")
            rejected("目标菜单缺失", "DELETE FROM system_menu WHERE id=6304;")
            for assignment in ("status=1", "deleted=1", "type=3", "parent_id=9999",
                               "permission='supply:data-api:query'", "component='supply/other/index'"):
                rejected(f"目标菜单无效 {assignment}", f"UPDATE system_menu SET {assignment} WHERE id=6304;")
            rejected("目标组件重复", """INSERT INTO system_menu(id,name,type,parent_id,permission,component)
                VALUES(17000,'重复入口',2,5990,'supply:data-api:invoke','/supply/apitest/index/');""")
            for content in ("{}", "not-json"):
                rejected(f"套餐非法 JSON {content}", f"UPDATE system_tenant_package SET menu_ids='{content}' WHERE id=1;")

            sql(baseline, "fresh")
            fresh = snapshot("fresh")
            assert not value("""SELECT count(*) FROM system_menu WHERE type=3 AND parent_id=6302
                AND permission='supply:data-api:invoke' AND coalesce(component,'')=''""", "fresh")
            sql(migration, "fresh")
            assert snapshot("fresh") == fresh, "新库重放0170改变权限快照"
            passed("新库完整基线可执行，0170 重放保持三张权限表全量快照不变")
            print(f"完成：{checks} 项真实 PostgreSQL 回归，跳过 0 项；临时集群将停止并清理。", flush=True)
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
