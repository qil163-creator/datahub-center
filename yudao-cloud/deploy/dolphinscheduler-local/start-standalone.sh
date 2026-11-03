#!/usr/bin/env bash
set -euo pipefail
# DATAHUB_LOCAL_ENTRYPOINT_V1：兼容既有容器 CMD，先清理停机遗留协调状态再进入官方入口。
exec /bin/bash /opt/dolphinscheduler/start-local.sh "$@"
