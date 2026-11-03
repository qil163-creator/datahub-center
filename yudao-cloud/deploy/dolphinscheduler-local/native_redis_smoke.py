#!/usr/bin/env python3
"""真实 Redis/Spark 验证 string/hash/list 类型、Key模板、批次与秘密拒绝。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-redis-sql'


def run(arguments, data=None, check=True, timeout=60):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=timeout)
    if check and result.returncode: raise RuntimeError(result.stderr.decode(errors='replace')[-5000:] + result.stdout.decode(errors='replace')[-5000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--redis', default='datahub-ds-local-redis-native-1')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-redis-binding.json'))
    parser.add_argument('--fixture', type=Path, default=Path('/tmp/datahub-redis-fixture.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/native-redis-components-20261102.json')
    options = parser.parse_args()
    fixture = json.loads(options.fixture.read_text()); binding = json.loads(options.binding.read_text())
    deployed = run(['docker', 'exec', options.worker, 'python3', '-c', "import hashlib;print(hashlib.sha256(open('" + binding['workerJarPath'] + "','rb').read()).hexdigest())"]).stdout.decode().strip()
    assert deployed == binding['jarSha256'], '工作节点Redis JAR与验收manifest不一致'
    # 只读取本脚本生成的临时合成测试密码，不输出或写入验收结果。
    acl = Path(__file__).resolve().parent / 'runtime/redis-fixture/users.acl'
    password = re.search(r'>\b([0-9a-f]{64})\b', acl.read_text()).group(1)

    def redis(*arguments):
        result = run(['docker', 'exec', '-i', options.redis, 'sh', '-c', 'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli --user datahub --json "$@"', 'sh', *arguments], data=(password + '\n').encode())
        text = result.stdout.decode(); value = json.loads(text)
        if isinstance(value, dict) and value.get('error'): raise RuntimeError('Redis 合成夹具命令失败')
        return value

    rows = [{'id': '9007199254740993', 'amount': '12345678901234567890.1234', 'day': '2026-11-02', 'label': '河道,a "B"\nline', 'empty': '', 'optional': None},
            {'id': '9007199254740994', 'amount': '2.0001', 'day': '2026-11-03', 'label': '水情' * 2200, 'empty': '', 'optional': 'present'}]
    for model in ['string', 'hash', 'list']:
        redis('DEL', 'input:' + model + ':one', 'input:' + model + ':two')
        for index, row in enumerate(rows):
            key = 'input:' + model + (':one' if model == 'list' or index == 0 else ':two')
            payload = json.dumps(row, ensure_ascii=False, separators=(',', ':'))
            if model == 'string': redis('SET', key, payload)
            elif model == 'list': redis('RPUSH', key, payload)
            else: redis('HSET', key, *[part for field, value in row.items() if value is not None for part in [field, value]])
        redis('DEL', 'result:' + model, *['result:' + model + ':' + row['id'] for row in rows])
    redis('SET', 'result:keep', 'UNCHANGED')
    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Spark3.5.6 native SQL', 'redis': 'Redis8.2.3',
              'connector': binding['connector'], 'jarSha256': binding['jarSha256'], 'cases': [], 'credentialPlaintextRecorded': False}

    def execute(name, sql, authorized=True):
        program = binding['sparkInitializationSql'] + '\n' + sql
        path = Path('/tmp/datahub-redis-' + uuid.uuid4().hex + '.sql'); path.write_text(program); remote = '/tmp/' + path.name
        run(['docker', 'cp', str(path), options.worker + ':' + remote])
        stage = '/tmp/' + path.stem
        run(['docker', 'exec', options.worker, 'python3', '-c', "from pathlib import Path;import os;p=Path('" + stage + "');p.mkdir();os.chown(p,10001,10001)"])
        result = run(['docker', 'exec', '-w', stage, '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_SECRET_DIR=' + fixture['workerSecretDirectory'],
                      '-e', 'DATAHUB_ETL_SECRET_REFS=' + (','.join(fixture['secretReferences']) if authorized else ''), options.worker,
                      '/opt/spark/bin/spark-sql-managed-original', '--master', 'local[1]', '--driver-memory', '512m',
                      '--conf', 'spark.sql.parser.escapedStringLiterals=false', '-f', remote], check=False)
        report['cases'].append({'name': name, 'exitCode': result.returncode, 'sqlSha256': hashlib.sha256(program.encode()).hexdigest()})
        return result

    positive = execute('three-models-read-write', '\n'.join((SQL / (model + '.sql')).read_text() for model in ['string', 'hash', 'list']))
    if positive.returncode: raise RuntimeError((positive.stderr + positive.stdout).decode(errors='replace')[-7000:])
    for model in ['string', 'hash', 'list']:
        actual = []
        if model == 'list': actual = [json.loads(value) for value in redis('LRANGE', 'result:list', '0', '-1')]
        else:
            for row in rows:
                key = 'result:' + model + ':' + row['id']
                if model == 'string': actual.append(json.loads(redis('GET', key)))
                else:
                    pairs = redis('HGETALL', key)
                    record = pairs if isinstance(pairs, dict) else dict(zip(pairs[0::2], pairs[1::2]))
                    record.setdefault('optional', None); actual.append(record)
        assert sorted(actual, key=lambda row: row['id']) == rows, model
        report['cases'].append({'name': model + '-exact-row-verification', 'rowCount': len(actual), 'exactLargeIntegerDecimalDateUnicodeEmptyNull': True,
                                'longUnicodeCodePoints': len(rows[1]['label']), 'passed': True})
    assert redis('GET', 'result:keep') == 'UNCHANGED'
    report['existingUnrelatedKeyUnchanged'] = True
    sql = (SQL / 'hash.sql').read_text()
    denied = execute('ungranted-secret-reference', sql, authorized=False)
    assert denied.returncode != 0
    redis('HSET', 'input:hash:one', 'id', '1.25')
    wrong_type = execute('fractional-integer-rejected', sql)
    assert wrong_type.returncode != 0
    redis('HSET', 'input:hash:one', 'id', rows[0]['id'])
    match = re.search(r"DH_READ_REDIS\('([A-Za-z0-9+/=]+)'\)", sql)
    plan = json.loads(base64.b64decode(match.group(1))); plan['maxRows'] = 1
    altered = sql.replace(match.group(1), base64.b64encode(json.dumps(plan, ensure_ascii=False).encode()).decode())
    exhausted = execute('row-budget-rejected', altered)
    assert exhausted.returncode != 0
    report['limits'] = ['原生Spark与真实Redis端到端，不声明管理端触发验收',
        'SCAN批处理不是一致性快照；string为SET替换、hash为显式字段HSET/HDEL、list为RPUSH追加',
        '批次逐条确认Redis响应，失败可能已写入部分命令；不声明全局回滚或恰好一次，list重试可能重复',
        '带ACL的独立测试环境，TLS编译和端点绑定已实现但本次使用隔离网络非TLS夹具']
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__': main()
