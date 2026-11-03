#!/usr/bin/env python3
"""独立 mTLS 身份下验收正式 Flink RPC launcher、窗口及实际保存点恢复。"""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl'


def run(arguments, timeout=60, check=True, env=None):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout, env=env)
    if check and result.returncode:
        raise RuntimeError(result.stdout[-3500:] + result.stderr[-3500:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='components-stable-ds')
    parser.add_argument('--worker-binding', type=Path, default=Path('/tmp/datahub-native-script-binding.json'))
    parser.add_argument('--launcher-binding', type=Path, default=Path('/tmp/datahub-native-script-flink-binding.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-native-script-flink.json')
    parser.add_argument('--only', default='')
    parser.add_argument('--token-jdbc-config', type=Path)
    options = parser.parse_args()
    directory = Path(tempfile.mkdtemp(prefix='datahub-script-flink-', dir='/private/tmp')); directory.chmod(0o700)
    (directory / 'secrets').mkdir(mode=0o700); (directory / 'ledger').mkdir(mode=0o700)
    worker = json.loads(options.worker_binding.read_text()); launcher = json.loads(options.launcher_binding.read_text())
    remote = '/tmp/datahub-script-flink-' + uuid.uuid4().hex
    private = {'workerClasspath': [item['path'] for item in worker['workerClasspath']], 'docker': shutil.which('docker'),
               'dockerEndpoint': json.loads(run(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']).stdout),
               'image': worker['workerImage'], 'port': 18448, 'remote': remote}
    if options.token_jdbc_config:
        if set(options.only.split(',')) - {'token', 'token-unauthorized'}:
            raise ValueError('Token JDBC夹具必须单独运行')
        private['tokenJdbc'] = json.loads(options.token_jdbc_config.read_text())
        private['tokenSentinel'] = uuid.uuid4().hex + uuid.uuid4().hex
    secrets = directory / 'secrets'
    config = directory / 'ca.conf'; config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=ca\n[dn]\n[ca]\nbasicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n')
    run(['openssl', 'req', '-config', str(config), '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '3', '-subj', '/CN=DataHub independent Flink fixture CA', '-keyout', str(directory / 'ca.key'), '-out', str(secrets / 'fixture.ca')])
    for name in ['server', 'client', 'other']:
        raw = directory / (name + '.raw'); key = secrets / ('fixture.' + name + '.key'); cert = secrets / ('fixture.' + name + '.cert')
        run(['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=datahub-flink-' + name, '-keyout', str(raw), '-out', str(directory / (name + '.csr'))])
        run(['openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', str(raw), '-out', str(key)]); raw.unlink()
        extension = directory / (name + '.ext'); extension.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=' + ('serverAuth' if name == 'server' else 'clientAuth') + '\n' + ('subjectAltName=DNS:host.docker.internal,DNS:localhost,IP:127.0.0.1\n' if name == 'server' else ''))
        run(['openssl', 'x509', '-req', '-in', str(directory / (name + '.csr')), '-CA', str(secrets / 'fixture.ca'), '-CAkey', str(directory / 'ca.key'), '-CAcreateserial', '-days', '3', '-extfile', str(extension), '-out', str(cert)])
        if name == 'client':
            private['clientFingerprint'] = hashlib.sha256(subprocess.check_output(['openssl', 'x509', '-in', str(cert), '-outform', 'DER'])).hexdigest()
    (directory / 'private.json').write_text(json.dumps(private))
    for path in directory.rglob('*'):
        if path.is_file():
            path.chmod(0o600)
    classes = directory / 'trusted'; classes.mkdir()
    with Path('/tmp/datahub-components-maven.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for source in [MODULE / 'target/classes', ROOT / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-common/target/classes']:
            shutil.copytree(source, classes, dirs_exist_ok=True)
    cp = str(classes) + ':' + Path('/tmp/datahub-etl-diagnostic-classpath.txt').read_text().strip()
    broker_class = 'TokenStartupFixtureBroker' if options.token_jdbc_config else 'ScriptFixtureBroker'
    source = MODULE / ('runtime/script-flink-smoke/' + broker_class + '.java')
    run(['javac', '-cp', cp, '-d', str(classes), str(source)])
    environment = dict(os.environ, DATAHUB_ETL_SECRET_DIR=str(secrets), DATAHUB_ETL_SECRET_REFS=','.join(path.name for path in secrets.iterdir()))
    broker_log = (directory / 'broker.log').open('w')
    broker = subprocess.Popen(['java', '-Xmx192m', '-cp', cp, 'cn.iocoder.yudao.framework.etl.script.fixture.' + broker_class, str(directory)], env=environment, stdout=broker_log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 30
        while not (directory / 'ready').exists():
            if broker.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('独立测试 broker 未启动：' + (directory / 'broker.log').read_text()[-3500:])
            time.sleep(0.1)
        run(['docker', 'exec', options.worker, 'mkdir', '-p', remote + '/classes', remote + '/secrets'])
        run(['docker', 'exec', options.worker, 'chmod', '0700', remote, remote + '/secrets'])
        for name in ['fixture.client.key', 'fixture.client.cert', 'fixture.other.key', 'fixture.other.cert', 'fixture.ca']:
            run(['docker', 'cp', str(secrets / name), options.worker + ':' + remote + '/secrets/' + name])
        for path in directory.glob('*.args'):
            run(['docker', 'cp', str(path), options.worker + ':' + remote + '/' + path.name])
        run(['docker', 'cp', launcher['flinkLauncher']['path'], options.worker + ':' + remote + '/launcher.jar'])
        api = directory / 'api'; api.mkdir()
        paths = json.loads(run(['docker', 'exec', options.worker, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')))"]).stdout.splitlines()[-1])
        for path in paths:
            run(['docker', 'cp', options.worker + ':' + path, str(api / Path(path).name)])
        run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', launcher['flinkLauncher']['path'] + ':' + str(api / '*'), '-d', str(directory / 'probe'), str(MODULE / 'runtime/script-flink-smoke/ScriptStreamProbe.java')])
        run(['docker', 'cp', str(directory / 'probe') + '/.', options.worker + ':' + remote + '/classes'])
        run(['docker', 'exec', options.worker, 'chown', '-R', '10001:10001', remote])
        prefix = ['docker', 'exec', '-u', '10001', '-e', 'DATAHUB_ETL_SECRET_DIR=' + remote + '/secrets', '-e', 'DATAHUB_ETL_SECRET_REFS=fixture.client.key,fixture.client.cert,fixture.other.key,fixture.other.cert,fixture.ca', options.worker,
                  'timeout', '--signal=TERM', '--kill-after=8s', '90s', '/opt/java17/bin/java', '-Xmx384m', '-XX:ActiveProcessorCount=2', '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED', '-cp', '/opt/flink/lib/*:' + remote + '/launcher.jar:' + remote + '/classes']
        cases = []
        names = ['token', 'token-unauthorized'] if options.token_jdbc_config else ['bounded', 'overflow', 'badtype', 'unauthorized', 'count', 'timer', 'resume', 'replay', 'cache', 'cancel']
        for name in names:
            if options.only and name not in options.only.split(','):
                continue
            phases = ['save-timer', 'restore-timer'] if name == 'resume' else ['save-session', 'restore-session', 'restore-rescaled', 'restore-scope'] if name == 'replay' else ['run']
            for phase in phases:
                negative = name in ['overflow', 'badtype', 'unauthorized', 'token-unauthorized'] or phase in ['restore-rescaled', 'restore-scope']
                if name in ['bounded', 'overflow', 'badtype', 'unauthorized', 'token', 'token-unauthorized']:
                    invocation = prefix + [launcher['flinkMainClass'], *(directory / (name + '.args')).read_text().splitlines()]
                else:
                    invocation = prefix + ['cn.iocoder.yudao.framework.etl.script.fixture.ScriptStreamProbe', remote, name, phase]
                started = time.monotonic()
                result = run(invocation, timeout=100, check=False)
                (directory / (name + '-' + phase + '.log')).write_text(result.stdout + result.stderr)
                if result.returncode in (124, 137) or (result.returncode == 0) == negative:
                    raise RuntimeError('实际 Flink 场景结果不符：' + name + '/' + phase + '\n' + (result.stdout + result.stderr)[-5000:])
                if negative and not any(reason in result.stdout + result.stderr for reason in ['完整分区超过脚本最大行数', '脚本 broker 不可用', '脚本检查点不属于当前阶段、分区或批次合同', '脚本恢复缺少明确的分区状态']):
                    raise RuntimeError('负例没有实际运行拒绝根因：' + name)
                cases.append({'case': name, 'phase': phase, 'exitCode': result.returncode, 'expectedFailure': negative, 'elapsedSeconds': round(time.monotonic() - started, 3)})
                print(json.dumps(cases[-1]), flush=True)
        run(['docker', 'cp', options.worker + ':' + remote + '/.', str(directory / 'worker-results')])
        outputs = {}
        for path in (directory / 'worker-results').glob('*.rows'):
            outputs[path.stem] = path.read_text().splitlines()
        for path in (directory / 'worker-results').glob('*-sql-output'):
            rows = []
            for part in path.glob('*'):
                if part.is_file() and not part.name.startswith('.'):
                    rows.extend(json.loads(line, parse_float=str) for line in part.read_text().splitlines() if line)
            outputs[path.name] = rows
        selected = {item['case'] for item in cases}
        if options.token_jdbc_config:
            sentinel = private['tokenSentinel'].encode()
            for artifact in directory.rglob('*'):
                if artifact.is_file() and artifact.name not in {'private.json'} and artifact.suffix in {'.log', '.args', '.jobgraph'}:
                    data = artifact.read_bytes()
                    decoded = base64.urlsafe_b64decode(b''.join(data.splitlines())) if artifact.suffix == '.args' else data
                    if sentinel in data or sentinel in decoded:
                        raise RuntimeError('Token夹具原始令牌进入日志或引擎参数')
        if 'bounded' in selected:
            actual = sorted(outputs.get('bounded-sql-output', []), key=lambda row: row['id'])
            expected = [{'id': 9007199254740993, 'label': "batch=2|河道 O'Reilly😀", 'amount': '12345678901234567890123456.78', 'day': '2026-11-02', 'moment': '2026-11-02T12:34:56.123456789'},
                        {'id': 9007199254740994, 'label': 'batch=2|null', 'amount': None, 'day': None, 'moment': None}]
            if actual != expected:
                raise RuntimeError('正式 SQL launcher 精确值不符：' + repr(actual))
        for name, count, batch in [('count', 2, 2), ('timer', 1, 1), ('resume', 2, 1), ('replay', 4, 2), ('cache', 3, 2)]:
            if name not in selected:
                continue
            expected_indices = [0, 0, 1] if name == 'cache' else list(range(count))
            expected = [str(9007199254740993 + index) + '|' + base64.b64encode(('batch=' + str(batch) + "|河道 O'Reilly😀-" + str(index)).encode()).decode()
                        + '|12345678901234567890123456.7800|2026-11-02|2026-11-02T12:34:56.123456789' for index in expected_indices]
            if outputs.get(name) != expected:
                raise RuntimeError('真实窗口/恢复精确结果不符：' + name + ' ' + repr(outputs.get(name)))
        secret_text = (secrets / 'fixture.client.key').read_text()
        key_body = ''.join(line for line in secret_text.splitlines() if not line.startswith('---'))
        sentinels = [secret_text.encode(encoding) for encoding in ['utf-8', 'utf-16le', 'utf-16be']]
        sentinels += [key_body.encode(encoding) for encoding in ['utf-8', 'utf-16le', 'utf-16be']]
        sentinels += [line.encode(encoding) for line in secret_text.splitlines() if len(line) >= 40 and not line.startswith('---') for encoding in ['utf-8', 'utf-16le', 'utf-16be']]
        sentinels.append(base64.b64decode(key_body))
        scans = []
        retained = options.output.parent / 'native-script-flink-artifacts'
        retained.mkdir(parents=True, exist_ok=True)
        for path in (directory / 'worker-results').rglob('*'):
            if path.is_file() and (path.suffix in ('.jobgraph', '.args') or 'savepoints' in path.parts):
                data = path.read_bytes()
                decoded = base64.urlsafe_b64decode(b''.join(data.splitlines())) if path.suffix == '.args' else data
                if any(value in data or value in decoded for value in sentinels) or b'public class Batch' in decoded:
                    raise RuntimeError('真实序列化产物包含源码或私钥：' + path.name)
                checksum = hashlib.sha256(data).hexdigest()
                artifact = retained / (checksum[:24] + ('.args' if path.suffix == '.args' else '.bin'))
                if artifact.exists() and artifact.read_bytes() != data:
                    raise RuntimeError('序列化证据摘要冲突')
                artifact.write_bytes(data)
                scans.append({'artifact': str(path.relative_to(directory / 'worker-results')), 'bytes': len(data), 'decodedProgramBytes': len(decoded) if path.suffix == '.args' else None,
                              'sha256': checksum, 'retainedArtifact': str(artifact.relative_to(options.output.parent)), 'privateMaterialMatches': 0})
        status = json.loads((directory / 'status.json').read_text())
        if status['activeWorkers'] != 0:
            raise RuntimeError('实际停止后隔离 worker 未释放')
        by_case = {item['case']: item for item in status['sessions']}
        if 'token' in selected and (len(status['sessions']) != 1 or by_case.get('token', {}).get('sequence') != 1 or not by_case['token'].get('hasOpaqueContext') or not by_case['token'].get('closed')):
            raise RuntimeError('Token初始化未执行一次或未取得关闭的opaque上下文')
        for name, executions in [('bounded', 1), ('count', 1), ('timer', 1), ('resume', 2), ('replay', 2), ('cache', 1)]:
            if name in selected and (name not in by_case or by_case[name]['sequence'] != executions):
                raise RuntimeError('恢复后用户批次实际执行次数不符：' + name)
        if 'cache' in selected and by_case['cache']['acknowledged'] != 1:
            raise RuntimeError('恢复后的已完成检查点未确认缓存')
        if 'cancel' in selected and (not by_case['cancel']['closed'] or outputs.get('cancel')):
            raise RuntimeError('运行中取消未关闭会话，或取消后仍产生输出')
        evidence = {'verifiedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Official Apache Flink1.20.3 / Java17', 'launcherSha256': launcher['flinkLauncher']['sha256'],
                    'executionBoundary': 'Independent fixture mTLS identity, trusted host broker18448, immutable Java25 Docker network=none worker; actual production Flink operator.',
                    'cases': cases, 'outputs': outputs, 'fixtureDirectory': str(directory), 'actualSerializationScans': scans, 'activeWorkersAfterStop': status['activeWorkers'], 'workerCleanupObservedBeforeBrokerClose': True,
                    'brokerSessions': status['sessions'],
                    'boundedUserInterface': 'qData EtlBase / JSONObject data.data; no JSON string conversion', 'rawTokenApplicable': bool(options.token_jdbc_config), 'rawTokenInLogsOrProgram': False if options.token_jdbc_config else None,
                    'limits': ['Technical component acceptance with controlled SQL VALUES/DataStream test sources; not a management API task or physical database continuous source.',
                               'Flink custom-token is offline startup-only; token does not implicitly become JDBC or MQ credentials. Realtime refresh is not implemented.', 'External sinks remain at least once; checkpoint response caching does not make arbitrary user side effects transactional.']}
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'evidence': str(options.output), 'directory': str(directory), 'cases': len(cases)}), flush=True)
    finally:
        (directory / 'stop').touch()
        try:
            broker.wait(timeout=15)
        except subprocess.TimeoutExpired:
            broker.terminate(); broker.wait(timeout=10)
        broker_log.close()
        if options.token_jdbc_config:
            run(['docker', 'exec', options.worker, 'rm', '-rf', remote], check=False)


if __name__ == '__main__':
    main()
