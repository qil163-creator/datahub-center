#!/usr/bin/env python3
"""验收真实本地 Spark/Flink CLI、mTLS 和持续流取消，不输出原始任务日志。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import ssl
import subprocess
import threading
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--connection', type=Path, default=Path('/tmp/datahub-native-runtime-connection.json'))
    parser.add_argument('--checks', choices=['all', 'stream'], default='all')
    parser.add_argument('--output', type=Path, default=Path('/tmp/datahub-native-runtime-smoke.json'))
    args = parser.parse_args()
    connection = json.loads(args.connection.read_text())
    context = ssl.create_default_context(cafile=connection['caCertificate'])
    context.load_cert_chain(connection['clientCertificate'], connection['clientKey'])
    container = connection['container']
    stamp = str(int(time.time() * 1000))
    report = {'checks': {}, 'status': 'RUNNING'}

    def query(name):
        url = connection['serverUrl'] + '/v1/executions?name=' + name + '&user=datahub'
        with urllib.request.urlopen(url, context=context, timeout=10) as response:
            rows = json.load(response)['executions']
        return rows[0] if rows else None

    def execute(engine, suffix, script, marker, stream=False):
        name = 'dh_native_99' + stamp + suffix
        directory = '/tmp/dolphinscheduler/' + name
        script = "SET 'pipeline.name' = '" + name + "';\n" + script if engine == 'FLINK' else script
        scripts = {'task.sql': script}
        if engine == 'FLINK' and stream:
            split = script.index('CREATE TABLE')
            scripts = {'init.sql': script[:split], 'task.sql': script[split:]}
        setup = 'from pathlib import Path;import sys,json;p=Path(sys.argv[1]);p.mkdir(parents=True);[(p/name).write_text(content) for name,content in json.load(sys.stdin).items()]'
        subprocess.run(['docker', 'exec', '-i', '-u', '10001', container, 'python3', '-c', setup, directory], input=json.dumps(scripts).encode(), check=True)
        if engine == 'SPARK':
            command = ['/opt/spark/bin/spark-sql', '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.ui.enabled=false', '--name', name, '-f', directory + '/task.sql']
        else:
            command = ['/opt/flink/bin/sql-client.sh', *(['-i', directory + '/init.sql'] if stream else []), '-f', directory + '/task.sql']
        process = subprocess.Popen(['docker', 'exec', '-u', '10001', '-w', directory, container, *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        output = bytearray()
        digest = hashlib.sha256()
        marker_seen = threading.Event()

        def read_output():
            for chunk in iter(lambda: process.stdout.read1(8192), b''):
                digest.update(chunk)
                output.extend(chunk)
                if marker.encode() in output:
                    marker_seen.set()
                if len(output) > 262144:
                    del output[:-65536]

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        if stream:
            if not marker_seen.wait(150):
                raise RuntimeError('持续流未输出验收标记')
            running = query(name)
            if not running or running['state'] != 'RUNNING' or not running['processTreeAlive']:
                raise RuntimeError('持续流没有可信运行证据')
            stopper = '''import os,signal,sys
from pathlib import Path
matches=[]
for entry in Path('/proc').glob('[0-9]*/cmdline'):
 try:
  arguments=entry.read_bytes().split(bytes([0]))
  if b'/opt/datahub-native-runtime/local_runtime_cli.py' in arguments and sys.argv[1].encode() in arguments: matches.append(int(entry.parent.name))
 except OSError: pass
if len(matches)!=1: raise RuntimeError('未找到唯一受管入口')
os.kill(matches[0],signal.SIGINT)
'''
            subprocess.run(['docker', 'exec', '-u', '10001', container, 'python3', '-c', stopper, directory + '/task.sql'], check=True)
        try:
            process.wait(timeout=150)
        except subprocess.TimeoutExpired:
            raise RuntimeError('受管入口未在期限内退出') from None
        reader.join(timeout=10)
        deadline = time.monotonic() + 15
        final = query(name)
        while final and not final['finished'] and time.monotonic() < deadline:
            time.sleep(.1)
            final = query(name)
        expected = 'CANCELLED' if stream else ('FAILED' if suffix == '3' else 'SUCCEEDED')
        if not final or final['state'] != expected or not final['started'] or not final['finished'] or final['processTreeAlive']:
            raise RuntimeError('实际监督结果不符合预期: ' + json.dumps(final))
        if not marker_seen.is_set():
            raise RuntimeError('CLI 输出缺少预期标记')
        return {'execution': final, 'wrapperExitCode': process.returncode, 'markerInOutput': True, 'outputSha256': digest.hexdigest()}

    settings = "\n".join(["SET 'execution.runtime-mode' = 'BATCH';", "SET 'execution.target' = 'local';",
                           "SET 'table.dml-sync' = 'true';", "SET 'execution.attached' = 'true';",
                           "SET 'jobmanager.memory.process.size' = '512m';", "SET 'taskmanager.memory.process.size' = '768m';",
                           "SET 'taskmanager.numberOfTaskSlots' = '1';", "SET 'local.number-taskmanager' = '1';",
                           "SET 'parallelism.default' = '1';"]) + '\n'
    try:
        if args.checks == 'all':
            report['checks']['SPARK_BATCH'] = execute('SPARK', '1', "SELECT 'DATAHUB_NATIVE_SPARK', SUM(id) FROM RANGE(10);", 'DATAHUB_NATIVE_SPARK\t45')
            bounded = "CREATE TABLE sink_rows (message STRING) WITH ('connector'='print');\nINSERT INTO sink_rows VALUES ('DATAHUB_NATIVE_FLINK');"
            report['checks']['FLINK_BATCH'] = execute('FLINK', '2', settings + bounded, '+I[DATAHUB_NATIVE_FLINK]')
            report['checks']['FLINK_FAILURE'] = execute('FLINK', '3', settings + 'SELECT DATAHUB_MISSING_FUNCTION(1);', '[ERROR] Could not execute SQL statement.')
        continuous = "CREATE TABLE source_rows (id INT) WITH ('connector'='datagen','rows-per-second'='1');\nCREATE TABLE sink_rows (message STRING) WITH ('connector'='print');\nINSERT INTO sink_rows SELECT 'DATAHUB_NATIVE_STREAM' FROM source_rows;"
        report['checks']['FLINK_STREAM_CANCEL'] = execute('FLINK', '4', settings.replace("'BATCH'", "'STREAMING'") + continuous, '+I[DATAHUB_NATIVE_STREAM]', True)
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'
        raise
    finally:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
