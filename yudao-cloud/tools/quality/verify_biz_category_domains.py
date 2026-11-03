"""在独立临时 PostgreSQL 集群验证业务分类数据域修正；不连接业务库。"""
from __future__ import annotations

import argparse
from contextlib import closing
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile

import psycopg2
from psycopg2 import sql as pg_sql


ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "sql/postgresql/datahub-center.sql"
MIGRATION_NAME = "0204_modeling_biz-category-data-domains.sql"
MIGRATION = ROOT / "sql/postgresql/migrations" / MIGRATION_NAME
BASELINE_MARKER = f"-- 新库最终状态来源：{MIGRATION_NAME}"
CATEGORY = "dh_modeling_biz_category"
DOMAIN = "dh_modeling_data_domain"
RELATION = "dh_modeling_biz_category_data_domain_rel"
SEQUENCE = RELATION + "_seq"
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'
CUSTOM_ID = 900000


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-bin", type=Path, help="含 initdb、pg_ctl、psql 的 PostgreSQL bin 目录")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    binary = args.postgres_bin or Path(shutil.which("initdb") or "").parent
    for name in ("initdb", "pg_ctl", "psql"):
        if not (binary / (name + suffix)).is_file():
            raise SystemExit(f"未找到 PostgreSQL {name}；请指定 --postgres-bin")
    baseline = BASELINE.read_text(encoding="utf-8-sig")
    assert baseline.count(BASELINE_MARKER) == 1, "完整基线必须只包含一个本迁移尾段标记"
    previous, appended = baseline.split(BASELINE_MARKER)
    migration = MIGRATION.read_text(encoding="utf-8-sig")
    assert appended.strip() == migration.strip(), "新库尾段与编号迁移必须逐字一致"
    environment = {**os.environ, "PGCLIENTENCODING": "UTF8"}
    # 删除外部连接配置；所有连接只指向本脚本新建的回环地址临时集群。
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

    with tempfile.TemporaryDirectory(prefix="datahub-biz-category-domains-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments, input_text: str | None = None, required: bool = True):
            command = [str(binary / (name + suffix)), *map(str, arguments)]
            if name == "pg_ctl":
                # 后台数据库输出落入临时文件，避免 Windows 子进程继承父进程输出管道。
                log_path = directory / "pg_ctl.log"
                with log_path.open("w", encoding="utf-8") as log:
                    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                            creationflags=creation_flags, timeout=60)
                result.stdout = log_path.read_text(encoding="utf-8", errors="replace")
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
        account = "biz_category_domains_test"
        run("initdb", "-D", data, "-U", account, "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str, required: bool = True):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", account,
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                           input_text=PREFIX + statement, required=required)

            def query(statement, database: str, parameters=None):
                with closing(psycopg2.connect(host="127.0.0.1", port=port, user=account, dbname=database,
                                             connect_timeout=10)) as connection:
                    connection.autocommit = True
                    with connection.cursor() as cursor:
                        cursor.execute(PREFIX)
                        cursor.execute(statement, parameters)
                        return cursor.fetchall()

            def value(statement, database: str, parameters=None):
                return query(statement, database, parameters)[0][0]

            def table_rows(table: str, database: str):
                statement = pg_sql.SQL("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM {} t").format(
                    pg_sql.Identifier(table))
                return value(statement, database)

            def table_hash(table: str, database: str):
                statement = pg_sql.SQL("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),"
                                       "'[]')::text) FROM {} t").format(pg_sql.Identifier(table))
                return value(statement, database)

            def snapshot(database: str):
                tables = [row[0] for row in query("SELECT tablename FROM pg_tables WHERE schemaname='datahub-center' "
                                                 "AND tablename LIKE 'dh_modeling_%' ORDER BY tablename", database)]
                sequences = [row[0] for row in query("SELECT sequencename FROM pg_sequences WHERE schemaname='datahub-center' "
                                                    "AND sequencename LIKE 'dh_modeling_%' ORDER BY sequencename", database)]
                state = {}
                for sequence in sequences:
                    statement = pg_sql.SQL("SELECT last_value,is_called FROM {}").format(pg_sql.Identifier(sequence))
                    state[sequence] = query(statement, database)[0]
                return {"tables": {table: table_hash(table, database) for table in tables}, "sequences": state}

            def preserved(before: dict, database: str):
                after = snapshot(database)
                assert set(after["tables"]) == set(before["tables"]), "建模表集合发生变化"
                for table, digest in before["tables"].items():
                    if table != RELATION:
                        assert after["tables"][table] == digest, f"{table} 的原数据被修改"
                for sequence, state in before["sequences"].items():
                    if sequence != SEQUENCE:
                        assert after["sequences"][sequence] == state, f"{sequence} 被无关修改"

            def clone(database: str):
                assert database.isidentifier()
                sql(f"CREATE DATABASE {database} TEMPLATE original", "postgres")

            def mapping(database: str):
                result = query(f"SELECT c.code,array_agg(d.code ORDER BY r.sort_order),"
                               f"array_agg(r.sort_order ORDER BY r.sort_order) FROM {CATEGORY} c "
                               f"JOIN {RELATION} r ON r.biz_category_id=c.id AND r.deleted=0 "
                               f"JOIN {DOMAIN} d ON d.id=r.data_domain_id AND d.deleted=0 AND d.status=0 "
                               "WHERE c.deleted=0 AND c.code<>'BC_VERIFY_CUSTOM' GROUP BY c.code", database)
                assert len(result) == 48, "48 个正式业务分类必须全部关联有效且启用的数据域"
                output = {}
                for code, domains, sorts in result:
                    assert len(domains) == len(set(domains)), f"{code} 存在重复域"
                    assert sorts == list(range(len(sorts))), f"{code} 展示排序不连续"
                    output[code] = domains
                assert not set(output["BC_GOV_APPROVAL"]) & {"CULTURE_TOURISM", "WEATHER_HYDRO"}
                for code in ("BC_MARKET", "BC_MARKET_ACCESS", "BC_MARKET_INSPECT", "BC_MARKET_QUALITY"):
                    assert "MARKET_REG" in output[code], f"{code} 缺少市场监管域"
                assert "EDUCATION" in output["BC_EDUCATION"]
                for code in ("BC_PUBLIC_HEALTH", "BC_MEDICAL_SERVICE"):
                    assert "HEALTH" in output[code]
                for code in ("BC_TRANSPORT", "BC_TRANS_PUBLIC"):
                    assert "TRANSPORT" in output[code]
                assert value(f"SELECT count(*) FROM {RELATION} r JOIN {CATEGORY} c ON c.id=r.biz_category_id "
                             f"LEFT JOIN {DOMAIN} d ON d.id=r.data_domain_id WHERE r.deleted=0 AND c.deleted=0 "
                             "AND (d.id IS NULL OR d.deleted<>0 OR d.status<>0)", database) == 0
                return output

            checks = 0

            def passed(description: str):
                nonlocal checks
                checks += 1
                print(f"通过 {checks:02d}：{description}", flush=True)

            def replay(database: str):
                before = snapshot(database)
                sql(migration, database)
                assert snapshot(database) == before, "重放改变了数据行、审计时间或序列"

            def rejected(database: str):
                before = snapshot(database)
                result = sql(migration, database, required=False)
                assert result.returncode and "P0001" in result.stderr, result.stderr
                assert snapshot(database) == before, "预检失败后数据或序列发生变化"

            sql("CREATE DATABASE original; CREATE DATABASE fresh;", "postgres")
            sql(previous, "original")
            assert value(f"SELECT count(*) FROM {CATEGORY} WHERE deleted=0", "original") == 48
            clone("upgrade")
            before = snapshot("upgrade")
            sql(migration, "upgrade")
            expected = mapping("upgrade")
            preserved(before, "upgrade")
            replay("upgrade")
            passed("完整旧基线升级后 48 分类全部有正确数据域；分类、域及全部模型原数据保留；重放行和序列不变")

            clone("polluted")
            sql(f"""INSERT INTO {CATEGORY}(id,parent_id,name,code,status,description,deleted)
                VALUES ({CUSTOM_ID},0,'自定义教育业务','BC_VERIFY_CUSTOM',0,'保持自定义关系',0);
                DELETE FROM {RELATION};
                INSERT INTO {RELATION}(id,biz_category_id,data_domain_id,sort_order,creator,updater)
                SELECT 500000+c.id,c.id,d.id,0,'verify:pollution','verify:pollution'
                FROM {CATEGORY} c CROSS JOIN {DOMAIN} d
                WHERE c.id<>{CUSTOM_ID} AND d.code='WEATHER_HYDRO' AND d.deleted=0;
                INSERT INTO {RELATION}(id,biz_category_id,data_domain_id,sort_order,creator,updater)
                SELECT {CUSTOM_ID},{CUSTOM_ID},id,8,'verify:custom','verify:custom'
                FROM {DOMAIN} WHERE code='CULTURE_TOURISM' AND deleted=0;
                SELECT setval('{SEQUENCE}',1,true);""", "polluted")
            before = snapshot("polluted")
            custom = [row for row in table_rows(RELATION, "polluted") if row["biz_category_id"] == CUSTOM_ID]
            sql(migration, "polluted")
            assert mapping("polluted") == expected, "污染旧关系没有被完整替换"
            preserved(before, "polluted")
            assert [row for row in table_rows(RELATION, "polluted") if row["biz_category_id"] == CUSTOM_ID] == custom
            replay("polluted")
            assert value(f"SELECT nextval('{SEQUENCE}') > (SELECT max(id) FROM {RELATION})", "polluted")
            passed("污染旧关联完整替换；额外自定义分类及关系保留；落后序列安全修正且重放不再推进")

            clone("renumbered")
            sql(f"UPDATE {DOMAIN} SET id=id+1000; UPDATE {RELATION} SET data_domain_id=data_domain_id+1000;", "renumbered")
            before = snapshot("renumbered")
            sql(migration, "renumbered")
            assert mapping("renumbered") == expected, "关系依赖固定数据库编号"
            preserved(before, "renumbered")
            assert value(f"SELECT min(data_domain_id)>1000 FROM {RELATION} WHERE deleted=0", "renumbered")
            replay("renumbered")
            passed("全部数据域编号平移后仍按编码正确关联，不依赖固定域 ID")

            for database, fixture in (
                ("missing", f"DELETE FROM {DOMAIN} WHERE code='EDUCATION'"),
                ("disabled", f"UPDATE {DOMAIN} SET status=1 WHERE code='EDUCATION'"),
                # 模拟允许空状态的遗留表；当前正式基线已使用非空约束。
                ("null_status", f"ALTER TABLE {DOMAIN} ALTER COLUMN status DROP NOT NULL; "
                                f"UPDATE {DOMAIN} SET status=NULL WHERE code='EDUCATION'"),
                ("deleted_domain", f"UPDATE {DOMAIN} SET deleted=1 WHERE code='EDUCATION'"),
                ("duplicate", f"INSERT INTO {DOMAIN}(id,name,code,status,deleted) "
                              f"SELECT 900001,name,code,status,deleted FROM {DOMAIN} WHERE code='EDUCATION'"),
            ):
                clone(database)
                sql(fixture, database)
                rejected(database)
                passed(f"{database}：缺失、禁用、已删除或重名重复编码数据域在写入前拒绝，全部行及序列不变")

            clone("write_failure")
            sql(f"""CREATE FUNCTION reject_relation_write() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION '模拟写入失败'; END $$;
                CREATE TRIGGER reject_relation_write BEFORE INSERT ON {RELATION}
                FOR EACH ROW EXECUTE FUNCTION reject_relation_write();""", "write_failure")
            rejected("write_failure")
            passed("关系插入阶段异常时，已变更的旧关系和已重启的序列完整回滚")

            sql(baseline, "fresh")
            assert mapping("fresh") == expected, "新库与已有库升级结果不一致"
            for table in (CATEGORY, DOMAIN):
                assert table_rows(table, "fresh") == table_rows(table, "original"), f"新库基线意外修改 {table}"
            replay("fresh")
            passed("全新数据库完整基线可执行，与已有库升级得到相同域映射，重放行和序列不变")
            print(f"完成：{checks} 项真实 PostgreSQL 回归，跳过 0 项；临时集群将停止并清理。", flush=True)
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
