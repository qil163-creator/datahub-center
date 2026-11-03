"""在无外部网络和端口映射的临时 PostgreSQL 容器中验证日志、消息功能删除。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sql/postgresql/migrations/0262_dataforge_remove-log-message.sql"
BASELINE = ROOT / "sql/postgresql/datahub-center.sql"
PERMISSION_TABLES = ("system_menu", "system_role_menu", "system_tenant_package")
RETIRED_TABLES = (
    "dh_dataforge_log_file_state", "dh_dataforge_log_run", "dh_dataforge_log_job",
    "dh_dataforge_message_log", "dh_dataforge_message_run", "dh_dataforge_message_job",
)
PREFIX = 'SET search_path TO "datahub-center",pg_catalog;\n'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="postgres:18-alpine", help="已存在的本地 PostgreSQL 镜像")
    args = parser.parse_args()
    baseline = BASELINE.read_text(encoding="utf-8-sig")
    migration = MIGRATION.read_text(encoding="utf-8-sig")
    name = "datahub-log-message-verify-" + uuid.uuid4().hex[:12]
    checks = 0

    def run(*arguments: str, input_text: str | None = None, required: bool = True, timeout: int = 180):
        result = subprocess.run(["docker", *map(str, arguments)], input=input_text, text=True,
                                encoding="utf-8", errors="replace", capture_output=True, timeout=timeout)
        if required and result.returncode:
            raise AssertionError(f"Docker 操作失败：\n{result.stdout[-1500:]}\n{result.stderr[-5000:]}")
        return result

    def sql(statement: str, database: str = "upgrade", required: bool = True):
        return run("exec", "-i", name, "psql", "-X", "-qAt", "-U", "postgres", "-d", database,
                   "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                   input_text=PREFIX + statement, required=required)

    def value(statement: str, database: str = "upgrade"):
        return json.loads(sql(statement, database).stdout.strip())

    def rows(table: str, database: str = "upgrade"):
        assert table in (*PERMISSION_TABLES, *RETIRED_TABLES, "infra_job", "infra_job_log",
                         "dh_dataforge_integration_task", "dh_dataforge_push_config")
        return value(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM {table} t", database)

    def snapshot(database: str = "upgrade"):
        return {table: rows(table, database) for table in PERMISSION_TABLES}

    def passed(description: str) -> None:
        nonlocal checks
        checks += 1
        print(f"通过 {checks:02d}：{description}", flush=True)

    # 使用基线字段类型，尤其保留套餐 JSON 的 varchar(4096) 限制。
    schema = 'CREATE SCHEMA "datahub-center"; CREATE SEQUENCE "datahub-center".system_menu_id_seq;\n'
    for table in PERMISSION_TABLES:
        match = re.search(rf'CREATE TABLE "datahub-center"\."{table}" \(.*?\n\)\s*;', baseline, re.DOTALL)
        assert match, f"基线未找到 {table}"
        schema += match[0] + f'\nALTER TABLE "datahub-center"."{table}" ADD PRIMARY KEY(id);\n'

    def reset(extra: str = "") -> None:
        sql("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE;" + schema + """
            INSERT INTO system_menu(id,name,permission,type,parent_id,path,component,component_name) VALUES
                (900,'数据接入','',1,0,'integrate',NULL,NULL),
                (910,'日志采集','',1,900,'logs',NULL,NULL),
                (911,'采集任务','dataforge:log-job:query',2,910,'job','dataforge/logcollect/job/index','DataforgeLogCollectJob'),
                (912,'日志新增','dataforge:log-job:create',3,911,'',NULL,NULL),
                (913,'执行监控','dataforge:log-run:query',3,911,'',NULL,NULL),
                (914,'无权限按钮','',3,911,'',NULL,NULL),
                (915,'旧独立监控','',2,910,'run','/dataforge/logcollect/run/index/',NULL),
                (920,'消息集成','',1,900,'message',NULL,NULL),
                (921,'集成任务','dataforge:message-job:query',2,920,'job','dataforge/message/job/index','DataforgeMessageJob'),
                (922,'消息新增','dataforge:message-job:create',3,921,'',NULL,NULL),
                (923,'运行删除','dataforge:message-run:delete',3,921,'',NULL,NULL),
                (924,'已删除权限','dataforge:message-job:update',3,921,'',NULL,NULL),
                (930,'离线开发','dataforge:integration-task:query',2,900,'offline','dataforge/integration/task/index',NULL),
                (931,'数据推送','dataforge:push-config:query',2,900,'push','dataforge/push/config/index',NULL);
            UPDATE system_menu SET status=1,deleted=1 WHERE id=924;
            INSERT INTO system_role_menu(id,role_id,menu_id) VALUES
                (1,1,910),(2,1,911),(3,2,914),(4,1,920),(5,2,923),(6,3,924),(910,4,930),(8,1,931);
            INSERT INTO system_tenant_package(id,name,menu_ids,updater) VALUES
                (1,'混合授权','[930,"911",912,931,"930",910,914,920,923,924]','原用户'),
                (2,'空套餐','[]','原用户'),(3,'仅退役授权','["924"]','原用户'),
                (4,'不相关套餐','["930",931]','原用户');
            CREATE TABLE infra_job(id bigint PRIMARY KEY, handler_name text);
            CREATE TABLE infra_job_log(id bigint PRIMARY KEY, handler_name text);
            INSERT INTO infra_job VALUES(1,'dataforgeLogCollectJob'),(2,'dataAccessSyncJob'),(3,'dataPushSyncJob');
            INSERT INTO infra_job_log SELECT * FROM infra_job;
            CREATE TABLE dh_dataforge_integration_task(id bigint PRIMARY KEY, name text);
            INSERT INTO dh_dataforge_integration_task VALUES(1,'保留离线与实时集成');
            CREATE TABLE dh_dataforge_push_config(id bigint PRIMARY KEY, channel_json jsonb);
            INSERT INTO dh_dataforge_push_config VALUES(1,'{"rabbit":{"exchange":"保留交换机"}}');
        """)
        for index, table in enumerate(RETIRED_TABLES):
            sql(f"CREATE SEQUENCE {table}_seq; CREATE TABLE {table}"
                f"(id bigint PRIMARY KEY DEFAULT nextval('{table}_seq'), data text);"
                f"INSERT INTO {table}(data) VALUES('退役数据');"
                + (f"ALTER SEQUENCE {table}_seq OWNED BY {table}.id;" if index % 2 == 0 else ""))
        if extra:
            sql(extra)

    def assert_removed(database: str = "upgrade") -> None:
        names = ",".join("'" + table + suffix + "'" for table in RETIRED_TABLES for suffix in ("", "_seq"))
        assert value(f"SELECT count(*) FROM pg_class WHERE relnamespace='\"datahub-center\"'::regnamespace "
                     f"AND relname IN ({names})", database) == 0

    def assert_unchanged(before) -> None:
        assert snapshot() == before
        for table in RETIRED_TABLES:
            assert rows(table)[0]["data"] == "退役数据"
        assert len(rows("infra_job")) == 3
        assert len(rows("infra_job_log")) == 3

    run("image", "inspect", args.image)
    started = False
    try:
        # 不提供业务连接参数；容器不发布端口、不挂载文件，也不能访问外部网络。
        run("run", "--detach", "--pull=never", "--name", name, "--network", "none",
            "--env", "POSTGRES_HOST_AUTH_METHOD=trust", args.image)
        started = True
        for _ in range(150):
            logs = run("logs", name)
            initialized = "PostgreSQL init process complete" in logs.stdout + logs.stderr
            if initialized and run("exec", name, "pg_isready", "-U", "postgres", required=False).returncode == 0:
                break
            time.sleep(0.2)
        else:
            raise AssertionError("临时 PostgreSQL 未在时限内就绪")
        sql("CREATE DATABASE upgrade; CREATE DATABASE fresh;", "postgres")

        reset()
        sql(migration)
        assert_removed()
        assert [row["id"] for row in rows("system_menu")] == [900, 930, 931]
        assert [(row["id"], row["menu_id"]) for row in rows("system_role_menu")] == [(8, 931), (910, 930)]
        packages = {row["id"]: row for row in rows("system_tenant_package")}
        assert packages[1]["menu_ids"] == '[930,931,"930"]'
        assert packages[3]["menu_ids"] == "[]"
        assert packages[2]["updater"] == packages[4]["updater"] == "原用户"
        assert [row["handler_name"] for row in rows("infra_job")] == ["dataAccessSyncJob", "dataPushSyncJob"]
        assert rows("infra_job_log") == rows("infra_job")
        assert rows("dh_dataforge_integration_task")[0]["name"] == "保留离线与实时集成"
        assert rows("dh_dataforge_push_config")[0]["channel_json"]["rabbit"]["exchange"] == "保留交换机"
        passed("首跑删除六张专属表及拥有或未拥有的序列，清理菜单、授权、套餐和旧调度且保留其他功能")

        after = snapshot()
        sql(migration)
        assert snapshot() == after
        assert_removed()
        passed("迁移重放不改变剩余权限和审计字段")
        sql("DROP TABLE infra_job_log,infra_job;")
        sql(migration)
        assert snapshot() == after
        passed("缺少历史 infra 调度表时仍可执行")

        reset("INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES(940,'其他功能',2,911,'other:query');")
        before = snapshot()
        result = sql(migration, required=False)
        assert result.returncode and "其他功能" in result.stderr, result.stderr
        assert_unchanged(before)
        passed("退役菜单混挂其他功能时拒绝执行，所有删除完整回滚")

        reset("CREATE TABLE other_business(id bigint PRIMARY KEY, job_id bigint REFERENCES dh_dataforge_message_job(id));"
              "INSERT INTO other_business VALUES(1,1);")
        before = snapshot()
        result = sql(migration, required=False)
        assert result.returncode and "2BP01" in result.stderr, result.stderr
        assert_unchanged(before)
        assert value("SELECT count(*) FROM other_business") == 1
        passed("外部外键阻断非级联删表，先前已删表、权限和旧调度均完整回滚")

        sql(baseline, "fresh")
        assert_removed("fresh")
        assert value("""SELECT count(*) FROM system_menu WHERE permission ~ '^dataforge:(log|message)-(job|run):'
            OR coalesce(component,'') ~ '^/?dataforge/(logcollect|message)(/|$)'""", "fresh") == 0
        fresh = snapshot("fresh")
        sql(migration, "fresh")
        assert snapshot("fresh") == fresh
        passed("完整新库基线可执行且不再创建退役对象，0262 重放保持权限全量快照不变")
        print(f"完成：{checks} 项真实 PostgreSQL 回归，跳过 0 项；临时容器将删除。", flush=True)
    finally:
        if started:
            run("rm", "--force", "--volumes", name, required=False, timeout=60)


if __name__ == "__main__":
    main()
