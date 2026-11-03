#!/usr/bin/env bash
set -euo pipefail
umask 077

# 受管 SQL 的结果仅由 root 监督器写入，Flink/Spark 继续使用非 root 执行用户。
mkdir -p /opt/datahub-native-runtime/tls
for name in ca.pem server.pem server-key.pem; do
  install -m 0600 -o root -g root "/run/datahub-native-runtime-tls-source/$name" "/opt/datahub-native-runtime/tls/$name"
done
test -r /opt/datahub-native-runtime/tls/server-key.pem
python3 /opt/datahub-native-runtime/local_runtime_observer.py &
observer_pid=$!
sleep 0.3
kill -0 "$observer_pid"
chown -R datahub:datahub /opt/dolphinscheduler/logs
exec runuser -u datahub -- /bin/bash /opt/dolphinscheduler/standalone-server/bin/start.sh
