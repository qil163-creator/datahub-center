#!/usr/bin/env python3
"""隔离DS Spark V2执行受管RabbitMQ有限浏览与确认输出，秘密/环境/网络不复用业务配置。"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import shutil
import tempfile
import time
import uuid

from smoke import Client
from engines_smoke import native_operations


def run(*arguments, check=True):
    result = subprocess.run(arguments, text=True, capture_output=True, timeout=120)
    if check and result.returncode:
        raise RuntimeError(result.stdout[-2500:] + result.stderr[-2500:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--connection', type=Path, required=True); parser.add_argument('--binding', type=Path, required=True)
    parser.add_argument('--worker', default='components-stable-ds'); parser.add_argument('--environment', default='188629399953920')
    parser.add_argument('--output', type=Path, required=True); options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]; module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    connection = json.loads(options.connection.read_text()); binding = json.loads(options.binding.read_text())
    if connection['server'] != 'http://127.0.0.1:12347/dolphinscheduler' or binding['installed']: raise RuntimeError('只允许隔离组件DS与独立构建产物')
    client = Client(connection['server']); client.token = connection['token']; prefix = '/projects/' + connection['projectCode']
    private = Path(tempfile.mkdtemp(prefix='qdata-spark-rabbit-')); private.chmod(0o700); sqlfile = private / 'spark.sql'; classes = private / 'classes'; classes.mkdir()
    trusted = private / 'trusted'; shutil.copytree(module / 'target/classes', trusted)
    shutil.copytree(root / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-common/target/classes', trusted, dirs_exist_ok=True)
    classpath = str(trusted) + ':' + Path('/tmp/datahub-etl-diagnostic-classpath.txt').read_text().strip()
    generator = module / 'runtime/messages-smoke/BoundedMessageCompilerFixture.java'; run('javac', '-cp', classpath, '-d', str(classes), str(generator))
    run('java', '-cp', str(classes) + ':' + classpath, 'cn.iocoder.yudao.framework.etl.messages.BoundedMessageCompilerFixture', str(sqlfile), 'rabbitmq')
    stamp = uuid.uuid4().hex[:12]; network = 'qdata-rabbit-' + stamp; broker = 'qdata-rabbit-' + stamp; remote = '/tmp/qdata-rabbit-' + stamp
    password = secrets.token_hex(32); environment = private / 'rabbit.env'; environment.write_text('RABBITMQ_DEFAULT_USER=qa_fixture\nRABBITMQ_DEFAULT_PASS=' + password + '\nRABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2\nRABBITMQ_CTL_ERL_ARGS=+S 1:1\n'); environment.chmod(0o600)
    secret = private / 'qa.rabbit'; secret.write_text('host=bounded-rabbit\nport=5672\nvirtualHost=/\nsourceQueues=qa_bounded_input,qa_bounded_output\nsinkQueues=qa_bounded_input,qa_bounded_output\nuseSsl=false\nusername=qa_fixture\npassword=' + password + '\n'); secret.chmod(0o600)
    report = {'status': 'RUNNING', 'scope': 'Actual isolated DS SPARK plugin, Spark3.5.6 V2 -> RabbitMQ4.1.4 bounded exclusive non-ack browse -> transform -> confirmed output', 'checks': {}, 'cleanup': []}
    attached = created = container = copied = False; env_code = None
    try:
        run('docker', 'network', 'create', '--internal', network); created = True; run('docker', 'network', 'connect', network, options.worker); attached = True
        run('docker', 'run', '-d', '--user', '0:0', '--name', broker, '--hostname', 'bounded-rabbit', '--network', network, '--network-alias', 'bounded-rabbit', '--memory', '512m', '--env-file', str(environment), 'rabbitmq:4.1.4'); container = True
        for attempt in range(30):
            ready = subprocess.run(['docker', 'exec', broker, 'rabbitmq-diagnostics', '-q', 'check_running'], text=True, capture_output=True, timeout=10)
            if ready.returncode == 0: break
            time.sleep(.3)
        else: raise RuntimeError('独立RabbitMQ未就绪：' + run('docker', 'logs', '--tail', '100', broker, check=False).replace(password, '[fixture-secret]'))
        run('docker', 'exec', options.worker, 'mkdir', '-p', remote + '/secrets'); copied = True
        run('docker', 'cp', str(secret), options.worker + ':' + remote + '/secrets/qa.rabbit'); run('docker', 'cp', binding['artifact'], options.worker + ':' + remote + '/messages.jar')
        run('docker', 'exec', options.worker, 'chown', '-R', '10001:10001', remote); run('docker', 'exec', options.worker, 'chmod', '0700', remote, remote + '/secrets'); run('docker', 'exec', options.worker, 'chmod', '0600', remote + '/secrets/qa.rabbit')
        fixture = ['docker', 'exec', '-u', '10001', '-e', 'DATAHUB_ETL_SECRET_DIR=' + remote + '/secrets', '-e', 'DATAHUB_ETL_SECRET_REFS=qa.rabbit', options.worker, '/opt/java17/bin/java', '-cp', remote + '/messages.jar:/opt/spark/jars/*', 'cn.iocoder.yudao.framework.etl.messages.BoundedMessageRabbitFixture']
        seeded = run(*fixture, 'seed')
        existing = client.request('GET', '/environment/query-environment-list'); base = next(item for item in existing if str(item['code']) == options.environment)
        env_name = 'QDATA_QA_RABBIT_' + stamp; config = base['config'] + '\nexport DATAHUB_ETL_SECRET_DIR=' + remote + '/secrets\nexport DATAHUB_ETL_SECRET_REFS=qa.rabbit\n'
        client.request('POST', '/environment/create', {'name': env_name, 'description': '临时AMQP受管引用验证，无明文秘密', 'workerGroups': json.dumps([connection['workerGroup']]), 'config': config})
        env_code = next(item['code'] for item in client.request('GET', '/environment/query-environment-list') if item['name'] == env_name)
        create, logs, bounded = native_operations(client, connection, {'SPARK': env_code}, report)
        params = {'resourceList': [], 'localParams': [], 'programType': 'SQL', 'rawScript': sqlfile.read_text(), 'master': 'local[1]', 'deployMode': 'local', 'driverCores': 1, 'driverMemory': '512m',
                  'numExecutors': 1, 'executorCores': 1, 'executorMemory': '512m', 'yarnQueue': '', 'others': '--jars ' + remote + '/messages.jar', 'sqlExecutionType': 'SCRIPT', 'sparkVersion': 'SPARK2', 'appName': 'QDATA_SPARK_RABBIT_BOUNDED_V2'}
        bounded('SPARK', params, 'SPARK', case_name='rabbit-v2'); verified = run(*fixture, 'verify')
        report.update({'status': 'PASS', 'seedConfirmed': 'rabbit-seed-confirmed=2' in seeded, 'exactResult': verified.splitlines()[-1], 'jarSha256': binding['jarSha256'], 'sqlSha256': hashlib.sha256(sqlfile.read_bytes()).hexdigest(),
                       'sourceMessagesRetainedAfterBrowse': 2, 'outputMessagesRetainedAfterBrowse': 2, 'limits': ['Initial-cardinality exclusive browse, not a point-in-time snapshot under concurrent producers.', 'No input acknowledgement; source messages requeue on close and repeated runs reread them.', 'Confirmed output is at least once; isolated plaintext test transport, TLS not accepted by this probe.']})
    finally:
        for check in report['checks'].values():
            code = check.get('workflowCode')
            if code:
                try: client.request('POST', prefix + '/workflow-definition/' + code + '/release', {'releaseState': 'OFFLINE'}); report['cleanup'].append({'workflowCode': code, 'state': 'OFFLINE'})
                except Exception: report['cleanup'].append({'workflowCode': code, 'state': 'CLEANUP_FAILED'})
        if env_code is not None:
            try: client.request('POST', '/environment/delete', {'code': env_code}); report['cleanup'].append({'environmentCode': str(env_code), 'state': 'DELETED'})
            except Exception: report['cleanup'].append({'environmentCode': str(env_code), 'state': 'DELETE_FAILED'})
        if copied: run('docker', 'exec', options.worker, 'rm', '-rf', remote, check=False)
        if container: run('docker', 'rm', '-fv', broker, check=False)
        if attached: run('docker', 'network', 'disconnect', network, options.worker, check=False)
        if created: run('docker', 'network', 'rm', network, check=False)
        secret.unlink(missing_ok=True); environment.unlink(missing_ok=True)
        report['cleanup'].append({'fixtureContainerRemoved': container, 'workerNetworkDetached': attached, 'isolatedNetworkRemoved': created, 'workerSecretRemoved': copied, 'hostFixtureSecretsRemoved': True})
        if report['status'] != 'PASS': report['status'] = 'FAIL'
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'status': report['status'], 'report': str(options.output)}, ensure_ascii=False), flush=True)


if __name__ == '__main__': main()
