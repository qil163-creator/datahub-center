#!/usr/bin/env python3
"""验收真实 RocketMQ 持续读写、检查点确认后恢复、精确字段映射和失败关闭。"""
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
        raise RuntimeError(result.stdout[-2500:] + result.stderr[-2500:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--contract-only', action='store_true')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-rocketmq.json')
    options = parser.parse_args()
    stage = ROOT / 'deploy/dolphinscheduler-local/runtime/rocket-native'
    manifest = json.loads((stage / 'manifest.json').read_text())
    fixture = MODULE / 'target/native-rocket-sql/rocket-mapped.sql'
    if not fixture.is_file():
        raise RuntimeError('请先运行 EtlNativeRocketCompilerTest 生成字段映射 SQL')
    remote = '/tmp/datahub-rocket-' + uuid.uuid4().hex
    run(['docker', 'exec', options.worker, 'mkdir', '-p', remote + '/classes', remote + '/logs'])
    run(['docker', 'exec', options.worker, 'chown', '-R', '10001:10001', remote])
    api = stage / 'api'; api.mkdir(exist_ok=True)
    provided = json.loads(run(['docker', 'exec', options.worker, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')))"]).stdout.splitlines()[-1])
    for path in provided:
        local = api / Path(path).name
        if not local.is_file():
            run(['docker', 'cp', options.worker + ':' + path, str(local)])
    classes = stage / 'probe-classes'; classes.mkdir(exist_ok=True)
    sources = sorted((MODULE / 'runtime/rocketmq-smoke').glob('*.java'))
    classpath = ':'.join([manifest['artifact'], *[str(path) for path in api.glob('*.jar')]])
    run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, sources)])
    run(['docker', 'cp', str(classes) + '/.', options.worker + ':' + remote + '/classes'])
    environment = ['docker', 'exec', '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_SECRET_DIR=/opt/datahub-rocket-secrets',
                   '-e', 'DATAHUB_ETL_SECRET_REFS=rocket.fixture,rocket.invalid', '-e', 'FLINK_LOG_DIR=' + remote + '/logs', options.worker]
    java = ['/opt/java17/bin/java', '-Xmx128m', '-cp', '/opt/flink/lib/*:' + remote + '/classes']
    contract = run(environment + java + ['cn.iocoder.yudao.framework.etl.flink.rocketmq.ManagedRocketContractProbe'])
    contract_report = json.loads(contract.stdout.splitlines()[-1])
    if options.contract_only:
        print(json.dumps(contract_report)); return
    def client(*args):
        return json.loads(run(environment + java + ['cn.iocoder.yudao.framework.etl.flink.rocketmq.RocketFixtureClient', *args]).stdout.splitlines()[-1])
    def publish(row, topic='components_input'):
        payload = stage / 'payload.json'; payload.write_text(json.dumps(row, ensure_ascii=False))
        run(['docker', 'cp', str(payload), options.worker + ':' + remote + '/payload.json'])
        client('publish', remote + '/payload.json', topic)
    def read():
        return client('read')
    prefix = "SET 'execution.target'='local';\nSET 'execution.runtime-mode'='STREAMING';\nSET 'execution.attached'='true';\nSET 'table.dml-sync'='true';\nSET 'parallelism.default'='1';\nSET 'execution.checkpointing.interval'='1000ms';\nSET 'restart-strategy.type'='none';\n"
    query = prefix + fixture.read_text()
    def start(name):
        path = stage / (name + '.sql'); path.write_text(query)
        run(['docker', 'cp', str(path), options.worker + ':' + remote + '/' + name + '.sql'])
        marker = remote + '/' + name + '.pid'
        invocation = 'echo $$ > ' + marker + '; exec timeout --signal=INT --kill-after=10s 90s /opt/flink/bin/sql-client-managed-original.sh -f ' + remote + '/' + name + '.sql'
        return subprocess.Popen(environment + ['/bin/bash', '-c', invocation], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True), marker
    def stop(process, marker):
        if process.poll() is None:
            stopper = "import os,signal,pathlib;p=pathlib.Path(" + repr(marker) + ");pid=int(p.read_text());cmd=pathlib.Path('/proc/'+str(pid)+'/cmdline').read_bytes();assert b'timeout' in cmd and " + repr(remote.encode()) + " in cmd;os.kill(pid,signal.SIGINT);p.unlink()"
            run(['docker', 'exec', '-u', '10001', options.worker, 'python3', '-c', stopper], timeout=10)
        result = process.communicate(timeout=20)[0]
        if '[ERROR] Could not execute SQL statement.' in result:
            raise RuntimeError(result[-4000:])
        return result
    def await_row(expected, process):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('持续作业提前退出：' + process.communicate(timeout=5)[0][-4000:])
            rows = read()
            if expected in rows:
                return rows
            time.sleep(0.5)
        raise RuntimeError('持续消息未在预算内输出：' + str(expected))
    first = {'id': 9007199254740993, 'name': "河道 O'Reilly"}
    live = {'id': 9007199254740994, 'name': 'live_启动后'}
    restarted = {'id': 9007199254740995, 'name': 'restart_检查点后'}
    mapped = lambda row: {'id': row['id'], '标签': row['name']}
    before = read(); publish(first)
    process, marker = start('stream'); log = ''
    try:
        await_row(mapped(first), process); publish(live); actual = await_row(mapped(live), process)
        deadline = time.monotonic() + 20
        while True:
            offsets = client('offsets')
            if offsets and all(item['saved'] == item['end'] and item['saved'] >= 0 for item in offsets):
                break
            if time.monotonic() > deadline:
                raise RuntimeError('Flink 完成检查点后 Broker 位点仍未确认')
            time.sleep(0.5)
    finally:
        log += stop(process, marker)
    publish(restarted); resumed, resume_marker = start('resumed')
    try:
        actual = await_row(mapped(restarted), resumed)
        if any(actual.count(mapped(row)) != before.count(mapped(row)) + 1 for row in [first, live, restarted]):
            raise RuntimeError('已确认检查点恢复后出现重复或丢失')
    finally:
        log += stop(resumed, resume_marker)
    negative = []
    for name, topic, group, change, body in [
            ('fractional-integer', 'components_input_fraction', 'components_fraction', lambda sql: sql, {'id': 1.5, 'name': 'invalid'}),
            ('unapproved-secret-ref', 'components_input_secret', 'components_secret', lambda sql: sql.replace("'credential-ref'='rocket.fixture'", "'credential-ref'='rocket.other'"), first),
            ('credential-address-redirect', 'components_input_redirect', 'components_redirect', lambda sql: sql.replace("'nameservers'='rocket-native-namesrv:9876'", "'nameservers'='another-host:9876'"), first),
            ('broker-invalid-authentication', 'components_input_auth', 'components_auth', lambda sql: sql.replace("'credential-ref'='rocket.fixture'", "'credential-ref'='rocket.invalid'"), first)]:
        publish(body, topic); baseline = read()
        sql = change(query.replace("'topic'='components_input'", "'topic'='" + topic + "'").replace("'group.id'='components_read'", "'group.id'='" + group + "'"))
        path = stage / (name + '.sql'); path.write_text(sql)
        run(['docker', 'cp', str(path), options.worker + ':' + remote + '/' + name + '.sql'])
        result = run(environment + ['timeout', '--signal=TERM', '--kill-after=5s', '40s', '/opt/flink/bin/sql-client-managed-original.sh', '-f', remote + '/' + name + '.sql'], timeout=50, check=False)
        failed = result.returncode != 0 or '[ERROR] Could not execute SQL statement.' in result.stdout
        if result.returncode in (124, 137) or not failed or read() != baseline:
            raise RuntimeError('消息或资源护栏未确定失败关闭：' + name + '\n' + result.stdout[-2000:])
        negative.append({'case': name, 'exitCode': result.returncode, 'runtimeFailureObserved': True, 'newOutputRows': 0})
    report = {'verifiedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Apache Flink1.20.3 / Java17', 'sdk': 'Official RocketMQ5.3.2 client/ACL SDK, managed DynamicTable adapter',
              'broker': 'Official Apache RocketMQ5.3.2 isolated NameServer/Broker with ACL1, no host ports', 'actualRows': actual, 'initialAndLiveExact': True,
              'mappedUnicodeOutput': True, 'brokerCheckpointOffsets': offsets, 'confirmedOffsetRestartExact': True, 'stopped': process.poll() is not None and resumed.poll() is not None,
              'jobIds': re.findall(r'Job ID:\s*([a-f0-9]{32})', log), 'runtimeContract': contract_report, 'negative': negative,
              'sqlSha256': hashlib.sha256(query.encode()).hexdigest(), 'runtimeJarSha256': manifest['jarSha256'],
              'limits': ['Official SDK5.3.2 and observed Flink1.20.3/Java17 only; no claim for arbitrary RocketMQ/Flink versions.',
                         'Independent approved task consumer group; at least once across external output failures; no transactional exactly-once claim.',
                         'Direct Flink CLI acceptance, not a management API or scheduled DS instance.', 'Plaintext transport only within this isolated authenticated fixture; TLS trust settings fail closed but TLS handshake not accepted here.']}
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(options.output), 'positiveRows': len(actual), 'negativeCases': len(negative)}))


if __name__ == '__main__':
    main()
