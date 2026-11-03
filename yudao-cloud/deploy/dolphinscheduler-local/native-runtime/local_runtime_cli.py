#!/usr/bin/env python3
"""仅将带平台执行标识的 SQL 命令交给 root 监督器，其余命令保留原行为。"""

import base64
import json
import os
from pathlib import Path
import re
import signal
import socket
import sys

ORIGINALS = {"FLINK": "/opt/flink/bin/sql-client-managed-original.sh",
             "SPARK": "/opt/spark/bin/spark-sql-managed-original"}
NAME = re.compile(r"dh_native_[1-9][0-9]{0,18}\Z")


def execution_name(engine, arguments):
    names = []
    if engine == "SPARK":
        for index, value in enumerate(arguments):
            if value == "--name" and index + 1 < len(arguments):
                names.append(arguments[index + 1])
            if value == "--conf" and index + 1 < len(arguments) and arguments[index + 1].startswith("spark.app.name="):
                names.append(arguments[index + 1].split("=", 1)[1])
    else:
        sources = [arguments[index + 1] for index, value in enumerate(arguments[:-1]) if value in ("-f", "--file", "-i", "--init")]
        if 1 <= len(sources) <= 2:
            for source_value in sources:
                source = Path(source_value)
                if source.stat().st_size <= 1024 * 1024:
                    script = source.read_text(encoding="utf-8")
                    names.extend(re.findall(r"(?im)\bSET\s+['\"]pipeline\.name['\"]\s*=\s*['\"]([^'\"]+)['\"]\s*;", script))
        names.extend(value.split("=", 1)[1] for value in arguments if value.startswith("-Dpipeline.name="))
    managed = [name for name in names if name.startswith("dh_native_")]
    if not managed:
        return None
    if len(set(names)) != 1 or not NAME.fullmatch(managed[0]):
        raise ValueError("平台执行标识冲突")
    return managed[0]


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ORIGINALS:
        raise SystemExit(2)
    engine, arguments = sys.argv[1], sys.argv[2:]
    try:
        name = execution_name(engine, arguments)
        if name is None:
            os.execv(ORIGINALS[engine], [ORIGINALS[engine], *arguments])
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.connect("/run/datahub-native-runtime/launch.sock")
        connection.sendall(json.dumps({"name": name, "engine": engine, "arguments": arguments,
                                       "cwd": os.getcwd()}).encode() + b"\n")
        cancelled = [False]

        def cancel(*_):
            if cancelled[0]:
                return
            cancelled[0] = True
            try:
                connection.sendall(b'{"operation":"cancel"}\n')
            except OSError:
                pass

        signal.signal(signal.SIGINT, cancel)
        signal.signal(signal.SIGTERM, cancel)
        with connection, connection.makefile("rb") as incoming:
            for line in incoming:
                message = json.loads(line)
                if message["type"] == "output":
                    sys.stdout.buffer.write(base64.b64decode(message["data"], validate=True))
                    sys.stdout.buffer.flush()
                elif message["type"] == "result":
                    code = message["exitCode"]
                    return code if code >= 0 else 128 - code
                elif message["type"] == "error":
                    raise RuntimeError("监督器拒绝执行")
        raise RuntimeError("监督器连接中断")
    except (OSError, ValueError, KeyError, RuntimeError):
        print("受管本地 SQL 执行未取得可信结果。", file=sys.stderr)
        return 127


if __name__ == "__main__":
    raise SystemExit(main())
