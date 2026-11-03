#!/usr/bin/env python3
"""本机并行验收共用一个target目录时，按仓库顺序执行Maven，避免测试/编译互相替换文件。"""
import fcntl
from pathlib import Path
import subprocess
import sys


if __name__ == '__main__':
    repository = Path(__file__).resolve().parents[2]
    with Path('/tmp/datahub-components-maven.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        result = subprocess.run(['mvn', *sys.argv[1:]], cwd=repository)
    raise SystemExit(result.returncode)
