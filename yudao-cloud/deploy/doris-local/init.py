#!/usr/bin/env python3
"""准备本地凭据，启动 Doris，并幂等初始化中台分层库。"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys


DIRECTORY = Path(__file__).resolve().parent
ENV_FILE = DIRECTORY / ".env"
DATABASES = ("ods", "dwd", "dws", "dim")
PASSWORD_KEYS = (
    "DORIS_ROOT_PASSWORD",
    "DORIS_ADMIN_PASSWORD",
    "DORIS_DATAHUB_PASSWORD",
)
COMPOSE = ["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(DIRECTORY / "compose.yaml")]


def password_hash(password: str) -> str:
    return "*" + hashlib.sha1(hashlib.sha1(password.encode()).digest()).hexdigest().upper()


def prepare_environment() -> dict[str, str]:
    if ENV_FILE.is_symlink():
        raise ValueError(".env 不得为符号链接")
    if not ENV_FILE.exists():
        values = {key: "Dh_" + secrets.token_hex(16) + "!" for key in PASSWORD_KEYS}
        values["DORIS_ROOT_PASSWORD_HASH"] = password_hash(values["DORIS_ROOT_PASSWORD"])
        # 排他创建并直接限制权限，避免凭据在写入期间可被其他用户读取。
        descriptor = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write("# 本机 Doris 凭据，请勿提交或删除；已有数据卷依赖这些密码。\n")
            for key, value in values.items():
                handle.write(f"{key}={value}\n")
    os.chmod(ENV_FILE, 0o600)
    values = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in values:
            raise ValueError(".env 格式错误或包含重复变量")
        values[key] = value
    for key in PASSWORD_KEYS:
        if not re.fullmatch(r"[A-Za-z0-9_!@.-]{20,128}", values.get(key, "")):
            raise ValueError(f"{key} 缺失或格式无效；请保留脚本生成的凭据格式")
    if len({values[key] for key in PASSWORD_KEYS}) != len(PASSWORD_KEYS):
        raise ValueError("三个账号必须使用不同的密码")
    if values.get("DORIS_ROOT_PASSWORD_HASH") != password_hash(values["DORIS_ROOT_PASSWORD"]):
        raise ValueError("root 密码与哈希不匹配；不要单独修改 .env 中的 root 密码")
    return values


def execute_sql(statements: list[str], values: dict[str, str]) -> None:
    environment = os.environ.copy()
    environment.update(values)
    # SQL 经标准输入传入；错误可能包含哈希，因此输出前同时脱敏明文与哈希。
    result = subprocess.run(
        COMPOSE + ["exec", "-T", "fe", "mysql", "-uroot", "-h127.0.0.1", "-P9030", "--batch"],
        input="\n".join(statements) + "\n",
        text=True,
        capture_output=True,
        env=environment,
        timeout=90,
    )
    if result.returncode:
        message = result.stderr
        for key in PASSWORD_KEYS:
            message = message.replace(values[key], "[REDACTED]").replace(password_hash(values[key]), "[REDACTED]")
        raise RuntimeError(f"Doris 初始化失败：{message.strip()}")


def initialize(values: dict[str, str]) -> None:
    environment = os.environ.copy()
    environment.update(values)
    subprocess.run(COMPOSE + ["config", "--quiet"], env=environment, check=True)
    subprocess.run(COMPOSE + ["up", "-d", "--wait", "--wait-timeout", "300", "fe"], env=environment, check=True)
    # 先关闭默认 admin 空密码，避免 BE 下载或启动失败时留下可用的空密码管理账号。
    admin_hash = password_hash(values["DORIS_ADMIN_PASSWORD"])
    execute_sql([f"SET PASSWORD FOR 'admin'@'%' = '{admin_hash}';"], values)
    subprocess.run(COMPOSE + ["up", "-d", "--wait", "--wait-timeout", "300"], env=environment, check=True)

    datahub_hash = password_hash(values["DORIS_DATAHUB_PASSWORD"])
    statements = [
        f"CREATE USER IF NOT EXISTS 'datahub'@'%' IDENTIFIED BY PASSWORD '{datahub_hash}';",
        f"SET PASSWORD FOR 'datahub'@'%' = '{datahub_hash}';",
    ]
    for database in DATABASES:
        statements.extend([
            f"CREATE DATABASE IF NOT EXISTS `{database}`;",
            "GRANT SELECT_PRIV, LOAD_PRIV, ALTER_PRIV, CREATE_PRIV, DROP_PRIV, SHOW_VIEW_PRIV "
            f"ON `{database}`.* TO 'datahub'@'%';",
        ])
    execute_sql(statements, values)
    print("Doris 已启动；ods、dwd、dws、dim 已初始化，datahub 账号权限已配置。")
    print(f"连接地址：127.0.0.1:9030；密码保存在 {ENV_FILE}（未输出）。")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true", help="仅创建或验证 .env，不启动容器")
    arguments = parser.parse_args()
    try:
        values = prepare_environment()
        if arguments.prepare_only:
            print(f"凭据文件已就绪：{ENV_FILE}，权限 0600；未启动容器。")
        else:
            initialize(values)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
