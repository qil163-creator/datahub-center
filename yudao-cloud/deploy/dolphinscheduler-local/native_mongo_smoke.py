#!/usr/bin/env python3
"""在隔离合成集合中验证真实 Spark/MongoDB 连接器的 INSERT、按键更新和错误类型拒绝。"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
SQL_DIR = ROOT / "yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-mongo-sql"


def run(args, *, timeout=45, check=True):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("命令失败：" + " ".join(args[:5]) + "\n" + result.stdout[-3000:] + result.stderr[-3000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", default="datahub-ds-local-standalone-1")
    parser.add_argument("--mongo", default="datahub-ds-local-mongo-native-1")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "verification/native-mongo-components-20261102.json")
    options = parser.parse_args()
    unique = uuid.uuid4().hex[:12]
    source, target = "native_input_" + unique, "native_output_" + unique

    def mongo(script):
        return run(["docker", "exec", options.mongo, "mongosh", "--quiet", "--eval", script]).stdout.strip()

    def rows():
        script = "JSON.stringify(db.getSiblingDB('datahub_components').getCollection(" + json.dumps(target) + ").find({}).sort({id:1}).toArray().map(v=>({id:v.id.toString(),label:v.label,origin:v.origin})))"
        return json.loads(mongo(script))

    def execute(mode, bad=False):
        canonical = SQL_DIR / (mode + ".sql")
        if not canonical.is_file():
            raise RuntimeError("请先运行 EtlNativeMongoCompilerTest 生成真实编译 SQL")
        sql = canonical.read_text().replace("'native_input'", "'" + source + "'")
        sql = sql.replace("'native_output'", "'" + target + ("_bad" if bad else "") + "'")
        local = Path("/tmp") / ("datahub-mongo-native-" + unique + "-" + mode + ("-bad" if bad else "") + ".sql")
        local.write_text(sql)
        remote = "/tmp/" + local.name
        run(["docker", "cp", str(local), options.worker + ":" + remote])
        result = run(["docker", "exec", "-u", "10001", "-e", "JAVA_HOME=/opt/java17", options.worker,
                      "/opt/spark/bin/spark-sql-managed-original", "--master", "local[1]", "--driver-memory", "512m",
                      "--conf", "spark.sql.catalogImplementation=in-memory", "-f", remote], timeout=60, check=not bad)
        return result, hashlib.sha256(sql.encode()).hexdigest()

    mongo("db.getSiblingDB('datahub_components').getCollection(" + json.dumps(source)
          + ").insertMany([{source_id:NumberLong('101'),label:\"O'Reilly\"},{source_id:NumberLong('102'),label:'Beta'}])")
    inserted, insert_hash = execute("insert")
    expected = [{"id": "101", "label": "O'REILLY", "origin": "mongo-native"}, {"id": "102", "label": "BETA", "origin": "mongo-native"}]
    assert rows() == expected, rows()
    mongo("db.getSiblingDB('datahub_components').getCollection(" + json.dumps(source)
          + ").updateOne({source_id:NumberLong('102')},{$set:{label:'gamma'}})")
    updated, update_hash = execute("update")
    expected[1]["label"] = "GAMMA"
    actual = rows()
    assert actual == expected, actual
    mongo("db.getSiblingDB('datahub_components').getCollection(" + json.dumps(source)
          + ").insertOne({source_id:'not-a-number',label:'Beta'})")
    rejected, bad_hash = execute("insert", bad=True)
    assert rejected.returncode != 0, rejected.stdout[-3000:] + rejected.stderr[-3000:]
    error = rejected.stdout + rejected.stderr
    assert "DataException" in error or "Invalid field" in error or "Cannot cast" in error, error[-3000:]
    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "engine": "Spark 3.5.6 native SQL", "mongo": "MongoDB 7.0.43",
        "connector": "MongoDB Spark 10.7.0 + Java Driver 5.1.4", "database": "datahub_components",
        "sourceCollection": source, "targetCollection": target,
        "insert": {"exitCode": inserted.returncode, "actualRows": [{**expected[0]}, {**expected[1], "label": "BETA"}]},
        "update": {"exitCode": updated.returncode, "actualRows": actual, "documentCountRemainsTwo": len(actual) == 2},
        "badType": {"exitCode": rejected.returncode, "rejected": True},
        "sqlSha256": {"insert": insert_hash, "update": update_hash, "badType": bad_hash},
        "limits": ["原生引擎与外部MongoDB端到端；未声明本次管理端触发验收", "显式匿名受管环境；认证秘密引用尚未适配",
                   "Spark批次写入不是全作业事务，失败可能已有批次提交，不声明自动回滚或恰好一次"],
    }
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
