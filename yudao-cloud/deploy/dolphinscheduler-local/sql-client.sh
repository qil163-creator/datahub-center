#!/usr/bin/env bash
set -uo pipefail

# SQL Client 1.20 可能把语句错误打印到控制台后返回 0，统一传播给调度器。
client_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
error_flag="$(mktemp)"
trap 'rm -f -- "$error_flag"' EXIT

# 只保存固定错误标记，持续流的业务输出不写入额外临时日志。
"$client_dir/sql-client-original.sh" "$@" 2>&1 | python3 -u -c '
import sys
for line in sys.stdin.buffer:
    sys.stdout.buffer.write(line)
    sys.stdout.buffer.flush()
    if b"[ERROR] Could not execute SQL statement." in line:
        with open(sys.argv[1], "wb") as marker:
            marker.write(b"SQL_ERROR\n")
' "$error_flag"
pipeline_status=("${PIPESTATUS[@]}")
if [ "${pipeline_status[0]}" -ne 0 ]; then
  exit "${pipeline_status[0]}"
fi
if [ "${pipeline_status[1]}" -ne 0 ]; then
  exit "${pipeline_status[1]}"
fi
if [ -s "$error_flag" ]; then
  printf '\n%s\n' 'Flink SQL 执行失败，拒绝向调度器报告成功。' >&2
  exit 1
fi
