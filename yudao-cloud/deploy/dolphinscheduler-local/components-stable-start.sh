#!/usr/bin/env bash
set -euo pipefail
umask 077

# 本实例只使用自己的三个卷；不读取、迁移或清理其他 DS 的数据库文件。
mkdir -p /var/lib/components-stable/metadata /tmp/dolphinscheduler /opt/dolphinscheduler/logs /opt/flink/usrlib
chmod 0755 /opt/flink/usrlib
chown -R datahub:datahub /var/lib/components-stable /tmp/dolphinscheduler /opt/dolphinscheduler/logs
if [[ -f /var/lib/components-stable/metadata/initialized ]]; then
  export SPRING_SQL_INIT_MODE=never
  mkdir -p /tmp/components-stable-metadata-helper
  "$JAVA_HOME/bin/javac" -J-Xmx64m -d /tmp/components-stable-metadata-helper /run/ComponentsStableMetadata.java
  "$JAVA_HOME/bin/java" -Xmx64m -cp /tmp/components-stable-metadata-helper:/opt/dolphinscheduler/api-server/libs/h2-2.2.220.jar ComponentsStableMetadata
else
  export SPRING_SQL_INIT_MODE=always
fi

# 仅安装经内容摘要固定的本次执行依赖；不改镜像中的官方引擎入口。
for source in /run/components-stable-artifacts/usrlib/*.jar; do
  [[ -f "$source" ]] || continue
  install -m 0444 "$source" "/opt/flink/usrlib/$(basename "$source")"
done
if [[ -f /run/components-stable-artifacts/spark-jars.json ]]; then
  python3 - <<'PY'
import hashlib,json,re
from pathlib import Path
root=Path('/run/components-stable-artifacts')
for item in json.loads((root/'spark-jars.json').read_text()):
    target=Path(item['path']); checksum=item['sha256']
    if str(target.parent)!='/opt/spark/jars' or not re.fullmatch(r'datahub-etl-(ftp|hdfs|redis|odps)\.jar',target.name):
        raise RuntimeError('Spark SPI 安装位置不是本任务批准的路径')
    source=root/item['installFile']; data=source.read_bytes()
    if hashlib.sha256(data).hexdigest()!=checksum:
        raise RuntimeError('Spark SPI 不可变摘要不匹配')
    target.write_bytes(data);target.chmod(0o444)
PY
fi
exec runuser -u datahub -- /bin/bash /opt/dolphinscheduler/standalone-server/bin/start.sh
