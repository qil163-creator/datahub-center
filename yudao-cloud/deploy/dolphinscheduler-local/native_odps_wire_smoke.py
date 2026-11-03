#!/usr/bin/env python3
"""真实Spark调用官方ODPS SDK Tunnel wire夹具，云端验收始终为OPEN。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-odps-sql'


def run(arguments, check=True, timeout=60, data=None):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=timeout)
    if check and result.returncode: raise RuntimeError((result.stderr + result.stdout).decode(errors='replace')[-6000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-odps-binding.json'))
    parser.add_argument('--fixture', type=Path, default=Path('/tmp/datahub-odps-wire-fixture.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/native-odps-wire-20261102.json')
    options = parser.parse_args(); binding = json.loads(options.binding.read_text()); fixture = json.loads(options.fixture.read_text())
    assert fixture['runtimeJarSha256'] == binding['jarSha256'], 'wire夹具与当前运行JAR不一致，请重新构建夹具'
    deployed = run(['docker', 'exec', options.worker, 'python3', '-c', "import hashlib;print(hashlib.sha256(open('" + binding['workerJarPath'] + "','rb').read()).hexdigest())"]).stdout.decode().strip()
    assert deployed == binding['jarSha256'], '工作节点ODPS JAR与验收manifest不一致'
    worker_generation = run(['docker', 'inspect', '--format', '{{.Id}}|{{.State.StartedAt}}', options.worker]).stdout.decode().strip()
    sql_client = run(['docker', 'exec', options.worker, 'python3', '-c',
        "from pathlib import Path;print('/opt/spark/bin/spark-sql-managed-original' if Path('/opt/spark/bin/spark-sql-managed-original').is_file() else '/opt/spark/bin/spark-sql')"]).stdout.decode().strip()
    private_value = run(['docker', 'exec', options.worker, 'python3', '-c',
        "import json;print(json.load(open('" + fixture['workerSecretDirectory'] + "/odpsAccess'))['accessSecret'])"]).stdout.strip()
    private_encodings = (private_value, base64.b64encode(private_value), quote(private_value.decode(), safe='').encode())

    def http(path):
        result = run(['docker', 'exec', options.worker, 'python3', '-c', "import urllib.request;print(urllib.request.urlopen('http://odps-wire:18088" + path + "',timeout=5).read().decode())"])
        return json.loads(result.stdout)

    def records(report):
        rows = report['targetRows']
        for row in rows: row.setdefault('optional', None)
        return sorted(rows, key=lambda row: int(row['id']))

    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Spark3.5.6 native SQL', 'connector': binding['connector'],
              'runtimeJarSha256': binding['jarSha256'], 'fixtureJarSha256': fixture['fixtureJarSha256'],
              'fixtureKind': 'OFFICIAL_SDK_TUNNEL_WIRE_ONLY', 'cloudAcceptance': 'OPEN', 'cases': [], 'credentialPlaintextRecorded': False,
              'submission': 'NATIVE_SPARK_SQL_CLIENT_NOT_DS_INSTANCE', 'workerContainer': options.worker,
              'workerImageId': run(['docker', 'inspect', '--format', '{{.Image}}', options.worker]).stdout.decode().strip(), 'nativeSqlClient': sql_client}

    def execute(name, text, authorized=True):
        assert run(['docker', 'inspect', '--format', '{{.Id}}|{{.State.StartedAt}}', options.worker]).stdout.decode().strip() == worker_generation, '工作节点已经重启，请从干净夹具重做整轮验收'
        sql = binding['sparkInitializationSql'] + '\n' + text
        path = Path('/tmp/datahub-odps-' + uuid.uuid4().hex + '.sql'); path.write_text(sql); stage = '/tmp/' + path.stem
        run(['docker', 'exec', '-i', options.worker, 'python3', '-c', "from pathlib import Path;import sys;p=Path('" + str(path) + "');p.write_bytes(sys.stdin.buffer.read());p.chmod(0o644)"], data=sql.encode())
        run(['docker', 'exec', options.worker, 'python3', '-c', "from pathlib import Path;import os;p=Path('" + stage + "');p.mkdir();os.chown(p,10001,10001)"])
        try:
            result = run(['docker', 'exec', '-w', stage, '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_SECRET_DIR=' + fixture['workerSecretDirectory'],
                          '-e', 'DATAHUB_ETL_SECRET_REFS=' + (','.join(fixture['secretReferences']) if authorized else ''), options.worker,
                          sql_client, '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.sql.parser.escapedStringLiterals=false', '-f', str(path)], check=False)
        except subprocess.TimeoutExpired:
            # docker exec 客户端退出不会结束容器内 JVM；只终止本例精确 SQL 路径的 Spark 进程。
            cleanup = "import os,signal;from pathlib import Path\nfor p in Path('/proc').glob('[0-9]*/cmdline'):\n try:\n  a=p.read_bytes().split(b'\\0')\n  if b'org.apache.spark.deploy.SparkSubmit' in a and " + repr(str(path).encode()) + " in a: os.kill(int(p.parent.name),signal.SIGTERM)\n except (FileNotFoundError,ProcessLookupError,PermissionError): pass\n"
            run(['docker', 'exec', options.worker, 'python3', '-c', cleanup], check=False)
            raise
        report['cases'].append({'name': name, 'exitCode': result.returncode, 'sqlSha256': hashlib.sha256(sql.encode()).hexdigest()})
        assert run(['docker', 'inspect', '--format', '{{.Id}}|{{.State.StartedAt}}', options.worker]).stdout.decode().strip() == worker_generation, '工作节点在本例执行中重启，结果不能计入验收'
        if b'private-fixture-' in result.stderr + result.stdout: raise RuntimeError('SDK私有错误响应泄漏到原生任务日志')
        if any(value in result.stderr + result.stdout + sql.encode() for value in private_encodings): raise RuntimeError('原生计划或任务日志含执行端秘密')
        report['cases'][-1]['secretEncodingMatches'] = 0
        return result

    copy = (SQL / 'copy.sql').read_text(); read = (SQL / 'read-target.sql').read_text()
    http('/__control?mode=normal'); positive = execute('official-protobuf-copy-and-final-commit', copy)
    if positive.returncode: raise RuntimeError((positive.stderr + positive.stdout).decode(errors='replace')[-7000:])
    visible = http('/__report'); actual = records(visible)
    assert [row['id'] for row in actual] == ['7', '9007199254740993', '9007199254740994'], [row['id'] for row in actual]
    assert actual[0]['label'] == 'EXISTING'
    assert actual[1] == {'id': '9007199254740993', 'amount': '12345678901234567890.1234', 'day': '2026-11-02', 'label': '河道,a "B"\nline', 'empty': '', 'optional': None, 'reading': '1.100000023841858'}
    assert actual[2] == {'id': '9007199254740994', 'amount': '2.0001', 'day': '2026-11-03', 'label': '水情' * 2200, 'empty': '', 'optional': 'present', 'reading': '1.401298464324817E-45'}
    assert visible['signedRequests'] == visible['requests'] and visible['commitRequests'] == 1 and visible['uploadedBlocks'] >= 1
    read_back = execute('tunnel-read-target', read)
    if read_back.returncode: raise RuntimeError((read_back.stderr + read_back.stdout).decode(errors='replace')[-5000:])
    report['nativeFloatBinaryValuePreserved'] = True; report['exactLongDecimalDateUnicodeEmptyNull'] = True; report['existingRowPreserved'] = True; report['longUnicodeCodePoints'] = 4400
    for mode, name in [('403', 'sdk-http403-rejected'), ('schema', 'native-schema-mismatch-rejected'), ('crc', 'official-protobuf-crc-rejected'), ('commit500', 'final-commit-not-confirmed')]:
        http('/__control?mode=' + mode); before = http('/__report'); result = execute(name, copy)
        assert result.returncode != 0
        after = http('/__report'); assert records(after) == actual
        if mode == 'schema': assert after['downloadDataRequests'] == before['downloadDataRequests']
        if mode == 'commit500': assert after['commitRequests'] == before['commitRequests'] + 1
        report['cases'][-1]['targetUnchanged'] = True
    http('/__control?mode=normal'); denied = execute('ungranted-runtime-reference-rejected', copy, authorized=False); assert denied.returncode != 0
    report['signedSdkRequests'] = http('/__report')['signedRequests']
    report['limits'] = ['本地Docker内HTTP与官方SDK protobuf/CRC/Tunnel session协议验收，不是官方MaxCompute服务端，也不证明真实云端IAM或计费路径',
        '云端真实TableTunnel读写、IAM授权、地域TLS端点与故障后提交状态核验均OPEN',
        '精确项目/表/完整分区绑定，Driver唯一UploadSession；分片只暂存block，全部成功后最终commit',
        '最终提交应答丢失可能产生不确定状态，不声明跨作业恰好一次或自动回滚；当前不执行附加ODPS SQL、持久增量游标或三层Schema表']
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__': main()
