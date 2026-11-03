#!/usr/bin/env python3
"""通过真实平台接口验证数据开发本地引擎，只操作明确指定的测试任务。"""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import ssl
import subprocess
import time
import urllib.parse
import urllib.request
import uuid


class Verification:
    def __init__(self, config):
        self.config = config
        self.headers = {
            "Authorization": Path(config["authHeaderFile"]).read_text().strip(),
            "Content-Type": "application/json",
        }
        self.tls = ssl.create_default_context(cafile=config["caCertificate"])
        self.tls.load_cert_chain(config["clientCertificate"], config["clientKey"])

    def call(self, path, method="GET", body=None, **query):
        url = self.config["apiBase"].rstrip("/") + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        request = urllib.request.Request(url, data=data, headers=self.headers, method=method)
        with urllib.request.urlopen(request, timeout=40) as response:
            value = json.load(response)
        if value.get("code") != 0:
            raise RuntimeError(f"平台接口失败：{path}，错误码 {value.get('code')}")
        return value.get("data")

    def observation(self, record_id):
        query = urllib.parse.urlencode({"name": f"dh_native_{record_id}", "user": "datahub"})
        with urllib.request.urlopen(self.config["observerUrl"] + "/v1/executions?" + query,
                                    context=self.tls, timeout=10) as response:
            return json.load(response)["executions"]

    def worker_marker(self, record_id, marker):
        if not str(record_id).isdigit():
            raise RuntimeError("运行编号无效")
        source = "from pathlib import Path; p=Path('/var/lib/datahub-native-runtime/dh_native_" \
                 + str(record_id) + "/output.log'); print(int(p.exists() and " + repr(marker) \
                 + " in p.read_text(errors='replace')))"
        value = subprocess.check_output(
            ["docker", "exec", "-u", "0", self.config.get("container", "datahub-ds-local-standalone-1"),
             "python3", "-c", source], text=True, timeout=10
        )
        return value.strip() == "1"

    def prepare(self, task_id, script=None):
        task = self.call("/dev-task/get", id=task_id)
        if str(task_id) not in [str(value) for value in self.config["allowedTaskIds"]] \
                or not task["name"].startswith("测试_"):
            raise RuntimeError("拒绝操作不在验收名单中的任务")
        if (task.get("latestRecord") or {}).get("status") in {
            "WAITING", "SUBMITTING", "SUBMITTED", "RUNNING", "CANCEL_REQUESTED", "UNKNOWN"
        }:
            raise RuntimeError("当前测试任务仍有活动实例，请先核查")
        if task["status"] == 1:
            self.call("/dev-task/update-status", "PUT", id=task_id, status=0)
            task = self.call("/dev-task/get", id=task_id)
        engine = "SPARK" if task["taskType"] == "SPARK_SQL" else "FLINK"
        mode = task["programConfig"]["workloadMode"]
        payload = {key: task.get(key) for key in (
            "id", "dirId", "name", "ownerId", "description", "scheduler", "maxRows",
            "dependencyTaskIds", "qualityTaskId", "taskType"
        )}
        payload.update(expectedRevision=task["revision"], executionUserId=1,
                       content=script or task["content"], timeoutSeconds=0 if mode == "STREAMING" else 300,
                       cronExpression=None, databaseId=None, dsDatasourceId=None, dsDatasourceType=None,
                       executionConfig={**(task.get("executionConfig") or {}),
                                        "priority": "MEDIUM", "workerGroup": self.config["workerGroup"],
                                        "retryTimes": 0, "retryIntervalMinutes": 1, "delayMinutes": 0,
                                        "sqlType": "AUTO", "statementSeparator": ";", "localParams": []},
                       programConfig={"schemaVersion": 1, "workloadMode": mode,
                                      "runtimeProfileCode": engine.lower() + "-local-sql",
                                      "engineVersion": "3.5.6" if engine == "SPARK" else "1.20.3",
                                      "queue": "default"})
        payload["programConfig"].update(
            {"parallelism": 1, "taskManagerSlots": 1, "taskManagerCount": 1,
             "jobManagerMemoryMb": 512, "taskManagerMemoryMb": 768, "checkpointIntervalMs": 10_000}
            if engine == "FLINK" else
            {"driverCores": 1, "driverMemoryMb": 512, "numExecutors": 1,
             "executorCores": 1, "executorMemoryMb": 512}
        )
        self.call("/dev-task/update", "PUT", payload)
        self.call("/dev-task/update-status", "PUT", id=task_id, status=1)
        return self.call("/dev-task/get", id=task_id)

    def run(self, task_id, expected, stop=False):
        request = {"id": task_id, "businessDate": datetime.date.today().isoformat(),
                   "requestId": "dev_native_" + uuid.uuid4().hex}
        record_id = self.call("/dev-task/run", "POST", request)
        if self.call("/dev-task/run", "POST", request) != record_id:
            raise RuntimeError("同一请求未返回同一实例")
        result = {"taskId": task_id, "recordId": record_id, "idempotencyVerified": True,
                  "states": [], "stopRequested": False}
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            record = self.call("/dev-record/get", id=record_id)
            state = record["status"]
            if not result["states"] or result["states"][-1] != state:
                result["states"].append(state)
                print(json.dumps({"recordId": record_id, "status": state}), flush=True)
            observed = self.observation(record_id)
            execution_marker = "+I[DATAHUB_DEV_FLINK_STREAM" if task_id == 9 else "Spark master: local[1]"
            if stop and not result["stopRequested"] and observed \
                    and all(item["started"] and item["processTreeAlive"] for item in observed) \
                    and self.worker_marker(record_id, execution_marker):
                result["actualOutputBeforeStop"] = True
                self.call("/dev-record/stop", "PUT", id=record_id)
                result["stopRequested"] = True
            if state in {"SUCCESS", "FAILED", "STOPPED", "TIMEOUT"}:
                result.update(status=state, observation=observed,
                              dsProjectCode=record.get("dsProjectCode"), dsInstanceId=record.get("dsInstanceId"))
                break
            time.sleep(2)
        else:
            self.call("/dev-record/stop", "PUT", id=record_id)
            raise RuntimeError(f"实例 {record_id} 未在有界时间内收敛")
        assert result["status"] == expected, result
        assert result["observation"] and all(
            item["started"] and item["finished"] and not item["processTreeAlive"]
            for item in result["observation"]
        ), result
        cursor = 0
        count = 0
        digest = hashlib.sha256()
        for _ in range(30):
            page = self.call("/dev-record/task-log", id=record_id, skipLineNum=cursor, limit=100)
            assert page["available"], page.get("statusMessage")
            next_cursor = page["nextSkipLineNum"]
            assert next_cursor >= cursor
            count += page["readLines"]
            digest.update(page["message"].encode())
            if next_cursor == cursor or not page["mayHaveMore"]:
                break
            cursor = next_cursor
        result.update(logReadLines=count, logCursor=cursor, maskedLogSha256=digest.hexdigest())
        assert count > 0
        request = urllib.request.Request(
            self.config["apiBase"] + "/dev-record/task-log-download?" + urllib.parse.urlencode({"id": record_id}),
            headers=self.headers,
        )
        with urllib.request.urlopen(request, timeout=40) as response:
            download = response.read(2_097_153)
            assert response.headers.get_content_type() == "text/plain"
        assert 0 < len(download) <= 2_097_152
        result.update(downloadedLogBytes=len(download), downloadedLogSha256=hashlib.sha256(download).hexdigest())
        latest = self.call("/dev-task/get", id=task_id)["latestRecord"]
        assert latest["id"] == record_id and latest["status"] == expected
        result["latestRecordVerified"] = True
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--case", choices=["flink-batch", "spark-batch", "flink-stop", "spark-stop", "flink-failure"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.config.stat().st_mode & 0o077:
        raise RuntimeError("本地配置必须限制为 0600")
    verification = Verification(json.loads(args.config.read_text()))
    stream = """CREATE TEMPORARY TABLE input_events (id BIGINT) WITH ('connector'='datagen', 'rows-per-second'='1');
CREATE TEMPORARY TABLE output_events (label STRING, id BIGINT) WITH ('connector'='print');
INSERT INTO output_events SELECT 'DATAHUB_DEV_FLINK_STREAM', id FROM input_events;"""
    cases = {
        "flink-batch": (7, "SELECT 'DATAHUB_DEV_FLINK_BATCH_OK', SUM(v) FROM (VALUES (1), (2), (3)) AS t(v);", "SUCCESS", False),
        "spark-batch": (8, "SELECT 'DATAHUB_DEV_SPARK_BATCH_OK', SUM(id) FROM range(10);", "SUCCESS", False),
        "flink-stop": (9, stream, "STOPPED", True),
        "spark-stop": (8, "SELECT sum(sin(id) * cos(id)) FROM range(1000000000);", "STOPPED", True),
        "flink-failure": (10, "SELECT DATAHUB_INTENTIONAL_MISSING_FUNCTION(1);", "FAILED", False),
    }
    task_id, script, expected, stop = cases[args.case]
    verification.prepare(task_id, script)
    result = verification.run(task_id, expected, stop)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({"case": args.case, "taskId": task_id, "recordId": result["recordId"],
                      "status": result["status"], "logReadLines": result["logReadLines"]}), flush=True)


if __name__ == "__main__":
    main()
