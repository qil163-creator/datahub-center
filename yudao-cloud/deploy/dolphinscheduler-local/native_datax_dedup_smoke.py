#!/usr/bin/env python3
"""隔离DS原生DataX插件执行新DBSwitch去重查询；专用PG和网络在结束后清理。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

from smoke import Client
from engines_smoke import native_operations


def run(*arguments, data=None, check=True):
    result = subprocess.run(arguments, input=data, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr[-2500:] + result.stdout[-2500:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--connection', type=Path, required=True)
    parser.add_argument('--worker', default='components-stable-ds')
    parser.add_argument('--environment', default='188629399923200')
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    fixture = root / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-core/target/dedup-datax-fixture'
    columns = fixture.joinpath('fields.txt').read_text().split(',')
    if columns != ['id', 'name', 'amount']:
        raise RuntimeError('请先由DeduplicatingSelectSqlTest生成真实编译查询')
    connection = json.loads(options.connection.read_text())
    if connection['server'] != 'http://127.0.0.1:12347/dolphinscheduler' or connection.get('container') != options.worker:
        raise RuntimeError('只允许隔离组件DS执行，不访问原IDE关联的DS')
    client = Client(connection['server']); client.token = connection['token']
    stamp = uuid.uuid4().hex[:12]; network = 'qdata-dedup-' + stamp; postgres = 'qdata-dedup-pg-' + stamp
    report = {'status': 'RUNNING', 'taskPlugin': 'DATAX', 'scope': 'Actual PostgreSQL reader -> new DB Switch dedup SELECT -> PostgreSQL writer; no platform/business database', 'checks': {}, 'cleanup': []}
    prefix = '/projects/' + connection['projectCode']
    attached = False; created = False; container = False
    try:
        run('docker', 'network', 'create', '--internal', network); created = True
        run('docker', 'network', 'connect', network, options.worker); attached = True
        run('docker', 'run', '-d', '--name', postgres, '--network', network, '--network-alias', 'dedup-postgres', '--memory', '128m',
            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=dedup_qa', 'postgres:18-alpine',
            '-c', 'shared_buffers=16MB', '-c', 'max_connections=20', '-c', 'work_mem=1MB'); container = True
        for attempt in range(100):
            if subprocess.run(['docker', 'exec', postgres, 'psql', '-X', '-q', '-A', '-t', '-U', 'postgres', '-d', 'dedup_qa', '-c', 'SELECT 1'], capture_output=True).returncode == 0:
                break
            time.sleep(.2)
        else:
            raise RuntimeError('专用PG未就绪')
        def sql(query):
            return run('docker', 'exec', '-i', postgres, 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'dedup_qa', data=query)
        sql("CREATE ROLE qa_dedup LOGIN; CREATE SCHEMA qa_dedup AUTHORIZATION qa_dedup; SET ROLE qa_dedup; "
            "CREATE TABLE qa_dedup.source(id bigint,name text,amount numeric(38,8)); "
            "INSERT INTO qa_dedup.source VALUES (9007199254740993,'A',12345678901234567890.12345678),(9007199254740993,'A',12345678901234567890.12345678),(9007199254740994,'a',2),(9007199254740995,NULL,3),(9007199254740996,NULL,4); "
            "CREATE TABLE qa_dedup.target_all(LIKE qa_dedup.source); CREATE TABLE qa_dedup.target_keys(LIKE qa_dedup.source); CREATE TABLE qa_dedup.target_duplicate(LIKE qa_dedup.source);")
        create, logs, bounded = native_operations(client, connection, {'DATAX': int(options.environment)}, report)
        url = 'jdbc:postgresql://dedup-postgres:5432/dedup_qa?connectTimeout=10&socketTimeout=30'
        # 此独立网络夹具使用trust认证；占位符仅满足旧DataX非空校验，不是可用于任何数据库的凭据。
        base = {'username': 'qa_dedup', 'password': 'unused-fixture-trust-placeholder'}
        for name in ['all', 'keys', 'duplicate']:
            if name == 'duplicate':
                sql("INSERT INTO qa_dedup.source VALUES (9007199254740993,'B',5);")
            query = fixture.joinpath(('keys' if name == 'duplicate' else name) + '.sql').read_text()
            content = {'reader': {'name': 'postgresqlreader', 'parameter': {**base, 'connection': [{'jdbcUrl': [url], 'querySql': [query]}]}},
                       'writer': {'name': 'postgresqlwriter', 'parameter': {**base, 'column': columns, 'preSql': ['TRUNCATE TABLE qa_dedup.target_' + name],
                                  'connection': [{'jdbcUrl': url, 'table': ['qa_dedup.target_' + name]}]}}}
            job = {'job': {'setting': {'speed': {'channel': 1}, 'errorLimit': {'record': 0, 'percentage': 0.0}}, 'content': [content]}}
            params = {'resourceList': [], 'localParams': [], 'customConfig': 1, 'json': json.dumps(job), 'xms': 1, 'xmx': 1, 'jobChannel': 1, 'jobSpeedByte': 0, 'jobSpeedRecord': 0}
            bounded('DATAX', params, 'DATAX', case_name='dedup-' + name, expected_state='FAILURE' if name == 'duplicate' else 'SUCCESS')
            actual = json.loads(sql("SELECT COALESCE(json_agg(r),'[]'::json) FROM (SELECT id::text,name,amount::text FROM qa_dedup.target_" + name + " ORDER BY id) r;"))
            expected = [{'id': '9007199254740993', 'name': 'A', 'amount': '12345678901234567890.12345678'}, {'id': '9007199254740994', 'name': 'a', 'amount': '2.00000000'},
                        {'id': '9007199254740995', 'name': None, 'amount': '3.00000000'}, {'id': '9007199254740996', 'name': None, 'amount': '4.00000000'}]
            expected = expected if name == 'all' else [expected[0], expected[2]] if name == 'keys' else []
            if actual != expected:
                raise RuntimeError('DataX真实目标值不符：' + name + ' ' + repr(actual))
            report['checks']['dedup-' + name].update({'querySha256': hashlib.sha256(query.encode()).hexdigest(), 'exactRows': actual, 'databaseCompared': True})
            print(json.dumps({'case': name, 'state': report['checks']['dedup-' + name]['state'], 'rows': len(actual)}, ensure_ascii=False), flush=True)
        report['status'] = 'PASS'
    finally:
        for check in report['checks'].values():
            code = check.get('workflowCode')
            if code:
                try:
                    client.request('POST', prefix + '/workflow-definition/' + code + '/release', {'releaseState': 'OFFLINE'})
                    report['cleanup'].append({'workflowCode': code, 'state': 'OFFLINE'})
                except Exception as failure:
                    report['cleanup'].append({'workflowCode': code, 'state': 'CLEANUP_FAILED'})
        if container:
            run('docker', 'rm', '-f', postgres, check=False)
        if attached:
            run('docker', 'network', 'disconnect', network, options.worker, check=False)
        if created:
            run('docker', 'network', 'rm', network, check=False)
        report['cleanup'].append({'fixtureContainerRemoved': container, 'workerNetworkDetached': attached, 'isolatedNetworkRemoved': created})
        if report['status'] != 'PASS':
            report['status'] = 'FAIL'
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'report': str(options.output), 'status': report['status']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
