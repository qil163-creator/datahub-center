#!/usr/bin/env bash
set -euo pipefail

# 本地 H2 元数据随现有持久日志卷保存；只初始化新库，不重复执行上游包含 DROP 的脚本。
metadata=/opt/dolphinscheduler/logs/.metadata
database="$metadata/dolphinscheduler"
mkdir -p "$metadata"
as_datahub() {
  if [[ "$(id -un)" == "datahub" ]]; then "$@"; else runuser -u datahub -- "$@"; fi
}
if [[ "$(id -u)" == "0" ]]; then
  chown -R datahub:datahub /opt/dolphinscheduler/logs
elif [[ "$(id -un)" != "datahub" ]]; then
  echo '本地 DS 仅允许 root 或 datahub 启动' >&2
  exit 1
fi
if [[ ! -f "$database.mv.db" ]]; then
  as_datahub /opt/java/openjdk/bin/java \
    -cp /opt/dolphinscheduler/libs/h2-2.2.220.jar org.h2.tools.RunScript \
    -url "jdbc:h2:file:$database;MODE=MySQL;DATABASE_TO_LOWER=true" \
    -user sa -script /opt/dolphinscheduler/standalone-server/conf/sql/dolphinscheduler_h2.sql
fi
export SPRING_DATASOURCE_URL="jdbc:h2:file:$database;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_LOWER=true"
export SPRING_SQL_INIT_MODE=never
# 仅调整本地单 JVM 默认值：保留 CPU 等保护，避免开发宿主占用超过 80% 后永久无法分发任务。
# 显式 Spring 环境变量优先；本地统一覆盖变量允许用户按机器资源调整。
export MASTER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS="${MASTER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS:-${DATAHUB_LOCAL_DS_MAX_MEMORY_USAGE:-0.9}}"
export WORKER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS="${WORKER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS:-${DATAHUB_LOCAL_DS_MAX_MEMORY_USAGE:-0.9}}"
export WORKER_PHYSICALTASKCONFIG_TASKEXECUTORTHREADSIZE="${WORKER_PHYSICALTASKCONFIG_TASKEXECUTORTHREADSIZE:-${DATAHUB_LOCAL_DS_WORKER_THREADS:-2}}"
if [[ "${1:-}" == "--prepare-only" ]]; then
  exit 0
fi

# 文件型 H2 默认独占打开；已有 JVM 持有库锁时本步骤失败，绝不并发清理运行中的注册表。
# 单 JVM 已停止后的协调节点全部失效，不能等待旧会话过期再通知同地址的新进程下线。
cleanup=$(mktemp "$metadata/.registry-cleanup.XXXXXX.sql")
trap 'rm -f "$cleanup"' EXIT
cat > "$cleanup" <<'SQL'
SET AUTOCOMMIT FALSE;
DELETE FROM t_ds_jdbc_registry_data WHERE data_type='EPHEMERAL';
DELETE FROM t_ds_jdbc_registry_lock;
DELETE FROM t_ds_jdbc_registry_client_heartbeat;
DELETE FROM t_ds_jdbc_registry_data_change_event;
COMMIT;
SHUTDOWN;
SQL
if [[ "$(id -u)" == "0" ]]; then chown datahub:datahub "$cleanup"; fi
as_datahub /opt/java/openjdk/bin/java \
  -cp /opt/dolphinscheduler/libs/h2-2.2.220.jar org.h2.tools.RunScript \
  -url "jdbc:h2:file:$database;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_LOWER=true" \
  -user sa -script "$cleanup"
rm -f "$cleanup"
trap - EXIT
echo 'DATAHUB_LOCAL_REGISTRY_CLEANUP: 已清理停止的单 JVM 协调元数据，业务定义与实例保留'
echo "DATAHUB_LOCAL_LOAD_LIMITS: masterMemory=$MASTER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS workerMemory=$WORKER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS workerThreads=$WORKER_PHYSICALTASKCONFIG_TASKEXECUTORTHREADSIZE"
upstream=/opt/dolphinscheduler/standalone-server/bin/start-upstream.sh
if [[ ! -f "$upstream" ]]; then
  echo '缺少官方上游启动入口，拒绝递归调用包装脚本' >&2
  exit 1
fi
if [[ "$(id -un)" == "datahub" ]]; then
  exec /bin/bash "$upstream"
fi
exec runuser -u datahub -- /bin/bash "$upstream"
