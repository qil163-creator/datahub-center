#!/usr/bin/env python3
"""准备正常本地 DS 的作业运行目标，合并 IDE 已导入的私密配置。"""

import argparse
import json
from pathlib import Path

from prepare_dev_native_runtime import read_json, write_private_configuration
from smoke import Client

PROJECT = "datahub-local-runtime"
GROUP = "datahub-local"


def prepare(path):
    config = read_json(path, private=True)
    client = Client(config["dolphinscheduler"]["server-url"])
    if client.base_url != "http://127.0.0.1:12345/dolphinscheduler":
        raise ValueError("只配置正常本地 DolphinScheduler")
    client.token = config["dolphinscheduler"]["token"]
    projects = client.request("GET", "/projects/list")
    project = next((row for row in projects if row["name"] == PROJECT), None)
    if project is None:
        project = client.request("POST", "/projects", {"projectName": PROJECT, "description": "IDE 启动项目使用的正常本地运行目标"})
    if not any(row["tenantCode"] == "datahub" for row in client.request("GET", "/tenants/list")):
        queues = client.request("GET", "/queues/list")
        if not queues:
            client.request("POST", "/queues", {"queue": "default", "queueName": "default"})
            queues = client.request("GET", "/queues/list")
        client.request("POST", "/tenants", {"tenantCode": "datahub", "queueId": queues[0]["id"], "description": "本地非 root 执行用户"})
    if GROUP not in client.request("GET", "/worker-groups/all"):
        addresses = client.request("GET", "/worker-groups/worker-address-list")
        if not addresses:
            raise ValueError("正常 DS 没有在线 Worker")
        client.request("POST", "/worker-groups", {"name": GROUP, "addrList": ",".join(addresses), "description": "正常本地作业运行组"})
    environments = client.request("GET", "/environment/query-environment-list")
    env_codes = {}
    for engine, java in (("DATAX", "/opt/java/openjdk"), ("SPARK", "/opt/java17"), ("FLINK", "/opt/java17")):
        name = "datahub-local-" + engine.lower()
        env = next((row for row in environments if row["name"] == name), None)
        if env is None:
            client.request("POST", "/environment/create", {
                "name": name, "description": "正常本地引擎运行环境", "workerGroups": json.dumps([GROUP, "default"]),
                "config": "\n".join([f"export JAVA_HOME={java}", "export PATH=$JAVA_HOME/bin:$PATH", "export DATAX_HOME=/opt/datax",
                                      "export DATAX_LAUNCHER=/opt/datax/bin/datax.py", "export PYTHON_LAUNCHER=/usr/bin/python3",
                                      "export SPARK_HOME=/opt/spark", "export SPARK_HOME1=/opt/spark", "export SPARK_HOME2=/opt/spark",
                                      "export FLINK_HOME=/opt/flink", "export SPARK_LOCAL_IP=127.0.0.1"]),
            })
            environments = client.request("GET", "/environment/query-environment-list")
            env = next(row for row in environments if row["name"] == name)
        env_codes[engine] = str(env["code"])
    forge = config.setdefault("dataforge", {})
    integration = forge.setdefault("integration", {})
    integration.update({"ds-project-code": str(project["code"]), "tenant-code": "datahub", "environment-code": env_codes["DATAX"],
                        "engine-environment-codes": env_codes, "worker-groups": ["default", GROUP],
                        "datax-upsert-worker-groups": ["default", GROUP], "spark-master": "local[1]", "flink-deploy-mode": "LOCAL"})
    # 集成页面的新任务默认 default；开发子流程必须使用相同父执行目标，避免默认混合作业无法启动。
    forge.setdefault("dev", {}).update({"ds-project-code": str(project["code"]), "tenant-code": "datahub", "worker-group": "default",
                                        "environment-code": env_codes["DATAX"]})
    write_private_configuration(path, config)
    print(json.dumps({"projectCode": str(project["code"]), "workerGroup": "default", "additionalWorkerGroup": GROUP, "environmentCodes": env_codes,
                      "privateConfig": str(path.resolve()), "additionalServices": 0}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-config", required=True, type=Path)
    prepare(parser.parse_args().private_config)
