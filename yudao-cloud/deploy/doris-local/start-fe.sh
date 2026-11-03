#!/usr/bin/env bash
set -euo pipefail
set -m

fe_directory=/opt/apache-doris/fe
configuration="${fe_directory}/conf/fe.conf"
test -f "${configuration}.datahub-original" || cp "${configuration}" "${configuration}.datahub-original"
sed -E 's/-Xmx[0-9]+[kKmMgG]/-Xmx1024m/g; s/-Xms[0-9]+[kKmMgG]/-Xms1024m/g' \
  "${configuration}.datahub-original" > "${configuration}"
cat >> "${configuration}" <<EOF

# 本地单节点实例配置；凭据从未纳入版本控制的环境文件注入。
priority_networks = 127.0.0.1/32
enable_fqdn_mode = false
initial_root_password = ${DORIS_ROOT_PASSWORD_HASH:?缺少 root 密码哈希}
skip_localhost_auth_check = false
force_olap_table_replication_num = 1
disable_balance = true
EOF

child_pid=
cleanup() {
  trap - TERM INT
  # 官方停止脚本默认强制终止，必须显式使用 --grace。
  bash "${fe_directory}/bin/stop_fe.sh" --grace || {
    test -z "${child_pid}" || kill -TERM -- "-${child_pid}" 2>/dev/null || true
  }
  test -z "${child_pid}" || wait "${child_pid}" || true
  exit 0
}
trap cleanup TERM INT
bash "${fe_directory}/bin/start_fe.sh" --console &
child_pid=$!
wait "${child_pid}"
