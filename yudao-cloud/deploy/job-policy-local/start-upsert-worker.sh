#!/bin/bash
set -euo pipefail
# 独立 Worker 与 master 分离，使故障验收能够只中断执行节点。
source /opt/dolphinscheduler/standalone-server/conf/dolphinscheduler_env.sh
exec "$JAVA_HOME/bin/java" -Xms128m -Xmx512m -Duser.timezone=Asia/Shanghai \
  -cp '/opt/dolphinscheduler/standalone-server/conf:/opt/dolphinscheduler/worker-server/libs/*:/opt/dolphinscheduler/plugins/task-plugins/*:/opt/dolphinscheduler/plugins/datasource-plugins/*:/opt/dolphinscheduler/plugins/storage-plugins/*' \
  org.apache.dolphinscheduler.server.worker.WorkerServer
