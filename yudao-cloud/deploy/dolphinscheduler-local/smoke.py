#!/usr/bin/env python3
"""验证本地 DolphinScheduler 的发布、执行、日志、调度和停止，不保存凭据。"""

import argparse
import datetime as dt
import getpass
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


class Client:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.session = None
        self.token = None

    def request(self, method, path, params=None):
        encoded = urllib.parse.urlencode(params or {}).encode()
        url = self.base_url + path
        data = None if method == "GET" else encoded
        if method == "GET" and encoded:
            url += "?" + encoded.decode()
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if self.session:
            headers["sessionId"] = self.session
            headers["Cookie"] = "sessionId=" + self.session
        if self.token:
            headers["token"] = self.token
        try:
            response = urllib.request.urlopen(
                urllib.request.Request(url, data=data, headers=headers, method=method),
                timeout=20,
            )
            result = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"DS {method} {path}: HTTP {error.code}") from None
        if result.get("code") != 0:
            raise RuntimeError(f"DS {method} {path}: {result.get('msg', '调用失败')}")
        return result.get("data")


def wait_for(probe, predicate, seconds, label):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = probe()
        if predicate(value):
            return value
        time.sleep(2)
    raise RuntimeError(f"等待{label}超时")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:12345/dolphinscheduler")
    parser.add_argument("--project", default="datahub-integration-local")
    parser.add_argument("--tenant", default="datahub")
    parser.add_argument("--worker-group", default="datahub-integration")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    client = Client(args.url)
    if os.environ.get("DS_TOKEN"):
        client.token = os.environ["DS_TOKEN"]
    else:
        password = os.environ.get("DS_PASSWORD") or getpass.getpass("本地 DS 密码: ")
        login = client.request("POST", "/login", {
            "userName": os.environ.get("DS_USERNAME", "admin"), "userPassword": password,
        })
        client.session = login["sessionId"]
    projects = client.request("GET", "/projects/list")
    project = next((item for item in projects if item["name"] == args.project), None)
    if not project:
        project = client.request("POST", "/projects", {
            "projectName": args.project, "description": "DataHub 本地集成任务验收",
        })
    project_code = project["code"]
    tenants = client.request("GET", "/tenants/list")
    if not any(item["tenantCode"] == args.tenant for item in tenants):
        queues = client.request("GET", "/queues/list")
        if not queues:
            client.request("POST", "/queues", {
                "queue": "datahub-local", "queueName": "datahub-local",
            })
            queues = client.request("GET", "/queues/list")
        client.request("POST", "/tenants", {
            "tenantCode": args.tenant, "queueId": queues[0]["id"],
            "description": "本地非 root 执行用户",
        })
    addresses = client.request("GET", "/worker-groups/worker-address-list")
    groups = client.request("GET", "/worker-groups/all")
    group_names = [item if isinstance(item, str) else item["name"] for item in groups]
    if args.worker_group not in group_names:
        client.request("POST", "/worker-groups", {
            "name": args.worker_group, "addrList": ",".join(addresses),
            "description": "本地集成任务专用 Worker 组",
        })
    report = {
        "server": args.url, "projectCode": str(project_code),
        "tenantCode": args.tenant, "workerGroup": args.worker_group, "checks": {},
    }
    try:
        if not args.prepare_only:
            run_smoke(client, args, project_code, report)
        report["status"] = "PREPARED" if args.prepare_only else "PASS"
        if report["checks"].get("stop", {}).get("state") == "FAILURE":
            report["status"] = "PASS_WITH_DS_STOP_STATE_MISMATCH"
    except Exception:
        report["status"] = "FAIL"
        raise
    finally:
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2))


def run_smoke(client, args, project_code, report):
    prefix = f"/projects/{project_code}"
    marker = "DATAHUB_DS_SMOKE_" + str(int(time.time()))

    def create_workflow(suffix, script):
        name = marker + suffix
        code = client.request("GET", prefix + "/task-definition/gen-task-codes", {"genNum": 1})[0]
        task = {
            "code": code, "name": name, "version": 1, "description": "本地真实执行验收",
            "flag": "YES", "taskType": "SHELL", "taskParams": {
                "rawScript": script, "localParams": [], "resourceList": [],
            }, "taskPriority": "MEDIUM", "workerGroup": args.worker_group,
            "environmentCode": -1, "failRetryTimes": 0, "failRetryInterval": 1,
            "timeoutFlag": "CLOSE", "timeoutNotifyStrategy": "FAILED", "timeout": 0,
            "delayTime": 0, "cpuQuota": -1, "memoryMax": -1,
            "taskExecuteType": "BATCH", "isCache": "NO",
        }
        workflow = client.request("POST", prefix + "/workflow-definition", {
            "name": name, "description": "仅本地烟测，保留实例供核查", "globalParams": "[]",
            "locations": json.dumps([{ "taskCode": code, "x": 100, "y": 100 }]),
            "timeout": 0, "executionType": "PARALLEL", "taskDefinitionJson": json.dumps([task]),
            "taskRelationJson": json.dumps([{
                "name": "", "preTaskCode": 0, "preTaskVersion": 0,
                "postTaskCode": code, "postTaskVersion": 1,
                "conditionType": "NONE", "conditionParams": {},
            }]),
        })
        workflow_code = workflow["code"]
        client.request("POST", prefix + f"/workflow-definition/{workflow_code}/release", {
            "releaseState": "ONLINE", "name": name,
        })
        return workflow_code

    def trigger(code):
        client.request("POST", prefix + "/executors/start-workflow-instance", {
            "workflowDefinitionCode": code, "failureStrategy": "END",
            "scheduleTime": "",
            "taskDependType": "TASK_POST", "execType": "START_PROCESS",
            "warningType": "NONE", "warningGroupId": 0,
            "runMode": "RUN_MODE_SERIAL", "workflowInstancePriority": "MEDIUM",
            "workerGroup": args.worker_group, "tenantCode": args.tenant,
            "environmentCode": -1, "startParams": "{}", "expectedParallelismNumber": 1,
            "dryRun": 0,
        })
        rows = wait_for(lambda: client.request("GET", prefix + "/workflow-instances", {
            "workflowDefinitionCode": code, "pageNo": 1, "pageSize": 10,
        })["totalList"], bool, 120, "DS 创建工作流实例")
        return rows[0]["id"]

    code = create_workflow("_success", "printf '%s\\n' '" + marker + "'\nid -un\n")
    instance_id = trigger(code)
    instance = wait_for(lambda: client.request("GET", prefix + f"/workflow-instances/{instance_id}"),
                        lambda item: item["state"] in ("SUCCESS", "FAILURE", "STOP"), 120, "DS 执行结束")
    if instance["state"] != "SUCCESS":
        raise RuntimeError("DS 工作流未成功，实例编号 " + str(instance_id))
    tasks = client.request("GET", prefix + f"/workflow-instances/{instance_id}/tasks")
    if isinstance(tasks, dict):
        tasks = tasks.get("taskList", tasks.get("taskInstances", []))
    task_id = tasks[0]["id"]
    log = client.request("GET", "/log/detail", {"taskInstanceId": task_id, "skipLineNum": 0, "limit": 1000})
    message = log["message"]
    if marker not in message or args.tenant not in message:
        raise RuntimeError("DS 日志中缺少实际执行输出")
    report["checks"]["execution"] = {
        "workflowCode": str(code), "workflowInstanceId": instance_id,
        "taskInstanceId": task_id, "state": instance["state"], "markerInLog": True,
    }
    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(tzinfo=None)
    schedule = client.request("POST", prefix + "/schedules", {
        "workflowDefinitionCode": code, "schedule": json.dumps({
            "startTime": now.strftime("%Y-%m-%d %H:%M:%S"),
            "endTime": (now + dt.timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S"),
            "crontab": "0 0 0 * * ?", "timezoneId": "Asia/Shanghai",
        }), "warningType": "NONE", "warningGroupId": 0, "failureStrategy": "END",
        "workerGroup": args.worker_group, "tenantCode": args.tenant,
        "environmentCode": -1, "workflowInstancePriority": "MEDIUM",
    })
    schedule_id = schedule["id"]
    client.request("POST", prefix + f"/schedules/{schedule_id}/online")
    client.request("POST", prefix + f"/schedules/{schedule_id}/offline")
    report["checks"]["schedule"] = {"id": schedule_id, "onlineAndOffline": True}
    stop_code = create_workflow("_stop", "printf '%s\\n' '" + marker + "_STOP'\nsleep 180\n")
    stop_id = trigger(stop_code)
    wait_for(lambda: client.request("GET", prefix + f"/workflow-instances/{stop_id}"),
             lambda item: item["state"] == "RUNNING_EXECUTION", 120, "DS 长任务运行")

    long_task_id = None

    def long_task_output():
        nonlocal long_task_id
        running_tasks = client.request("GET", prefix + f"/workflow-instances/{stop_id}/tasks")
        if isinstance(running_tasks, dict):
            running_tasks = running_tasks.get("taskList", running_tasks.get("taskInstances", []))
        if not running_tasks or running_tasks[0]["state"] != "RUNNING_EXECUTION":
            return False
        long_task_id = running_tasks[0]["id"]
        output = client.request("GET", "/log/detail", {
            "taskInstanceId": running_tasks[0]["id"], "skipLineNum": 0, "limit": 1000,
        })
        return marker + "_STOP" in output["message"]

    wait_for(long_task_output, bool, 120, "DS Worker 长任务实际输出")
    client.request("POST", prefix + "/executors/execute", {
        "workflowInstanceId": stop_id, "executeType": "STOP",
    })
    stopped = wait_for(lambda: client.request("GET", prefix + f"/workflow-instances/{stop_id}"),
                       lambda item: item["state"] in ("STOP", "FAILURE"), 120, "DS 停止长任务")
    stop_log = wait_for(lambda: client.request("GET", "/log/detail", {
        "taskInstanceId": long_task_id, "skipLineNum": 0, "limit": 1000,
    })["message"], lambda output: "process has exited" in output
        and "Successfully killed process tree" in output, 20, "DS 确认进程树退出")
    report["checks"]["stop"] = {
        "workflowInstanceId": stop_id, "state": stopped["state"], "workerOutputBeforeStop": True,
        "processTreeExitConfirmed": True, "reportedAsStop": stopped["state"] == "STOP",
    }


if __name__ == "__main__":
    main()
