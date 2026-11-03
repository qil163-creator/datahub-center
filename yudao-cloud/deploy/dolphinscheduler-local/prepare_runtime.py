#!/usr/bin/env python3
"""下载并校验固定版本的 DataX、Spark、Flink 执行环境构建输入。"""

import hashlib
from pathlib import Path
import urllib.request


URL = "https://datax-opensource.oss-cn-hangzhou.aliyuncs.com/202308/datax.tar.gz"
SHA256 = "e925d0873e78aea28bf47b96866d127faacdc5a993ef1a92e25189151d5e1aa1"
DESTINATION = Path(__file__).resolve().parent / "runtime" / "datax-202308.tar.gz"
ARTIFACTS = [
    (URL, SHA256, DESTINATION),
    ("https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar",
     "6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93",
     DESTINATION.parent / "postgresql-42.7.13.jar"),
    ("https://repo.maven.apache.org/maven2/org/apache/flink/flink-connector-jdbc/3.3.0-1.20/flink-connector-jdbc-3.3.0-1.20.jar",
     "50d243cf0e1aac67c2c81c9fb2e9b32bb7d7124b9594df009efa8122e3df1c75",
     DESTINATION.parent / "flink-connector-jdbc-3.3.0-1.20.jar"),
    ("https://repo.maven.apache.org/maven2/org/apache/flink/flink-sql-connector-kafka/3.3.0-1.20/flink-sql-connector-kafka-3.3.0-1.20.jar",
     "1086f3eee73d727e234860fcd03adafc0d76f2fc70a25d39c36427693fff749d",
     DESTINATION.parent / "flink-sql-connector-kafka-3.3.0-1.20.jar"),
    ("https://repo.maven.apache.org/maven2/org/mongodb/spark/mongo-spark-connector_2.12/10.7.0/mongo-spark-connector_2.12-10.7.0.jar",
     "1b0908775a41d72621944a43e36ed83df4dbaff5bf8581811f0b5e9eabeb7cbe",
     DESTINATION.parent / "mongo-spark" / "mongo-spark-connector_2.12-10.7.0.jar"),
    ("https://repo.maven.apache.org/maven2/org/mongodb/mongodb-driver-sync/5.1.4/mongodb-driver-sync-5.1.4.jar",
     "341880078296edd762756440e9d6b1d6d2bf4d6b91975c2638e3da611f28b1c2",
     DESTINATION.parent / "mongo-spark" / "mongodb-driver-sync-5.1.4.jar"),
    ("https://repo.maven.apache.org/maven2/org/mongodb/mongodb-driver-core/5.1.4/mongodb-driver-core-5.1.4.jar",
     "ae3dbfd439d5afe9e0d6abbd61ef27d858ca2d38083bb7361f69e243aff31dec",
     DESTINATION.parent / "mongo-spark" / "mongodb-driver-core-5.1.4.jar"),
    ("https://repo.maven.apache.org/maven2/org/mongodb/bson/5.1.4/bson-5.1.4.jar",
     "bba556a8acd4e87545c1b9a1cb25c12ce9587ed5bf01685f236c5d92abf1e676",
     DESTINATION.parent / "mongo-spark" / "bson-5.1.4.jar"),
]


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def prepare(url, expected_digest, destination):
    if destination.exists() and digest(destination) == expected_digest:
        print(destination.name + " 校验通过，复用已有构建输入")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".download")
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            total = 0
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                total += len(block)
                if total > 2 * 1024 * 1024 * 1024:
                    raise RuntimeError("执行环境文件超出预期大小，拒绝继续下载")
                output.write(block)
        if digest(temporary) != expected_digest:
            raise RuntimeError("执行环境文件摘要不一致，拒绝作为执行环境")
        temporary.replace(destination)
        print(destination.name + " 下载及 SHA-256 校验通过")
    finally:
        temporary.unlink(missing_ok=True)


def main():
    # 可选 HDFS 数据源在基础环境启动后使用目标 Spark/Hadoop SDK 构建，空目录不阻断首次镜像构建。
    (DESTINATION.parent / "hdfs").mkdir(parents=True, exist_ok=True)
    (DESTINATION.parent / "redis").mkdir(parents=True, exist_ok=True)
    (DESTINATION.parent / "odps").mkdir(parents=True, exist_ok=True)
    (DESTINATION.parent / "datax-upsert").mkdir(parents=True, exist_ok=True)
    for artifact in ARTIFACTS:
        prepare(*artifact)


if __name__ == "__main__":
    main()
