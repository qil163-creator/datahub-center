"""在独立临时 PostgreSQL 集群验证运维实例迁移、重放、新库基线及真实 MyBatis 查询。"""
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


ROOT = Path(__file__).resolve().parents[6]
NAME = "0217_dataforge_operations-instances.sql"
MARKER = "-- 新库最终状态来源：" + NAME
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-bin", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--skip-maven", action="store_true")
    args = parser.parse_args()
    binary = args.postgres_bin or Path(shutil.which("initdb") or "").parent
    suffix = ".exe" if os.name == "nt" else ""
    for command in ("initdb", "pg_ctl", "psql"):
        if not (binary / (command + suffix)).is_file(): raise SystemExit("未找到 PostgreSQL " + command)
    migration = (ROOT / "sql/postgresql/migrations" / NAME).read_text(encoding="utf-8-sig")
    baseline = (ROOT / "sql/postgresql/datahub-center.sql").read_text(encoding="utf-8-sig")
    assert baseline.count(MARKER) == 1
    previous, appended = baseline.split(MARKER)
    # 并行任务可以在本迁移后追加新的尾段，当前段必须与编号脚本逐字相同。
    own_segment = appended.split("-- 新库最终状态来源：", 1)[0]
    assert own_segment.strip() == migration.strip()
    env = dict(os.environ)
    for key in ("PGHOST", "PGPORT", "PGUSER", "PGPASSWORD", "PGDATABASE", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        env.pop(key, None)
    env["PGCLIENTENCODING"] = "UTF8"
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home)
        env["PATH"] = str(args.java_home / "bin") + os.pathsep + env["PATH"]
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    with tempfile.TemporaryDirectory(prefix="datahub-operations-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(command, *arguments, text=None, timeout=180):
            command_path = binary / (command + suffix)
            if command == "pg_ctl":
                with (directory / "pg_ctl.log").open("w", encoding="utf-8") as log:
                    result = subprocess.run([str(command_path), *map(str, arguments)], env=env, stdout=log,
                                            stderr=subprocess.STDOUT, creationflags=flags, timeout=60)
                assert result.returncode == 0, (directory / "pg_ctl.log").read_text(encoding="utf-8", errors="replace")[-3000:]
                return ""
            result = subprocess.run([str(command_path), *map(str, arguments)], input=text, text=True,
                                    encoding="utf-8", errors="replace", capture_output=True, env=env,
                                    creationflags=flags, timeout=timeout)
            assert result.returncode == 0, result.stdout[-1200:] + result.stderr[-5000:]
            return result.stdout.strip()

        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
        account = "ops_test"; data = directory / "data"
        run("initdb", "-D", data, "-U", account, "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement, database="operations_test"):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", account,
                           "-d", database, "-v", "ON_ERROR_STOP=1", text=PREFIX + statement)

            sql("CREATE DATABASE operations_test; CREATE DATABASE fresh_test;", "postgres")
            sql(previous)
            sql(migration)
            first = sql("SELECT md5(string_agg(to_jsonb(t)::text,'' ORDER BY id)) FROM dh_dataforge_ops_development_snapshot t;")
            menu_first = sql("SELECT md5(string_agg(to_jsonb(t)::text,'' ORDER BY id)) FROM system_menu t;")
            sql(migration)
            assert first == sql("SELECT md5(string_agg(to_jsonb(t)::text,'' ORDER BY id)) FROM dh_dataforge_ops_development_snapshot t;")
            assert menu_first == sql("SELECT md5(string_agg(to_jsonb(t)::text,'' ORDER BY id)) FROM system_menu t;")
            sql(baseline, "fresh_test")
            for database in ("operations_test", "fresh_test"):
                assert sql("SELECT count(*) FROM dh_dataforge_ops_development_snapshot WHERE deleted=0", database) == "24"
                assert sql("SELECT count(*) FROM dh_dataforge_integration_record WHERE deleted=0", database) == "59"
                assert sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_dataforge_ops_development_snapshot' AND column_name='tenant_id'", database) == "0"
                assert sql("SELECT count(*) FROM system_menu WHERE component IN ('dataforge/ops/integration/index','dataforge/ops/development/index') AND deleted=0", database) == "2"
                assert sql("SELECT count(*) FROM system_menu o JOIN system_menu a ON a.id=5987 WHERE o.parent_id=a.parent_id AND o.path='operations' AND o.sort=a.sort+1 AND o.deleted=0", database) == "1"
                assert sql("SELECT count(*) FROM dh_dataforge_ops_development_snapshot WHERE log_content ~ '(jdbc:|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|10\\.[0-9]+\\.)'", database) == "0"
            if not args.skip_maven:
                maven = shutil.which("mvn.cmd") or shutil.which("mvn")
                assert maven
                test_env = {**env, "OPS_TEST_JDBC_URL": f"jdbc:postgresql://127.0.0.1:{port}/operations_test?currentSchema=datahub-center",
                            "OPS_TEST_JDBC_USER": account}
                if args.output_dir: test_env["OPS_TEST_OUTPUT_DIR"] = str(args.output_dir.resolve())
                log_path = directory / "maven.log"
                with log_path.open("w", encoding="utf-8") as log:
                    result = subprocess.run([maven, "-pl", ":yudao-module-dataforge-server", "-am",
                                             "-Dtest=OpsInstanceServiceTest,OpsInstanceMapperPostgreSqlTest",
                                             "-Dsurefire.failIfNoSpecifiedTests=false", "test", "-q"],
                                            cwd=ROOT, env=test_env, stdout=log, stderr=subprocess.STDOUT,
                                            creationflags=flags, timeout=900)
                assert result.returncode == 0, log_path.read_text(encoding="utf-8", errors="replace")[-9000:]
            print(json.dumps({"result": "通过", "postgresql": "真实独立临时实例", "upgrade": "通过", "replay": "通过",
                              "fresh_baseline": "通过", "integration_records": 59, "development_records": 24,
                              "mybatis": "未执行" if args.skip_maven else "通过", "skipped": 0}, ensure_ascii=False))
        finally:
            if started: run("pg_ctl", "-D", data, "-m", "immediate", "-w", "stop")


if __name__ == "__main__": main()
