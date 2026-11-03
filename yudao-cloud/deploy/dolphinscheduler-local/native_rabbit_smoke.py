#!/usr/bin/env python3
"""验证编译器 SQL、持续到达消息、确认输出和目标 Java17/Flink 的秘密/类型护栏。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl'


def run(arguments, timeout=60, check=True):
    result = subprocess.run(arguments, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stderr[-2500:] + result.stdout[-2500:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--contract-only', action='store_true')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-rabbitmq.json')
    options = parser.parse_args()
    stage = ROOT / 'deploy/dolphinscheduler-local/runtime/rabbit-native'
    fixture = MODULE / 'target/native-rabbit-sql/rabbit-mapped.sql'
    if not fixture.is_file():
        raise RuntimeError('请先运行 EtlNativeRabbitCompilerTest')
    identifier = uuid.uuid4().hex
    remote = '/tmp/datahub-rabbit-' + identifier
    run(['docker', 'exec', options.worker, 'mkdir', '-p', remote + '/classes', remote + '/logs'])
    run(['docker', 'exec', options.worker, 'chown', '-R', '10001:10001', remote])
    sources = sorted((MODULE / 'runtime/rabbitmq-smoke').glob('*.java'))
    for source in sources:
        run(['docker', 'cp', str(source), options.worker + ':' + remote + '/' + source.name])
    api = stage / 'api'; api.mkdir(exist_ok=True)
    provided = json.loads(run(['docker', 'exec', options.worker, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')))"]).stdout.splitlines()[-1])
    for path in provided:
        local = api / Path(path).name
        if not local.is_file():
            run(['docker', 'cp', options.worker + ':' + path, str(local)])
    classes = stage / 'probe-classes'; classes.mkdir(exist_ok=True)
    artifact = Path(json.loads((stage / 'manifest.json').read_text())['artifact'])
    classpath = ':'.join([str(artifact), *[str(path) for path in api.glob('*.jar')]])
    run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *[str(source) for source in sources]])
    run(['docker', 'cp', str(classes) + '/.', options.worker + ':' + remote + '/classes'])
    environment = ['docker', 'exec', '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_SECRET_DIR=/opt/datahub-rabbit-secrets',
        '-e', 'DATAHUB_ETL_SECRET_REFS=rabbit.fixture', '-e', 'FLINK_LOG_DIR=' + remote + '/logs', options.worker]
    java = ['/opt/java17/bin/java', '-Xmx128m', '-cp', '/opt/flink/lib/*:' + remote + '/classes']
    contract = run(environment + java + ['cn.iocoder.yudao.framework.etl.flink.rabbitmq.ManagedRabbitContractProbe'])
    contract_report = json.loads(contract.stdout.splitlines()[-1])
    if options.contract_only:
        print(json.dumps(contract_report)); return
    def client(*arguments):
        return run(environment + java + ['cn.iocoder.yudao.framework.etl.rabbitmq.smoke.RabbitFixtureClient', *arguments]).stdout
    client('purge')
    def publish(row):
        payload = stage / 'payload.json'; payload.write_text(json.dumps(row, ensure_ascii=False))
        run(['docker', 'cp', str(payload), options.worker + ':' + remote + '/payload.json'])
        client('publish', remote + '/payload.json')
    def read():
        return json.loads(client('read').splitlines()[-1])
    prefix = "SET 'execution.target'='local';\nSET 'execution.runtime-mode'='STREAMING';\nSET 'execution.attached'='true';\nSET 'table.dml-sync'='true';\nSET 'parallelism.default'='1';\nSET 'execution.checkpointing.interval'='1000ms';\nSET 'restart-strategy.type'='none';\n"
    query = prefix + fixture.read_text()
    local = stage / 'stream.sql'; local.write_text(query)
    run(['docker', 'cp', str(local), options.worker + ':' + remote + '/stream.sql'])
    first = {'id': 9007199254740993, 'name': "河道 O'Reilly"}
    live = {'id': 9007199254740994, 'name': 'live_启动后'}
    expected_first = {'id': first['id'], '标签': first['name']}
    expected_live = {'id': live['id'], '标签': live['name']}
    publish(first)
    marker = remote + '/process.pid'
    invocation = 'echo $$ > ' + marker + '; exec timeout --signal=INT --kill-after=10s 90s /opt/flink/bin/sql-client-managed-original.sh -f ' + remote + '/stream.sql'
    process = subprocess.Popen(environment + ['/bin/bash', '-c', invocation], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    actual = []; log = ''
    try:
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('持续消息作业提前退出：' + process.communicate(timeout=5)[0][-4000:])
            actual += read()
            if expected_first in actual:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('未收到精确大整数和 Unicode 消息')
        publish(live)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            actual += read()
            if expected_live in actual:
                break
            if process.poll() is not None:
                raise RuntimeError('持续消息作业未保持运行')
            time.sleep(0.5)
        else:
            raise RuntimeError('未读取启动后新到消息')
        if process.poll() is not None:
            raise RuntimeError('持续输入被误作为有限快照结束')
        time.sleep(2)
    finally:
        if process.poll() is None:
            stopper = "import os,signal,pathlib;p=pathlib.Path(" + repr(marker) + ");pid=int(p.read_text());cmd=pathlib.Path('/proc/'+str(pid)+'/cmdline').read_bytes();assert b'timeout' in cmd and " + repr((remote + '/stream.sql').encode()) + " in cmd;os.kill(pid,signal.SIGINT);p.unlink()"
            run(['docker', 'exec', '-u', '10001', options.worker, 'python3', '-c', stopper], timeout=10)
        log = process.communicate(timeout=20)[0]
    if '[ERROR] Could not execute SQL statement.' in log:
        raise RuntimeError(log[-4500:])
    negative = []
    for name, sql, payload in [
            ('fractional-integer', query, {'id': 1.5, 'name': 'invalid'}),
            ('unapproved-secret-ref', query.replace("'credential-ref'='rabbit.fixture'", "'credential-ref'='rabbit.other'"), first),
            ('credential-address-redirect', query.replace("'host'='rabbit-native'", "'host'='another-host'"), first)]:
        client('purge'); publish(payload)
        path = stage / (name + '.sql'); path.write_text(sql)
        run(['docker', 'cp', str(path), options.worker + ':' + remote + '/' + name + '.sql'])
        result = run(environment + ['timeout', '--signal=TERM', '--kill-after=5s', '40s', '/opt/flink/bin/sql-client-managed-original.sh', '-f', remote + '/' + name + '.sql'], timeout=50, check=False)
        if result.returncode in (124, 137):
            raise RuntimeError('消息护栏作业未在限定时间确定失败：' + name)
        failed = result.returncode != 0 or '[ERROR] Could not execute SQL statement.' in result.stdout
        if not failed or read():
            raise RuntimeError('消息/资源护栏未在真实 Flink 作业中失败关闭：' + name)
        negative.append({'case': name, 'exitCode': result.returncode, 'runtimeFailureObserved': True, 'outputRows': 0})
    report = {'verifiedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Apache Flink1.20.3 / Java17',
        'broker': 'RabbitMQ4.1.4 isolated fixture without host ports', 'connector': 'Official RMQSource/RMQSink 3.0.1-1.17 with managed DynamicTable adapter; observed target-version compatibility only',
        'actualRows': actual, 'startedThenLiveMessagePassed': expected_live in actual, 'mappedUnicodeOutput': True, 'stopped': process.poll() is not None,
        'jobIds': re.findall(r'Job ID:\s*([a-f0-9]{32})', log), 'runtimeContract': contract_report, 'negative': negative,
        'sqlSha256': hashlib.sha256(query.encode()).hexdigest(), 'runtimeJarSha256': json.loads((stage / 'manifest.json').read_text())['jarSha256'],
        'authorization': ['Compile checks task-approved database binding, direction-specific queue ACL and approved credentialRef.', 'Worker secret file separately verifies host/port/vhost/queue/SSL before creating connection.', 'Queue passive inspection only; source requires checkpointing, sink uses persistent mandatory messages and broker confirms.'],
        'limits': ['Direct official Flink CLI acceptance; no management API or DS scheduled instance is claimed.', 'Flink continuous JSON messages only; top-level fields, ISO dates, exact declared numeric bounds; message budget1MiB.', 'Input checkpoint acknowledgements and confirmed output can still duplicate across system recovery; no transactional exactly-once claim.']}
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(options.output), 'positiveRows': len(actual), 'negativeCases': len(negative)}))


if __name__ == '__main__':
    main()
