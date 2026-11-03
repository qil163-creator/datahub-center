#!/usr/bin/env bash
set -euo pipefail

case "${1:?请指定 fe 或 be}" in
  fe) object=FRONTENDS ;;
  be) object=BACKENDS ;;
  *) exit 2 ;;
esac

mysql -uroot -h127.0.0.1 -P9030 --connect-timeout=2 --batch -e "SHOW ${object}" 2>/dev/null |
  awk -F '\t' '
    NR == 1 { for (i = 1; i <= NF; i++) column[$i] = i; next }
    $column["Host"] == "127.0.0.1" && $column["Alive"] == "true" { alive = 1 }
    END { exit !alive }
  '
