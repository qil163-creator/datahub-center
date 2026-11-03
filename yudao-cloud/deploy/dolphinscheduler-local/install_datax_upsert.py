#!/usr/bin/env python3
"""在现有本地 DS 安装幂等插件并持久化新元数据；不创建服务、不占用额外端口。"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time

from smoke import Client
from prepare_dev_native_runtime import read_json

ROOT = Path(__file__).resolve().parent
CONTAINER = "datahub-ds-local-standalone-1"
DOCKER = ["docker", "--context", "desktop-linux"]


def run(*args, input=None):
    result = subprocess.run([*DOCKER, *args], input=input, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("本地 DS 维护命令失败，请检查容器状态（敏感输出已隐藏）")
    return result.stdout


def check_instances(client, recover_stalled_submissions=False):
    preserved = []
    terminal = {"SUCCESS", "FAILURE", "STOP", "PAUSE", "STOPPED", "KILL"}
    for project in client.request("GET", "/projects/list"):
        prefix = f'/projects/{project["code"]}'
        page_no = 1
        while True:
            page = client.request("GET", prefix + '/workflow-instances', {"pageNo": page_no, "pageSize": 100})
            rows = page["totalList"]
            for row in rows:
                if row["state"] in terminal:
                    continue
                if not recover_stalled_submissions:
                    raise ValueError("存在未结束实例，请待执行完成后安装")
                tasks = client.request("GET", prefix + f'/workflow-instances/{row["id"]}/tasks')
                tasks = tasks.get("taskList", []) if isinstance(tasks, dict) else tasks
                serial_wait = row["state"] == "SERIAL_WAIT"
                submitted = row["state"] == "SUBMITTED_SUCCESS"
                allowed = terminal | {"SUBMITTED_SUCCESS"} if serial_wait else {"SUBMITTED_SUCCESS"}
                if (not tasks and not (serial_wait or submitted)) or any(task.get("state") not in allowed for task in tasks):
                    raise ValueError("恢复模式仅允许尚未执行的提交任务，或没有执行中任务的串行排队实例")
                preserved.append(row["id"])
            if len(rows) < 100:
                break
            page_no += 1
    if preserved:
        # 仅返回引擎进程数量，不输出可能含私密参数的进程命令行。
        engines = run("exec", CONTAINER, "python3", "-c", """from pathlib import Path
count=0
for item in Path('/proc').glob('[0-9]*/cmdline'):
    try: args=item.read_bytes().decode(errors='replace').split('\\0')
    except OSError: continue
    if any(value in ('com.alibaba.datax.core.Engine','org.apache.spark.deploy.SparkSubmit','org.apache.spark.executor.CoarseGrainedExecutorBackend')
           or value.startswith('org.apache.flink.') or value == '/opt/datax/bin/datax.py' for value in args): count+=1
print(count)
""").strip()
        if engines != "0":
            raise ValueError("恢复模式发现活动引擎进程，拒绝重启")
    return preserved


def install(private, archive, recover_stalled_submissions=False):
    config = read_json(private, private=True)
    client = Client(config["dolphinscheduler"]["server-url"])
    if client.base_url != "http://127.0.0.1:12345/dolphinscheduler":
        raise ValueError("仅维护正常本地 DS 的 12345 端口")
    client.token = config["dolphinscheduler"]["token"]
    source = run("exec", CONTAINER, "cat", "/opt/dolphinscheduler/standalone-server/conf/application.yaml")
    in_memory = "jdbc:h2:mem:dolphinscheduler" in source
    tokens = []
    if in_memory:
        # 只接管没有业务对象的初始内存库；已有业务数据必须先执行专门迁移。
        for path in ("/projects/list", "/environment/query-environment-list"):
            if client.request("GET", path):
                raise ValueError("当前内存库已有业务对象，拒绝重启；请先迁移元数据")
        if client.request("GET", "/datasources", {"pageNo": 1, "pageSize": 1})["total"]:
            raise ValueError("当前内存库已有数据源，拒绝重启")
        tokens = client.request("GET", "/access-tokens", {"pageNo": 1, "pageSize": 100, "userId": 1})["totalList"]
        if any(row["userId"] != 1 or not re.fullmatch(r"[a-fA-F0-9]{32,64}", row["token"]) for row in tokens):
            raise ValueError("初始访问凭据格式不符合保留条件")
    else:
        check_instances(client, recover_stalled_submissions)
    with tarfile.open(archive) as bundle:
        root = ROOT / "runtime/datax-upsert"
        root.mkdir(parents=True, exist_ok=True)
        for member in bundle.getmembers():
            if member.name.startswith("/") or ".." in Path(member.name).parts or not (member.isfile() or member.isdir()):
                raise ValueError("插件归档包含非预期路径或链接")
        bundle.extractall(root)
    run("cp", str(archive), f"{CONTAINER}:/tmp/datahub-datax-upsert.tar.gz")
    run("exec", CONTAINER, "tar", "-xzf", "/tmp/datahub-datax-upsert.tar.gz", "-C", "/")
    run("cp", str(ROOT / "start-local.sh"), f"{CONTAINER}:/opt/dolphinscheduler/start-local.sh")
    # 既有容器 CMD 直接调用官方 start.sh；保留上游脚本并接入统一冷启动入口，避免重建丢失其他插件。
    run("exec", CONTAINER, "bash", "-c", """set -euo pipefail
entry=/opt/dolphinscheduler/standalone-server/bin/start.sh
upstream=/opt/dolphinscheduler/standalone-server/bin/start-upstream.sh
if [[ ! -f "$upstream" ]]; then
  grep -q 'org.apache.dolphinscheduler.StandaloneServer' "$entry" || { echo '上游启动入口不符合预期' >&2; exit 1; }
  cp -p "$entry" "$upstream"
fi
grep -q 'org.apache.dolphinscheduler.StandaloneServer' "$upstream"
""")
    run("cp", str(ROOT / "start-standalone.sh"), f"{CONTAINER}:/opt/dolphinscheduler/standalone-server/bin/start.sh")
    run("exec", CONTAINER, "chmod", "755", "/opt/dolphinscheduler/start-local.sh", "/opt/dolphinscheduler/standalone-server/bin/start.sh")
    if in_memory:
        run("exec", CONTAINER, "bash", "/opt/dolphinscheduler/start-local.sh", "--prepare-only")
        statements = []
        for row in tokens:
            expiry = row["expireTime"]
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", expiry):
                raise ValueError("凭据有效期格式无效")
            statements.append("MERGE INTO t_ds_access_token (id,user_id,token,expire_time,create_time,update_time) KEY(id) "
                              f"VALUES ({int(row['id'])},1,'{row['token']}','{expiry}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);")
        with tempfile.TemporaryDirectory(prefix="datahub-ds-maintenance-") as directory:
            restore = Path(directory) / "restore.sql"
            restore.write_text("\n".join(statements))
            os.chmod(restore, 0o600)
            run("cp", str(restore), f"{CONTAINER}:/tmp/datahub-restore-token.sql")
            try:
                run("exec", CONTAINER, "chown", "datahub:datahub", "/tmp/datahub-restore-token.sql")
                run("exec", "-u", "datahub", CONTAINER, "/opt/java/openjdk/bin/java", "-cp",
                    "/opt/dolphinscheduler/libs/h2-2.2.220.jar", "org.h2.tools.RunScript", "-url",
                    "jdbc:h2:file:/opt/dolphinscheduler/logs/.metadata/dolphinscheduler;MODE=MySQL;DATABASE_TO_LOWER=true",
                    "-user", "sa", "-script", "/tmp/datahub-restore-token.sql")
            finally:
                run("exec", CONTAINER, "rm", "-f", "/tmp/datahub-restore-token.sql")
        source = source.replace("jdbc:h2:mem:dolphinscheduler", "jdbc:h2:file:/opt/dolphinscheduler/logs/.metadata/dolphinscheduler")
        if "  sql:\n    init:\n" not in source:
            raise ValueError("DS 配置缺少预期的 SQL 初始化段，拒绝重启")
        source = source.replace("  sql:\n    init:\n", "  sql:\n    init:\n      mode: never\n", 1)
        run("exec", "-i", CONTAINER, "bash", "-c", "cat > /opt/dolphinscheduler/standalone-server/conf/application.yaml", input=source)
    # 保留当前容器可写层中的其他原生插件，只重启同一个服务。
    preserved = [] if in_memory else check_instances(client, recover_stalled_submissions)
    run("restart", CONTAINER)
    for _ in range(60):
        try:
            client.request("GET", "/projects/list")
            break
        except Exception:
            time.sleep(2)
    else:
        raise RuntimeError("本地 DS 尚未恢复，请查看现有容器日志")
    print(json.dumps({"container": CONTAINER, "port": 12345, "plugin": "DATAX_UPSERT", "persistentMetadata": True, "credentialsPreserved": True, "queuedInstancesPreserved": preserved}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-config", required=True, type=Path)
    parser.add_argument("--archive", type=Path, default=ROOT / "runtime/datax-upsert.tar.gz")
    parser.add_argument("--recover-stalled-submissions", action="store_true", help="仅在任务尚未执行或串行排队且无活动引擎时，保留停滞实例并修复单 JVM 注册状态")
    args = parser.parse_args()
    install(args.private_config, args.archive, args.recover_stalled_submissions)
