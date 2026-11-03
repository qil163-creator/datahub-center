#!/usr/bin/env python3
import os
from pathlib import Path
import secrets
import subprocess

root = Path(__file__).resolve().parent
credential_file = root / ".env"
if not credential_file.exists():
    descriptor = os.open(credential_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(f"FTP_USER=datahub_test\nFTP_PASSWORD={secrets.token_hex(16)}\n")
(root / "data").mkdir(mode=0o700, exist_ok=True)
example = root / "data" / "测试说明.txt"
if not example.exists():
    example.write_text("这是 DataHub 本机 FTP 文件管理测试文件。\n", encoding="utf-8")
subprocess.run(["docker", "compose", "up", "-d", "--build", "--wait"], cwd=root, check=True)
print("FTP 测试服务已启动；用户名和密码保存在本目录 .env。")
