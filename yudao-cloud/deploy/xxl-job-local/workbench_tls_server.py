#!/usr/bin/env python3
"""复用现有 IDEA Asset classpath，管理仅本机可达的规则工作台 TLS 实例。"""

import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys


ROOT = Path(__file__).resolve().parent
RUNTIME = ROOT / "runtime" / "sync-tls"
MAIN_CLASS = "cn.iocoder.yudao.module.asset.AssetServerApplication"
CONFIG = RUNTIME / "asset-tls.yaml"
STATE = RUNTIME / "asset-process.json"


def process_details(pid):
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return None
    result = subprocess.run(["ps", "-p", str(pid), "-o", "uid=,lstart=,command="],
                            capture_output=True, text=True)
    if result.returncode != 0 or not result.stdout.strip():
        return None
    parts = result.stdout.strip().split(maxsplit=6)
    if len(parts) != 7:
        return None
    try:
        return {"uid": int(parts[0]), "started": " ".join(parts[1:6]),
                "args": shlex.split(parts[6])}
    except ValueError:
        return None


def own_process():
    if not STATE.exists():
        return None
    try:
        saved = json.loads(STATE.read_text())
    except (ValueError, OSError):
        raise SystemExit("Asset PID 记录无法读取；不会操作任何进程。")
    details = process_details(saved.get("pid"))
    if not details or details["uid"] != os.getuid():
        return None
    args = details["args"]
    if MAIN_CLASS not in args or f"--spring.config.additional-location={CONFIG.as_uri()}" not in args:
        return None
    # 兼容此前仅记录 PID 的 Asset 实例；新记录再核对启动时间，拒绝 PID 重用。
    if saved.get("started") is not None and saved["started"] != details["started"]:
        return None
    return saved["pid"]


def source_command():
    lines = subprocess.check_output(["ps", "-axo", "uid=,pid=,command="], text=True).splitlines()
    candidates = []
    for line in lines:
        try:
            args = shlex.split(line)
            if int(args[0]) != os.getuid():
                continue
        except (ValueError, IndexError):
            continue
        if MAIN_CLASS in args and not any(CONFIG.name in arg for arg in args):
            candidates.append(args[2:])
    if len(candidates) != 1:
        raise SystemExit("需要一个当前用户的普通 AssetServerApplication 进程用于确定本机 classpath。")
    source = candidates[0]
    cp_flag = "-classpath" if "-classpath" in source else "-cp"
    if cp_flag not in source or source.index(cp_flag) + 1 >= len(source):
        raise SystemExit("Asset 主进程未提供可复用的 classpath。")
    return source[0], source[source.index(cp_flag) + 1]


def main():
    os.umask(0o077)
    action = sys.argv[1] if len(sys.argv) == 2 else "status" if len(sys.argv) == 1 else ""
    if action not in ("start", "stop", "status"):
        raise SystemExit("用法：workbench_tls_server.py start|stop|status")
    pid = own_process()
    if action == "status":
        print(f"规则工作台 Asset TLS 实例 PID={pid}" if pid else "规则工作台 Asset TLS 实例未运行")
        return
    if action == "stop":
        if pid:
            os.kill(pid, signal.SIGTERM)
            print(f"已向本脚本所属的 Asset TLS 实例发送退出请求，PID={pid}")
        else:
            print("未找到身份匹配的 Asset TLS 实例，没有停止任何进程。")
        return
    if pid:
        print(f"规则工作台 Asset TLS 实例已运行，PID={pid}")
        return
    if not CONFIG.exists():
        raise SystemExit("请先运行 prepare_workbench_tls.py。")
    java, classpath = source_command()
    args = [java, "-Xms256m", "-Xmx1024m", "-cp", classpath, MAIN_CLASS,
            f"--spring.config.additional-location={CONFIG.as_uri()}"]
    with (RUNTIME / "asset-console.log").open("a") as output:
        process = subprocess.Popen(args, cwd=ROOT.parents[1], stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    details = process_details(process.pid)
    if not details:
        raise SystemExit("Asset TLS 实例启动后已退出，请检查 asset-console.log。")
    STATE.write_text(json.dumps({"pid": process.pid, "started": details["started"]}) + "\n")
    print(f"已启动独立 Asset TLS 实例，PID={process.pid}；等待日志中的 Started 后再调用。")


if __name__ == "__main__":
    main()
