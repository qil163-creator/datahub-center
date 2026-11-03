#!/usr/bin/env python3
"""用实际编译器生成的 SQL 验证 Kafka 批处理、持续采集和受控停止，不经过本地 ETL 引擎。"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
SOURCE = "datahub_component_native_input"
TARGET = "datahub_component_native_output"
SQL_DIR = ROOT / "yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-kafka-sql"


def run(args, *, data=None, timeout=45, check=True):
    result = subprocess.run(args, input=data, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("命令失败：" + " ".join(args[:5]) + "\n" + result.stderr[-3000:] + result.stdout[-3000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", default="datahub-ds-local-standalone-1")
    parser.add_argument("--broker", default="datahub-ds-local-kafka-1")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "verification/native-kafka-components-20261102.json")
    options = parser.parse_args()
    def command(script, args, data=None):
        base = ["docker", "exec"] + (["-i"] if data is not None else []) + [options.broker, "/opt/kafka/bin/" + script]
        return run(base + ["--bootstrap-server", "kafka:9092"] + args, data=data)

    def offset():
        text = command("kafka-get-offsets.sh", ["--topic", TARGET, "--time", "-1"]).stdout.strip()
        return int(text.rsplit(":", 1)[1])

    def rows_since(start):
        end = offset()
        if end == start:
            return []
        text = command("kafka-console-consumer.sh", ["--topic", TARGET, "--partition", "0", "--offset", str(start),
                       "--max-messages", str(end - start), "--timeout-ms", "10000"]).stdout
        return [json.loads(line) for line in text.splitlines() if line.strip()]

    def produce(rows):
        command("kafka-console-producer.sh", ["--topic", SOURCE], data="".join(json.dumps(row) + "\n" for row in rows))

    for topic in [SOURCE, TARGET]:
        command("kafka-topics.sh", ["--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1", "--if-not-exists"])
    for name in ["batch", "stream"]:
        if not (SQL_DIR / (name + ".sql")).is_file():
            raise RuntimeError("请先运行 EtlNativeConnectorCompilerTest 生成真实编译 SQL")
        run(["docker", "cp", str(SQL_DIR / (name + ".sql")), options.worker + ":/tmp/datahub-kafka-component-" + name + ".sql"])
    run(["docker", "exec", "-u", "10001", options.worker, "mkdir", "-p", "/tmp/datahub-kafka-logs"])
    identifier = int(time.time() * 1000)
    fixtures = [{"id": identifier, "label": "alpha"}, {"id": identifier + 1, "label": "汉字" * 2200}]
    expected = [{**row, "label": row["label"].upper(), "origin": "kafka-native"} for row in fixtures]
    start = offset()
    produce(fixtures)
    batch = run(["docker", "exec", "-u", "10001", "-e", "JAVA_HOME=/opt/java17", "-e", "FLINK_LOG_DIR=/tmp/datahub-kafka-logs",
                 options.worker, "/opt/flink/bin/sql-client-managed-original.sh", "-f", "/tmp/datahub-kafka-component-batch.sql"], timeout=60)
    if "[ERROR] Could not execute SQL statement." in batch.stdout:
        raise RuntimeError(batch.stdout[-5000:])
    batch_rows = [row for row in rows_since(start) if row.get("id") in [identifier, identifier + 1]]
    assert sorted(batch_rows, key=lambda row: row["id"]) == expected, batch_rows

    start = offset()
    marker = "/tmp/datahub-kafka-stream-" + uuid.uuid4().hex + ".pid"
    invocation = "echo $$ > " + marker + "; exec timeout --signal=INT --kill-after=10s 90s /opt/flink/bin/sql-client-managed-original.sh -f /tmp/datahub-kafka-component-stream.sql"
    process = subprocess.Popen(["docker", "exec", "-u", "10001", "-e", "JAVA_HOME=/opt/java17", "-e", "FLINK_LOG_DIR=/tmp/datahub-kafka-logs",
                                options.worker, "/bin/bash", "-c", invocation], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    live = {"id": identifier + 2, "label": "live_after_start"}
    live_expected = {**live, "label": "LIVE_AFTER_START", "origin": "kafka-native"}
    stream_log = ""
    try:
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("持续采集作业提前结束：" + process.communicate(timeout=5)[0][-5000:])
            initial = rows_since(start)
            if all(row in initial for row in expected):
                break
            time.sleep(1)
        else:
            raise RuntimeError("持续采集未在限定时间输出已有消息")
        assert process.poll() is None
        produce([live])
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            stream_rows = rows_since(start)
            if live_expected in stream_rows:
                break
            time.sleep(1)
        else:
            raise RuntimeError("持续采集未读取启动后新产生的消息")
        assert process.poll() is None
    finally:
        if process.poll() is None:
            stopper = "import os,signal,pathlib;p=pathlib.Path(" + repr(marker) + ");pid=int(p.read_text());cmd=pathlib.Path('/proc/'+str(pid)+'/cmdline').read_bytes();assert b'timeout' in cmd and b'datahub-kafka-component-stream.sql' in cmd;os.kill(pid,signal.SIGINT);p.unlink()"
            run(["docker", "exec", "-u", "10001", options.worker, "python3", "-c", stopper], timeout=10)
        try:
            stream_log = process.communicate(timeout=20)[0]
        except subprocess.TimeoutExpired:
            process.kill()
            raise RuntimeError("持续采集未在停止期限内退出")
    if "[ERROR] Could not execute SQL statement." in stream_log:
        raise RuntimeError(stream_log[-5000:])
    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "engine": "Flink 1.20.3 native SQL",
        "broker": "Apache Kafka 3.9.1", "connector": "flink-sql-connector-kafka 3.3.0-1.20",
        "batch": {"exitCode": batch.returncode, "bounded": True, "actualRows": [
            row if len(row["label"]) < 2000 else {"id": row["id"], "labelCharacters": len(row["label"]),
                 "labelUtf8Bytes": len(row["label"].encode()), "labelSha256": hashlib.sha256(row["label"].encode()).hexdigest(), "origin": row["origin"]}
            for row in batch_rows], "longUnicodePayloadExactMatch": True},
        "stream": {"observedInitialMessages": True, "messageProducedAfterJobStarted": live_expected,
                   "observedLiveMessage": True, "stopped": process.poll() is not None, "exitCodeAfterSignal": process.returncode},
        "sqlSha256": {name: hashlib.sha256((SQL_DIR / (name + ".sql")).read_bytes()).hexdigest() for name in ["batch", "stream"]},
        "jobIds": re.findall(r"Job ID:\s*([a-f0-9]{32})", batch.stdout + stream_log),
        "limits": ["仅原生引擎与Kafka端到端验证，不包含本次重启平台服务后的管理端触发", "显式无凭据受管测试环境；SASL/TLS秘密引用尚未适配"],
    }
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
