#!/usr/bin/env python3
"""原生DS Spark V2实际Kafka有限读取→字段转换→确认写，临时broker/网络/任务用后关闭。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

from smoke import Client
from engines_smoke import native_operations


def run(*arguments, data=None, check=True, timeout=120):
    result = subprocess.run(arguments, input=data, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stdout[-3500:] + result.stderr[-3500:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--connection', type=Path, required=True)
    parser.add_argument('--binding', type=Path, required=True)
    parser.add_argument('--worker', default='components-stable-ds')
    parser.add_argument('--environment', default='188629399953920')
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args(); root = Path(__file__).resolve().parents[2]
    module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    connection = json.loads(options.connection.read_text()); binding = json.loads(options.binding.read_text())
    if connection['server'] != 'http://127.0.0.1:12347/dolphinscheduler' or binding['installed']:
        raise RuntimeError('只允许隔离DS与未安装的独立fixture产物')
    client = Client(connection['server']); client.token = connection['token']; prefix = '/projects/' + connection['projectCode']
    private = Path(tempfile.mkdtemp(prefix='qdata-spark-kafka-')); private.chmod(0o700); sqlfile = private / 'spark.sql'; classes = private / 'classes'; classes.mkdir()
    classpath = str(module / 'target/classes') + ':' + str(root / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-common/target/classes') + ':' + Path('/tmp/datahub-etl-diagnostic-classpath.txt').read_text().strip()
    generator = module / 'runtime/messages-smoke/BoundedMessageCompilerFixture.java'
    run('javac', '-cp', classpath, '-d', str(classes), str(generator))
    run('java', '-cp', str(classes) + ':' + classpath, 'cn.iocoder.yudao.framework.etl.messages.BoundedMessageCompilerFixture', str(sqlfile))
    stamp = uuid.uuid4().hex[:12]; network = 'qdata-kafka-' + stamp; broker = 'qdata-kafka-' + stamp; remote = '/tmp/qdata-messages-' + stamp + '.jar'
    report = {'status': 'RUNNING', 'scope': 'Actual isolated DS SPARK plugin, Spark3.5.6 V2 provider and Kafka3.9.1 finite offset source -> upper-case transform -> confirmed output', 'checks': {}, 'cleanup': []}
    attached = created = container = copied = False
    try:
        run('docker', 'network', 'create', '--internal', network); created = True
        run('docker', 'network', 'connect', network, options.worker); attached = True
        config = {'KAFKA_NODE_ID': '1', 'KAFKA_PROCESS_ROLES': 'broker,controller', 'KAFKA_LISTENERS': 'PLAINTEXT://:9092,CONTROLLER://:9093',
                  'KAFKA_ADVERTISED_LISTENERS': 'PLAINTEXT://bounded-kafka:9092', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
                  'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER', 'KAFKA_INTER_BROKER_LISTENER_NAME': 'PLAINTEXT', 'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@bounded-kafka:9093',
                  'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR': '1', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR': '1',
                  'CLUSTER_ID': 'MkU3OEVBNTcwNTJENDM2Qk', 'KAFKA_HEAP_OPTS': '-Xms128m -Xmx256m'}
        command = ['docker', 'run', '-d', '--name', broker, '--network', network, '--network-alias', 'bounded-kafka', '--memory', '512m']
        for key, value in config.items(): command += ['-e', key + '=' + value]
        command += ['apache/kafka:3.9.1']; run(*command); container = True
        time.sleep(5)
        for attempt in range(10):
            result = subprocess.run(['docker', 'exec', broker, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'bounded-kafka:9092', '--list'], text=True, capture_output=True, timeout=20)
            if result.returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('独立Kafka未就绪：' + run('docker', 'logs', '--tail', '20', broker, check=False))
        for topic in ['qa_bounded_input', 'qa_bounded_output']:
            run('docker', 'exec', broker, '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'bounded-kafka:9092', '--create', '--topic', topic, '--partitions', '1', '--replication-factor', '1')
        run('docker', 'cp', binding['artifact'], options.worker + ':' + remote); copied = True; run('docker', 'exec', options.worker, 'chmod', '0644', remote)
        fixture = ['docker', 'exec', '-u', '10001', options.worker, '/opt/java17/bin/java', '-cp', remote + ':/opt/spark/jars/*', 'cn.iocoder.yudao.framework.etl.messages.BoundedMessageKafkaFixture']
        seeded = run(*fixture, 'seed')
        create, logs, bounded = native_operations(client, connection, {'SPARK': int(options.environment)}, report)
        params = {'resourceList': [], 'localParams': [], 'programType': 'SQL', 'rawScript': sqlfile.read_text(), 'master': 'local[1]', 'deployMode': 'local', 'driverCores': 1,
                  'driverMemory': '512m', 'numExecutors': 1, 'executorCores': 1, 'executorMemory': '512m', 'yarnQueue': '', 'others': '--jars ' + remote,
                  'sqlExecutionType': 'SCRIPT', 'sparkVersion': 'SPARK2', 'appName': 'QDATA_SPARK_KAFKA_BOUNDED_V2'}
        bounded('SPARK', params, 'SPARK', case_name='kafka-v2')
        verified = run(*fixture, 'verify')
        report.update({'status': 'PASS', 'seedConfirmed': 'kafka-seed-confirmed=2' in seeded, 'exactResult': verified.splitlines()[-1], 'jarSha256': binding['jarSha256'],
                       'sqlSha256': hashlib.sha256(sqlfile.read_bytes()).hexdigest(), 'generatorSha256': hashlib.sha256(generator.read_bytes()).hexdigest(),
                       'limits': ['Explicit anonymous isolated broker; no SASL/TLS support claimed.', 'Output confirmation is at least once; retries may duplicate external messages.', 'Runtime/DS native plugin acceptance, not a published management API integration task.']})
    finally:
        for check in report['checks'].values():
            code = check.get('workflowCode')
            if code:
                try:
                    client.request('POST', prefix + '/workflow-definition/' + code + '/release', {'releaseState': 'OFFLINE'})
                    report['cleanup'].append({'workflowCode': code, 'state': 'OFFLINE'})
                except Exception:
                    report['cleanup'].append({'workflowCode': code, 'state': 'CLEANUP_FAILED'})
        if copied: run('docker', 'exec', options.worker, 'rm', '-f', remote, check=False)
        if container: run('docker', 'rm', '-f', broker, check=False)
        if attached: run('docker', 'network', 'disconnect', network, options.worker, check=False)
        if created: run('docker', 'network', 'rm', network, check=False)
        report['cleanup'].append({'fixtureContainerRemoved': container, 'workerNetworkDetached': attached, 'isolatedNetworkRemoved': created, 'workerTemporaryJarRemoved': copied})
        if report['status'] != 'PASS': report['status'] = 'FAIL'
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'status': report['status'], 'report': str(options.output)}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
