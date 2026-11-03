"""在独立临时 PostgreSQL 集群验证旧资产持久化清理；不接受业务库连接参数。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = "sql/postgresql/datahub-center.sql"
MIGRATION = ROOT / "sql/postgresql/migrations/0196_asset_remove-unused-persistence.sql"
PREREQUISITE = ROOT / "sql/postgresql/migrations/0195_governance_government-datameta-categories.sql"
RETIRED = ("dh_asset_data_item", "dh_asset_delivery_op")
SEQUENCES = tuple(name + "_seq" for name in RETIRED)
PRESERVED = ("dh_market_delivery_operation", "dh_market_delivery_operation_seq",
             "dh_asset_grant_source", "dh_asset_catalog_version", "dh_asset_review_ticket")
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'


def previous_baseline() -> str:
    """从版本历史取得截至 0194 的完整基线，使提交后仍能复现真实升级。"""
    revisions = subprocess.run(["git", "rev-list", "HEAD", "--", BASELINE_PATH], cwd=ROOT,
                               check=True, capture_output=True, text=True).stdout.splitlines()
    for revision in revisions:
        result = subprocess.run(["git", "show", f"{revision}:{BASELINE_PATH}"], cwd=ROOT,
                                check=True, capture_output=True)
        content = result.stdout.decode("utf-8-sig")
        if content.splitlines()[0] == "-- Included migrations through: 0194":
            return content
    raise AssertionError("Git 历史中找不到截至 0194 的完整基线")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-bin", type=Path, help="含 initdb、pg_ctl、psql 的 PostgreSQL bin 目录")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    binary = args.postgres_bin or Path(shutil.which("initdb") or "").parent
    for name in ("initdb", "pg_ctl", "psql"):
        if not (binary / (name + suffix)).is_file():
            raise SystemExit(f"未找到 PostgreSQL {name}；请指定 --postgres-bin")
    environment = {key: value for key, value in os.environ.items() if not key.upper().startswith("PG")}
    environment["PGCLIENTENCODING"] = "UTF8"
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    baseline = (ROOT / BASELINE_PATH).read_text(encoding="utf-8-sig")
    migration = MIGRATION.read_text(encoding="utf-8-sig")
    prerequisite = PREREQUISITE.read_text(encoding="utf-8-sig")
    historical = previous_baseline()
    assert baseline.splitlines()[0] == "-- Included migrations through: 0196"
    assert baseline.count(migration.strip()) == 1, "完整基线必须准确包含一次 0196"

    with tempfile.TemporaryDirectory(prefix="datahub-unused-asset-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments, input_text: str | None = None, required: bool = True):
            command = [str(binary / (name + suffix)), *map(str, arguments)]
            if name == "pg_ctl":
                # 后台服务器输出到临时文件，避免 Windows 继承管道而阻止调用结束。
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
        run("initdb", "-D", data, "-U", "unused_asset_test", "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str = "upgrade", required: bool = True):
                # 连接地址、账号和数据库均由脚本控制，不读取任何业务连接配置。
                assert database in {"postgres", "upgrade", "fresh"}
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", "unused_asset_test",
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate",
                           input_text=PREFIX + statement, required=required)

            def value(statement: str, database: str = "upgrade"):
                return json.loads(sql(statement, database).stdout.strip())

            def existence(database: str = "upgrade"):
                names = ",".join(f"'{name}'" for name in RETIRED + SEQUENCES + PRESERVED)
                return value(f"SELECT jsonb_object_agg(name,to_regclass(name) IS NOT NULL) "
                             f"FROM unnest(ARRAY[{names}]) AS t(name)", database)

            def schema(database: str = "upgrade"):
                # 使用稳定的名称及定义比较结构，排除每次安装都变化的内部 OID。
                return value("""
                    SELECT jsonb_build_object(
                      'relations',(SELECT jsonb_object_agg(c.relname,jsonb_build_array(c.relkind,obj_description(c.oid)))
                        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname='datahub-center' AND c.relkind IN('r','p','v','m','S')),
                      'columns',(SELECT jsonb_object_agg(c.relname||'.'||a.attname,
                        jsonb_build_array(format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
                          pg_get_expr(d.adbin,d.adrelid),col_description(c.oid,a.attnum)))
                        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                        JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
                        WHERE n.nspname='datahub-center' AND c.relkind IN('r','p','v','m')),
                      'constraints',(SELECT jsonb_object_agg(c.relname||'.'||con.conname,pg_get_constraintdef(con.oid))
                        FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
                        JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='datahub-center'),
                      'indexes',(SELECT jsonb_object_agg(indexname,indexdef) FROM pg_indexes
                        WHERE schemaname='datahub-center'),
                      'sequences',(SELECT jsonb_object_agg(sequencename,jsonb_build_array(data_type,start_value,
                        min_value,max_value,increment_by,cycle,cache_size)) FROM pg_sequences
                        WHERE schemaname='datahub-center'),
                      'functions',(SELECT jsonb_object_agg(p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',
                        pg_get_functiondef(p.oid)) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                        WHERE n.nspname='datahub-center' AND p.prokind IN('f','p')),
                      'triggers',(SELECT jsonb_object_agg(c.relname||'.'||t.tgname,pg_get_triggerdef(t.oid))
                        FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                        JOIN pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname='datahub-center' AND NOT t.tgisinternal));
                """, database)

            def row_snapshot(database: str = "upgrade"):
                # 全部保留表逐行比较，包含审计时间；新旧安装间另比较完整结构及各表行数。
                return value("""
                    CREATE TEMP TABLE verify_rows(name text,total bigint,digest text);
                    DO $snapshot$ DECLARE t record; BEGIN
                      FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='datahub-center'
                               AND tablename NOT IN('dh_asset_data_item','dh_asset_delivery_op') LOOP
                        EXECUTE format('INSERT INTO verify_rows SELECT %L,count(*),md5(COALESCE(string_agg(to_jsonb(r)::text,'''' ORDER BY to_jsonb(r)::text),'''')) FROM %I.%I r',
                                       t.tablename,'datahub-center',t.tablename);
                      END LOOP;
                    END $snapshot$;
                    SELECT jsonb_object_agg(name,jsonb_build_array(total,digest)) FROM verify_rows;
                """, database)

            def retired_snapshot():
                state = {"exists": existence(), "schema": schema()}
                for name in RETIRED:
                    state[name] = value(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM {name} t")
                for name in SEQUENCES:
                    state[name] = value(f"SELECT jsonb_build_array(last_value,is_called) FROM {name}")
                return state

            checks = 0

            def passed(description: str) -> None:
                nonlocal checks
                checks += 1
                print(f"通过 {checks:02d}：{description}", flush=True)

            def assert_rejected(code: str) -> None:
                before = retired_snapshot()
                result = sql(migration, required=False)
                assert result.returncode and code in result.stderr, result.stderr
                assert retired_snapshot() == before, "失败后旧表、序列、记录或外部依赖未完整保留"

            sql("CREATE DATABASE upgrade; CREATE DATABASE fresh;", "postgres")
            sql(baseline, "fresh")
            fresh_exists = existence("fresh")
            assert all(not fresh_exists[name] for name in RETIRED + SEQUENCES)
            assert all(fresh_exists[name] for name in PRESERVED)
            fresh_schema, fresh_rows = schema("fresh"), row_snapshot("fresh")
            sql(migration, "fresh")
            assert schema("fresh") == fresh_schema and row_snapshot("fresh") == fresh_rows
            passed("新库完整基线可导入，两旧表及两序列不存在，当前交付与编目持久化保留，0196 重放无变化")

            sql(historical)
            sql(prerequisite)
            assert all(existence().values()), "真实 0194 基线加 0195 必须包含旧对象和当前交付对象"
            before_rows = row_snapshot()
            passed("真实完整 0194 基线加 0195 升级前置成功，旧表及序列均存在")

            for name in RETIRED:
                for deleted in (0, 1):
                    if name == "dh_asset_data_item":
                        sql(f"INSERT INTO {name}(id,asset_id,item_code,item_name,deleted) "
                            f"VALUES(-900001,-900001,'verify_retired','旧数据项验证',{deleted})")
                    else:
                        sql(f"INSERT INTO {name}(id,action,deleted) VALUES(-900001,'GRANT',{deleted})")
                    assert_rejected("P0001")
                    sql(f"DELETE FROM {name} WHERE id=-900001")
                    passed(f"{name} 含 deleted={deleted} 的记录时拒绝删除，两个旧表和序列均完整保留")

            for name in RETIRED:
                sql(f"CREATE VIEW verify_retired_view AS SELECT id FROM {name}")
                assert_rejected("2BP01")
                sql("DROP VIEW verify_retired_view")
                passed(f"{name} 存在外部视图时拒绝，视图及两表两序列均保留")
                sql(f"CREATE TABLE verify_retired_fk(id bigint PRIMARY KEY,ref_id bigint REFERENCES {name}(id))")
                assert_rejected("2BP01")
                sql("DROP TABLE verify_retired_fk")
                passed(f"{name} 存在外部外键时拒绝，外键及两表两序列均保留")

            for name in SEQUENCES:
                sql(f"CREATE TABLE verify_retired_sequence(id bigint DEFAULT nextval('{name}'))")
                assert_rejected("2BP01")
                sql("DROP TABLE verify_retired_sequence")
                passed(f"{name} 存在外部默认值依赖时拒绝，两表两序列和外部依赖均保留")

            sql(migration)
            assert existence() == fresh_exists
            assert schema() == fresh_schema, "新基线与真实旧基线升级后的完整结构不一致"
            assert row_snapshot() == before_rows, "清理修改了其他表的记录"
            assert {name: row[0] for name, row in before_rows.items()} == {
                name: row[0] for name, row in fresh_rows.items()}, "新库与升级的各表行数不一致"
            sql(migration)
            assert existence() == fresh_exists and schema() == fresh_schema and row_snapshot() == before_rows
            passed("已有库升级与新库完整结构、各表行数一致；保留表全部行内容不变，重复执行无变化")
            print(f"完成：{checks} 项真实 PostgreSQL 回归，跳过 0 项；临时集群将停止并清理。", flush=True)
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
