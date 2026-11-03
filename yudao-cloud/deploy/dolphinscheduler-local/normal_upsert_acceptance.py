#!/usr/bin/env python3
"""使用已登记 PostgreSQL 测试连接，验收正常 DS 幂等写入与重放；不创建服务或周期。"""
import base64
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.parse
import urllib.request

import psycopg
from psycopg import sql
import yaml
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from prepare_dev_native_runtime import write_private_configuration
from smoke import Client, wait_for

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
PRIVATE = ROOT / 'runtime/dev-native/application-dolphinscheduler-private.yaml'
OUTPUT = ROOT / 'verification/normal-upsert-20261102'
SOURCE = 'codex_job_normal_source_20261102'
TARGET = 'codex_job_normal_target_20261102'


def decrypt(value, key):
    if not value or not key:
        return value
    raw = key.encode()
    if len(raw) not in (16, 24, 32):
        raw = hashlib.sha256(raw).digest()
    cipher = Cipher(algorithms.AES(raw), modes.ECB()).decryptor()
    result = cipher.update(base64.b64decode(value)) + cipher.finalize()
    return result[:-result[-1]].decode()


def main():
    config = json.loads(PRIVATE.read_text())
    client = Client(config['dolphinscheduler']['server-url'])
    client.token = config['dolphinscheduler']['token']
    app = REPO / 'yudao-module-dataforge/yudao-module-dataforge-server/src/main/resources/application-local.yaml'
    data = next(item for item in yaml.safe_load_all(app.read_text()) if item and 'datasource' in item.get('spring', {}))['spring']['datasource']['dynamic']['datasource']['master']
    url = urllib.parse.urlsplit(data['url'].removeprefix('jdbc:'))
    namespace = urllib.parse.parse_qs(url.query)['currentSchema'][0]
    with psycopg.connect(host=url.hostname, port=url.port or 5432, dbname=url.path.lstrip('/'), user=data['username'], password=data['password'], connect_timeout=10) as platform:
        with platform.cursor() as cursor:
            cursor.execute(sql.SQL('SELECT id,name,database_type,database_ip,database_port,database_name,database_schema,user_name,password,secret_key FROM {}.dh_dataforge_database WHERE id=1 AND deleted=0').format(sql.Identifier(namespace)))
            row = cursor.fetchone()
    if row is None or row[2] != 2 or row[1] != 'postgresql测试库':
        raise ValueError('只允许已确认的 PostgreSQL 测试连接 1')
    password = decrypt(row[8], row[9])
    schema = row[6]
    db = psycopg.connect(host=row[3], port=int(row[4]), dbname=row[5], user=row[7], password=password, connect_timeout=10, autocommit=True)
    secrets = [password, row[8], row[9], data['password'], client.token]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {'platformDatabaseId': '1', 'sourceTable': SOURCE, 'targetTable': TARGET, 'schema': schema,
              'normalDsPort': 12345, 'additionalServices': 0, 'schedulesCreated': 0, 'runs': []}
    def save():
        (OUTPUT / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    def ds_json(path, payload):
        req = urllib.request.Request(client.base_url + path, data=json.dumps(payload).encode(), headers={'token': client.token, 'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.load(response)
        if result.get('code') != 0:
            raise RuntimeError('DS 数据源操作失败，未输出连接参数')
        return result.get('data')
    source = sql.Identifier(schema, SOURCE)
    target = sql.Identifier(schema, TARGET)
    with db.cursor() as q:
        q.execute(sql.SQL('CREATE TABLE IF NOT EXISTS {} (id bigint PRIMARY KEY,name text,amount numeric(18,2))').format(source))
        q.execute(sql.SQL('CREATE TABLE IF NOT EXISTS {} (id bigint PRIMARY KEY,name text,amount numeric(18,2))').format(target))
        q.execute(sql.SQL("INSERT INTO {} SELECT n,CASE WHEN n%7=0 THEN NULL ELSE '测试中文-'||n END,n*1.25 FROM generate_series(1,37)n ON CONFLICT(id) DO NOTHING").format(source))
    name = 'datahub-local-postgresql-test-1'
    existing = client.request('GET', '/datasources/list', {'type': 'POSTGRESQL'})
    datasource = next((item for item in existing if item['name'] == name), None)
    if datasource is None:
        body = {'type': 'POSTGRESQL', 'name': name, 'host': row[3], 'port': int(row[4]), 'database': row[5],
                'userName': row[7], 'password': password, 'other': {'currentSchema': schema}, 'note': '正常本地 DS 绑定平台测试数据源 1'}
        ds_json('/datasources/connect', body)
        ds_json('/datasources', body)
        datasource = next(item for item in client.request('GET', '/datasources/list', {'type': 'POSTGRESQL'}) if item['name'] == name)
    binding = config['dataforge']['integration']
    binding.setdefault('database-bindings', {})['1'] = {'ds-datasource-id': datasource['id'], 'ds-type': 'POSTGRESQL'}
    write_private_configuration(PRIVATE, config)
    project = str(binding['ds-project-code'])
    environment = int(binding['engine-environment-codes']['DATAX'])
    prefix = '/projects/' + project
    task_code = client.request('GET', prefix + '/task-definition/gen-task-codes', {'genNum': 1})[0]
    quoted_source = sql.Identifier(schema, SOURCE).as_string(db)
    quoted_target = sql.Identifier(schema, TARGET).as_string(db)
    parameters = {'customConfig': 0, 'dataSource': datasource['id'], 'dsType': 'POSTGRESQL', 'dataTarget': datasource['id'],
                  'dtType': 'POSTGRESQL', 'sql': f'SELECT id,name,amount FROM {quoted_source}', 'targetTable': quoted_target,
                  'writeContract': 'POSTGRESQL_UPSERT_V1', 'upsertSchema': schema, 'upsertTable': TARGET, 'upsertKeys': ['id'],
                  'batchSize': 16, 'xms': 1, 'xmx': 1, 'jobChannel': 1, 'jobSpeedByte': 0, 'jobSpeedRecord': 0,
                  'localParams': [], 'resourceList': [], 'preStatements': [], 'postStatements': []}
    task = {'code': task_code, 'name': '正常本地 PostgreSQL 幂等写入', 'version': 1, 'description': '保留样例与日志',
            'taskType': 'DATAX_UPSERT', 'taskParams': parameters, 'taskExecuteType': 'BATCH', 'flag': 'YES',
            'taskPriority': 'HIGHEST', 'workerGroup': 'datahub-local', 'environmentCode': environment,
            'failRetryTimes': 0, 'failRetryInterval': 1, 'timeoutFlag': 'CLOSE', 'timeoutNotifyStrategy': 'FAILED',
            'timeout': 0, 'delayTime': 0, 'cpuQuota': -1, 'memoryMax': -1, 'isCache': 'NO'}
    workflow_name = 'datahub_normal_upsert_20261102_' + str(int(time.time()))
    definition = client.request('POST', prefix + '/workflow-definition', {'name': workflow_name, 'description': '正常本地插件与重放验收，无周期',
        'globalParams': '[]', 'locations': '[]', 'timeout': 0, 'executionType': 'SERIAL_WAIT', 'taskDefinitionJson': json.dumps([task]),
        'taskRelationJson': json.dumps([{'preTaskCode': 0, 'preTaskVersion': 0, 'postTaskCode': task_code, 'postTaskVersion': 1, 'conditionType': 'NONE', 'conditionParams': {}}])})
    code = definition['code']; report.update(projectCode=project, workflowCode=str(code), dsDatasourceId=datasource['id'])
    client.request('POST', prefix + f'/workflow-definition/{code}/release', {'releaseState': 'ONLINE', 'name': workflow_name})
    for attempt in range(1, 4):
        if attempt == 3:
            with db.cursor() as q:
                q.execute(sql.SQL("UPDATE {} SET name='重放更新',amount=99.75 WHERE id=1").format(source))
                q.execute(sql.SQL("INSERT INTO {} SELECT n,'追加-'||n,n*1.25 FROM generate_series(38,40)n ON CONFLICT(id) DO NOTHING").format(source))
        ids = client.request('POST', prefix + '/executors/start-workflow-instance', {'workflowDefinitionCode': code, 'scheduleTime': '',
            'failureStrategy': 'END', 'taskDependType': 'TASK_POST', 'execType': 'START_PROCESS', 'warningType': 'NONE',
            'warningGroupId': 0, 'runMode': 'RUN_MODE_SERIAL', 'workflowInstancePriority': 'MEDIUM', 'workerGroup': 'datahub-local',
            'tenantCode': 'datahub', 'environmentCode': environment, 'startParams': '{}', 'expectedParallelismNumber': 1, 'dryRun': 0})
        instance = ids[0]
        result = wait_for(lambda: client.request('GET', prefix + f'/workflow-instances/{instance}'),
                          lambda value: value['state'] in ('SUCCESS', 'FAILURE', 'STOP'), 180, '幂等写入终态')
        tasks = client.request('GET', prefix + f'/workflow-instances/{instance}/tasks')['taskList']
        output = client.request('GET', '/log/detail', {'taskInstanceId': tasks[0]['id'], 'skipLineNum': 0, 'limit': 2000})['message']
        for secret in sorted(filter(None, secrets), key=len, reverse=True):
            output = output.replace(secret, '[REDACTED]')
        (OUTPUT / f'attempt-{attempt}.log').write_text(output)
        with db.cursor() as q:
            q.execute(sql.SQL('SELECT count(*),count(DISTINCT id) FROM {}').format(target)); count, unique = q.fetchone()
            q.execute(sql.SQL('SELECT count(*) FROM ((SELECT * FROM {} EXCEPT ALL SELECT * FROM {}) UNION ALL (SELECT * FROM {} EXCEPT ALL SELECT * FROM {})) differences').format(source,target,target,source)); difference = q.fetchone()[0]
        check = {'attempt': attempt, 'instanceId': instance, 'taskInstanceId': tasks[0]['id'], 'state': result['state'], 'count': count,
                 'uniqueIds': unique, 'rowDifferences': difference, 'taskPriority': tasks[0].get('taskInstancePriority')}
        report['runs'].append(check); save(); print(json.dumps(check), flush=True)
        if result['state'] != 'SUCCESS' or count != unique or difference != 0:
            raise RuntimeError('正常 DS 实际落库验收失败，查看保留的脱敏日志')
    report['status'] = 'PASS'; save(); db.close()


if __name__ == '__main__':
    main()
