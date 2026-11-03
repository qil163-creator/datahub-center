#!/usr/bin/env bash
set -euo pipefail
set -m

be_directory=/opt/apache-doris/be
configuration="${be_directory}/conf/be.conf"
test -f "${configuration}.datahub-original" || cp "${configuration}" "${configuration}.datahub-original"
sed -E 's/-Xmx[0-9]+[kKmMgG]/-Xmx512m/g; s/-Xms[0-9]+[kKmMgG]/-Xms512m/g' \
  "${configuration}.datahub-original" > "${configuration}"
cat >> "${configuration}" <<'EOF'

# 与 FE 共享网络空间，让宿主机可直接访问 Stream Load 重定向地址。
priority_networks = 127.0.0.1/32
mem_limit = 75%
EOF

registered="$(mysql -uroot -h127.0.0.1 -P9030 --connect-timeout=5 --batch -e 'SHOW BACKENDS' |
  awk -F '\t' '
    NR == 1 { for (i = 1; i <= NF; i++) column[$i] = i; next }
    $column["Host"] == "127.0.0.1" && $column["HeartbeatPort"] == "9050" { print "yes" }
  ')"
if [[ -z "${registered}" ]]; then
  mysql -uroot -h127.0.0.1 -P9030 --connect-timeout=5 \
    -e "ALTER SYSTEM ADD BACKEND '127.0.0.1:9050'"
fi

child_pid=
cleanup() {
  trap - TERM INT
  bash "${be_directory}/bin/stop_be.sh" --grace || {
    test -z "${child_pid}" || kill -TERM -- "-${child_pid}" 2>/dev/null || true
  }
  test -z "${child_pid}" || wait "${child_pid}" || true
  exit 0
}
trap cleanup TERM INT
bash "${be_directory}/bin/start_be.sh" --console &
child_pid=$!
wait "${child_pid}"
