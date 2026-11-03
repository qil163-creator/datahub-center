#!/usr/bin/env python3
"""准备 DataForge 本地 SQL 运行配置；不提交任务、不重启服务、不输出凭据。"""

import argparse
import json
import os
from pathlib import Path
import re
import ssl
import stat
import subprocess
import tempfile
import urllib.parse
import urllib.request

from smoke import Client

ROOT = Path(__file__).resolve().parent
PROJECT_NAME = "datahub-development-native-local"
WORKER_GROUP = "datahub-development"
BUNDLE_NAME = "dev-local-observer"
PROFILE_CODES = ("flink-local-sql", "spark-local-sql")


def read_json(path, private=False):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > 1024 * 1024:
            raise ValueError("输入必须是有大小限制的普通 JSON 文件")
        if private and (info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600):
            raise ValueError("DS 私有配置必须属于当前用户且权限为 0600")
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError("输入 JSON 必须是对象")
    return value


def positive_code(value):
    text = str(value)
    if not re.fullmatch(r"[1-9][0-9]{0,18}", text) or int(text) > 2**63 - 1:
        raise ValueError("受管对象编码无效")
    return text


def local_endpoint(value, secure=False):
    if not isinstance(value, str):
        raise ValueError("本地服务地址无效")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in (("https",) if secure else ("http", "https")) or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("仅允许本机环回服务地址")
    if parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.port:
        raise ValueError("本地服务地址不能携带认证信息或查询参数")
    return value.rstrip("/")


def tls_material(connection):
    endpoint = local_endpoint(connection.get("serverUrl"), secure=True)
    result = {}
    for key in ("caCertificate", "clientCertificate", "clientKey"):
        path = Path(connection[key]).expanduser().resolve(strict=True)
        if not path.is_file():
            raise ValueError("TLS 材料不是普通文件")
        if key == "clientKey" and (path.stat().st_uid != os.geteuid() or stat.S_IMODE(path.stat().st_mode) != 0o600):
            raise ValueError("客户端私钥必须属于当前用户且权限为 0600")
        result[key] = str(path)
    context = ssl.create_default_context(cafile=result["caCertificate"])
    context.load_cert_chain(result["clientCertificate"], result["clientKey"])
    query = "/v1/executions?name=dh_native_990000000001&user=datahub"
    with urllib.request.urlopen(endpoint + query, context=context, timeout=5) as response:
        payload = json.load(response)
        if not isinstance(payload.get("executions"), list):
            raise ValueError("受信观察器响应无效")
    return endpoint, result


def prepare_objects(client, environment_codes, tenant):
    existing_environments = client.request("GET", "/environment/query-environment-list")
    known_codes = {positive_code(item["code"]) for item in existing_environments}
    if any(code not in known_codes for code in environment_codes.values()):
        raise ValueError("现有引擎环境编码在当前 DS 中不存在，请先重新准备引擎环境")
    tenants = client.request("GET", "/tenants/list")
    if not any(item["tenantCode"] == tenant for item in tenants):
        raise ValueError("当前 DS 缺少已有非 root 执行租户，请先准备运行环境")
    projects = client.request("GET", "/projects/list")
    project = next((item for item in projects if item["name"] == PROJECT_NAME), None)
    if project is None:
        project = client.request("POST", "/projects", {
            "projectName": PROJECT_NAME, "description": "DataForge 本地原生 SQL 的独立运行项目",
        })
    groups = client.request("GET", "/worker-groups/all")
    names = {item if isinstance(item, str) else item["name"] for item in groups}
    if WORKER_GROUP not in names:
        addresses = client.request("GET", "/worker-groups/worker-address-list")
        if not addresses or any(not isinstance(value, str) for value in addresses):
            raise ValueError("当前 DS 没有可用 Worker 地址")
        client.request("POST", "/worker-groups", {
            "name": WORKER_GROUP, "addrList": ",".join(addresses),
            "description": "DataForge 数据开发本地运行专用 Worker 组",
        })
    return positive_code(project["code"])


def configuration(private, project_code, environments, tenant, endpoint, material):
    profiles = []
    for engine, version, label in (("FLINK", "1.20.3", "Flink"), ("SPARK", "3.5.6", "Spark")):
        profiles.append({
            "code": engine.lower() + "-local-sql", "name": label + " 本地 SQL",
            "revision": "local-sql-v1", "ds-project-code": project_code, "ds-version": "3.4.2",
            "engine": engine, "engine-version": version, "deploy-mode": "LOCAL",
            "worker-group": WORKER_GROUP, "tenant-code": tenant,
            "environment-code": environments[engine], "queue": "default",
            "allowed-task-types": [engine + "_SQL"], "local-observer-endpoint": endpoint,
            "local-observer-ssl-bundle": BUNDLE_NAME, "spark-master": "local[1]",
            "artifacts": [], "dependency-resources": [],
        })
    return {
        "dolphinscheduler": {"server-url": local_endpoint(private["server"]), "token": private["token"]},
        "dataforge": {"dev": {"ds-project-code": project_code, "worker-group": WORKER_GROUP,
                              "tenant-code": tenant, "native": {"profiles": profiles}}},
        "spring": {"ssl": {"bundle": {"pem": {BUNDLE_NAME: {
            "keystore": {"certificate": material["clientCertificate"], "private-key": material["clientKey"]},
            "truststore": {"certificate": material["caCertificate"]},
        }}}}},
    }


def write_private_configuration(path, value):
    if path.is_symlink():
        raise ValueError("拒绝覆盖符号链接配置文件")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = path.parent.resolve() / path.name
    repository = ROOT.parent.parent
    if path.is_relative_to(repository):
        ignored = subprocess.run(["git", "-C", str(repository), "check-ignore", "--quiet", "--", str(path)],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if ignored.returncode != 0:
            raise ValueError("仓库内私有输出必须位于 Git 忽略目录")
    descriptor, temporary = tempfile.mkstemp(prefix=".application-native-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as destination:
            # JSON is a valid YAML document and avoids an extra serializer dependency.
            json.dump(value, destination, ensure_ascii=False, indent=2)
            destination.write("\n")
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ds-private", type=Path, required=True)
    parser.add_argument("--tls-connection", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "runtime/dev-native/application-native.yaml")
    args = parser.parse_args()
    try:
        private = read_json(args.ds_private, private=True)
        token = private["token"]
        if not isinstance(token, str) or not token.strip() or len(token) > 4096:
            raise ValueError("DS token 无效")
        server = local_endpoint(private["server"])
        tenant = private.get("tenantCode", "datahub")
        if not isinstance(tenant, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]{0,99}", tenant) or tenant.lower() == "root":
            raise ValueError("执行租户必须为已有非 root 用户")
        environments = {engine: positive_code(private["environmentCodes"][engine]) for engine in ("FLINK", "SPARK")}
        endpoint, material = tls_material(read_json(args.tls_connection))
        client = Client(server)
        client.token = token
        project = prepare_objects(client, environments, tenant)
        output = write_private_configuration(args.output, configuration(private, project, environments, tenant, endpoint, material))
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, ssl.SSLError):
        # Upstream errors may contain credentials; never interpolate them here.
        parser.exit(1, "准备失败：请检查本地 DS 私有配置、环境编码、TLS 连接与输出目录权限。\n")
    print(json.dumps({"configurationPath": str(output), "profileCodes": list(PROFILE_CODES)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
