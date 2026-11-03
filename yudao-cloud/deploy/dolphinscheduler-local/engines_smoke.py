#!/usr/bin/env python3
"""通过 DS 原生 DATAX/SPARK/FLINK 插件验证实际引擎，不用 Shell 替代。"""

import argparse
import datetime as dt
import getpass
import json
import os
from pathlib import Path
import secrets
import time

from smoke import Client, wait_for


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("/tmp/datahub-ds-engines-smoke.json"))
    parser.add_argument("--private-config", type=Path)
    parser.add_argument("--checks", choices=["engines", "compiled", "stream"], default="engines")
    parser.add_argument("--compiled-sql-dir", type=Path)
    args = parser.parse_args()
    connection = json.loads(args.connection.read_text())
    client = Client(connection["server"])
    if os.environ.get("DS_TOKEN"):
        client.token = os.environ["DS_TOKEN"]
    else:
        password = os.environ.get("DS_PASSWORD") or getpass.getpass("本地 DS 密码: ")
        client.session = client.request("POST", "/login", {
            "userName": os.environ.get("DS_USERNAME", "admin"), "userPassword": password,
        })["sessionId"]
    groups = connection["workerGroup"]
    env_common = "\n".join([
        "export FLINK_HOME=/opt/flink", "export SPARK_HOME=/opt/spark",
        "export SPARK_HOME1=/opt/spark", "export SPARK_HOME2=/opt/spark",
        "export DATAX_HOME=/opt/datax", "export DATAX_LAUNCHER=/opt/datax/bin/datax.py",
        "export PYTHON_LAUNCHER=/usr/bin/python3", "export SPARK_LOCAL_IP=127.0.0.1",
    ])
    environment_codes = {}
    existing = client.request("GET", "/environment/query-environment-list")
    for engine, java_home in [("DATAX", "/opt/java/openjdk"), ("SPARK", "/opt/java17"), ("FLINK", "/opt/java17")]:
        name = "datahub-local-" + engine.lower()
        config = env_common + "\nexport JAVA_HOME=" + java_home + "\nexport PATH=$JAVA_HOME/bin:$PATH\n"
        found = next((item for item in existing if item["name"] == name), None)
        if found:
            client.request("POST", "/environment/update", {
                "code": found["code"], "name": name, "config": config, "workerGroups": json.dumps([groups]),
            })
            environment_codes[engine] = found["code"]
        else:
            created = client.request("POST", "/environment/create", {
                "name": name, "config": config, "workerGroups": json.dumps([groups]),
                "description": "本地实际执行引擎，不包含外部系统凭据",
            })
            environment_codes[engine] = created["code"] if isinstance(created, dict) else next(
                item["code"] for item in client.request("GET", "/environment/query-environment-list")
                if item["name"] == name)
    connection["environmentCodes"] = {key: str(value) for key, value in environment_codes.items()}
    connection["sparkMaster"] = "local[1]"
    connection["flinkDeployMode"] = "LOCAL"
    args.connection.write_text(json.dumps(connection, ensure_ascii=False, indent=2) + "\n")
    if args.private_config:
        user = client.request("GET", "/users/get-user-info")
        token = secrets.token_hex(32)
        expires = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(days=1))
        client.request("POST", "/access-tokens", {
            "userId": user["id"], "expireTime": expires.strftime("%Y-%m-%d %H:%M:%S"), "token": token,
        })
        private = {**connection, "token": token}
        fd = os.open(args.private_config, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as output:
            output.write(json.dumps(private, ensure_ascii=False, indent=2) + "\n")
        client.token = token
        client.session = None
    report = {"projectCode": connection["projectCode"], "scope": args.checks, "checks": {}, "status": "RUNNING"}
    try:
        run_engines(client, connection, environment_codes, report, args.checks, args.compiled_sql_dir)
        report["status"] = "PASS"
    except Exception:
        report["status"] = "FAIL"
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2))


def native_operations(client, connection, environments, report):
    prefix = "/projects/" + connection["projectCode"]
    group = connection["workerGroup"]
    tenant = connection["tenantCode"]
    stamp = str(int(time.time()))

    def create(engine, params, label):
        name = "DATAHUB_NATIVE_" + label + "_" + stamp
        task_code = client.request("GET", prefix + "/task-definition/gen-task-codes", {"genNum": 1})[0]
        task = {
            "code": task_code, "name": name, "version": 1, "description": "真实引擎验收",
            "taskType": engine, "taskParams": params, "taskExecuteType": "BATCH", "flag": "YES",
            "taskPriority": "MEDIUM", "workerGroup": group, "environmentCode": environments[engine],
            "failRetryTimes": 0, "failRetryInterval": 1, "timeoutFlag": "CLOSE",
            "timeoutNotifyStrategy": "FAILED", "timeout": 0, "delayTime": 0,
            "cpuQuota": -1, "memoryMax": -1, "isCache": "NO",
        }
        result = client.request("POST", prefix + "/workflow-definition", {
            "name": name, "globalParams": "[]", "locations": "[]", "timeout": 0,
            "executionType": "PARALLEL", "taskDefinitionJson": json.dumps([task]),
            "taskRelationJson": json.dumps([{
                "preTaskCode": 0, "preTaskVersion": 0, "postTaskCode": task_code,
                "postTaskVersion": 1, "conditionType": "NONE", "conditionParams": {},
            }]),
        })
        code = result["code"]
        client.request("POST", prefix + f"/workflow-definition/{code}/release", {
            "releaseState": "ONLINE", "name": name,
        })
        client.request("POST", prefix + "/executors/start-workflow-instance", {
            "workflowDefinitionCode": code, "scheduleTime": "", "failureStrategy": "END",
            "taskDependType": "TASK_POST", "execType": "START_PROCESS", "warningType": "NONE",
            "warningGroupId": 0, "runMode": "RUN_MODE_SERIAL", "workflowInstancePriority": "MEDIUM",
            "workerGroup": group, "tenantCode": tenant, "environmentCode": environments[engine],
            "startParams": "{}", "expectedParallelismNumber": 1, "dryRun": 0,
        })
        rows = wait_for(lambda: client.request("GET", prefix + "/workflow-instances", {
            "workflowDefinitionCode": code, "pageNo": 1, "pageSize": 10,
        })["totalList"], bool, 120, "DS 原生引擎实例")
        return code, rows[0]["id"]

    def task_and_log(instance_id):
        data = client.request("GET", prefix + f"/workflow-instances/{instance_id}/tasks")
        tasks = data["taskList"] if isinstance(data, dict) else data
        if not tasks:
            return None, ""
        task = tasks[0]
        if not task.get("logPath"):
            return task, ""
        output = client.request("GET", "/log/detail", {
            "taskInstanceId": task["id"], "skipLineNum": 0, "limit": 1000,
        })["message"]
        return task, output

    def bounded(engine, params, marker, case_name=None, expected_state="SUCCESS"):
        code, instance_id = create(engine, params, case_name or engine)
        result = wait_for(lambda: client.request("GET", prefix + f"/workflow-instances/{instance_id}"),
                          lambda item: item["state"] in ("SUCCESS", "FAILURE", "STOP"), 300, engine + " 执行终态")
        task, output = task_and_log(instance_id)
        expected = [marker] if isinstance(marker, str) else marker
        report["checks"][case_name or engine] = {
            "taskType": engine, "workflowCode": str(code), "workflowInstanceId": instance_id,
            "taskInstanceId": task["id"] if task else None,
            "state": result["state"], "markerInLog": all(value in output for value in expected),
        }
        if result["state"] != expected_state or not all(value in output for value in expected):
            raise RuntimeError(engine + " 原生任务未通过，查看 DS 实例 " + str(instance_id))
        return output

    return create, task_and_log, bounded


def run_engines(client, connection, environments, report, checks="engines", compiled_sql_dir=None):
    create, task_and_log, bounded = native_operations(client, connection, environments, report)
    prefix = "/projects/" + connection["projectCode"]

    common = {"resourceList": [], "localParams": []}
    marker = "DATAHUB_DATAX_SMOKE"
    datax_job = {"job": {
        "setting": {"speed": {"channel": 1}},
        "content": [{"reader": {"name": "streamreader", "parameter": {
            "column": [{"type": "long", "value": "1"}, {"type": "string", "value": marker}],
            "sliceRecordCount": 3,
        }}, "writer": {"name": "streamwriter", "parameter": {"print": True}}}],
    }}
    def spark_params(script, label):
        return {**common, "programType": "SQL", "rawScript": script, "master": "local[1]",
        "deployMode": "local", "driverCores": 1, "driverMemory": "512m", "numExecutors": 1,
        "executorCores": 1, "executorMemory": "512m", "yarnQueue": "", "others": "",
        "sqlExecutionType": "SCRIPT", "sparkVersion": "SPARK2", "appName": label}

    init = "\n".join([
        "SET 'execution.runtime-mode' = 'BATCH';", "SET 'execution.target' = 'local';",
        "SET 'table.dml-sync' = 'true';", "SET 'execution.attached' = 'true';",
        "SET 'jobmanager.memory.process.size' = '512m';",
        "SET 'taskmanager.memory.process.size' = '768m';", "SET 'taskmanager.numberOfTaskSlots' = '1';",
        "SET 'local.number-taskmanager' = '1';", "SET 'parallelism.default' = '1';",
    ]) + "\n"
    def flink_params(script, label, stream=False):
        return {**common, "programType": "SQL", "rawScript": script, "initScript":
            init.replace("'BATCH'", "'STREAMING'") if stream else init,
        "deployMode": "LOCAL", "flinkVersion": ">=1.13", "slot": 1, "taskManager": 1,
        "parallelism": 1, "jobManagerMemory": "512m", "taskManagerMemory": "768m",
        "yarnQueue": "", "others": "", "appName": label}

    if checks == "engines":
        bounded("DATAX", {**common, "customConfig": 1, "json": json.dumps(datax_job),
                          "xms": 1, "xmx": 1, "jobChannel": 1,
                          "jobSpeedByte": 0, "jobSpeedRecord": 0}, "1\t" + marker)
        marker = "DATAHUB_SPARK_SMOKE"
        bounded("SPARK", spark_params("SELECT '" + marker + "', SUM(id) FROM RANGE(10);", marker), marker + "\t45")
        marker = "DATAHUB_FLINK_SMOKE"
        sql = "\n".join([
            "CREATE TABLE source_rows (id INT) WITH ('connector'='datagen','number-of-rows'='3');",
            "CREATE TABLE sink_rows (message STRING) WITH ('connector'='print');",
            "INSERT INTO sink_rows SELECT '" + marker + "' FROM source_rows;",
        ])
        bounded("FLINK", flink_params(sql, marker), "+I[" + marker + "]")
    elif checks == "compiled":
        if not compiled_sql_dir:
            raise ValueError("编译产物验收需要 --compiled-sql-dir")
        spark_fixture = "\n".join([
            "CREATE TABLE source (id BIGINT, name STRING) USING parquet;",
            "INSERT INTO source VALUES (1, ' alice ');",
            "CREATE TABLE target (id BIGINT, name STRING, label STRING) USING parquet;",
        ])
        spark_script = spark_fixture + "\n" + (compiled_sql_dir / "spark.sql").read_bytes().decode("utf-8") + "\nSELECT * FROM target;"
        bounded("SPARK", spark_params(spark_script, "DATAHUB_COMPILED_SPARK"), "ALICE\tq'Data")
        flink_fixture = "\n".join([
            "CREATE TEMPORARY VIEW source AS SELECT CAST(1 AS BIGINT) AS id, ' alice ' AS name;",
            "CREATE TABLE target (id BIGINT, name STRING, label STRING) WITH ('connector'='print');",
        ])
        flink_script = flink_fixture + "\n" + (compiled_sql_dir / "flink.sql").read_bytes().decode("utf-8")
        bounded("FLINK", flink_params(flink_script, "DATAHUB_COMPILED_FLINK"), "+I[1, ALICE, q'Data]")
    else:
        marker = "DATAHUB_FLINK_STREAM"
        script = "\n".join([
            "CREATE TABLE source_rows (id INT) WITH ('connector'='datagen','rows-per-second'='1');",
            "CREATE TABLE sink_rows (message STRING) WITH ('connector'='print');",
            "INSERT INTO sink_rows SELECT '" + marker + "' FROM source_rows;",
        ])
        code, instance_id = create("FLINK", flink_params(script, marker, True), "FLINK_STREAM")

        def flowing():
            task, output = task_and_log(instance_id)
            if task and task["state"] in ("FAILURE", "STOP", "SUCCESS"):
                raise RuntimeError("Flink 实时任务提前结束，实例 " + str(instance_id))
            return (task, output)

        try:
            task, output = wait_for(flowing, lambda item: "+I[" + marker + "]" in item[1], 180, "Flink 持续输出")
        finally:
            current = client.request("GET", prefix + f"/workflow-instances/{instance_id}")
            if current["state"] not in ("STOP", "FAILURE", "SUCCESS"):
                client.request("POST", prefix + "/executors/execute", {
                    "workflowInstanceId": instance_id, "executeType": "STOP",
                })
            result = wait_for(lambda: client.request("GET", prefix + f"/workflow-instances/{instance_id}"),
                              lambda item: item["state"] in ("STOP", "FAILURE", "SUCCESS"), 120, "Flink 停止终态")
        wait_for(lambda: task_and_log(instance_id)[1], lambda text: "process has exited" in text
                 and "Successfully killed process tree" in text, 30, "Flink 进程树退出")
        report["checks"]["FLINK_STREAM"] = {
            "taskType": "FLINK", "workflowCode": str(code), "workflowInstanceId": instance_id,
            "taskInstanceId": task["id"], "outputWhileRunning": True,
            "stateAfterStop": result["state"], "processTreeExitConfirmed": True,
        }


if __name__ == "__main__":
    main()
