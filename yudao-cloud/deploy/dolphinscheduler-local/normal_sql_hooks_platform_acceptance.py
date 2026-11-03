#!/usr/bin/env python3
"""通过正常 DataForge API 验收前后置 SQL，保留测试数据和日志，不启用周期。"""

import argparse
from datetime import datetime
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

from psycopg import sql

from normal_sql_hooks_acceptance import connect_target
from prepare_dev_native_runtime import local_endpoint, read_json


ROOT = Path(__file__).resolve().parent
DEFAULT_CONFIG = ROOT / 'runtime/sql-hooks-platform-private.json'
DS_CONFIG = ROOT / 'runtime/dev-native/application-dolphinscheduler-private.yaml'
TERMINAL = {'SUCCESS', 'FAILURE', 'FAILED', 'STOPPED', 'CANCELLED', 'STOP', 'KILL'}


class PlatformAcceptance:
    def __init__(self, args):
        self.config = read_json(args.config, private=True)
        self.api_base = self.config['apiBase'].rstrip('/')
        endpoint = urllib.parse.urlsplit(self.api_base)
        if endpoint.scheme != 'http' or endpoint.hostname not in {'localhost', '127.0.0.1'} or endpoint.port != 48083:
            raise ValueError('仅允许正常本地网关 48083 的正式集成 API')
        if endpoint.path != '/admin-api/dataforge/integration' or endpoint.query or endpoint.fragment or endpoint.username:
            raise ValueError('验收配置必须指向正式数据集成 API，不得包含其他路径或身份')
        self.admin_base = self.api_base.removesuffix('/dataforge/integration')
        normal_ds = read_json(DS_CONFIG, private=True)
        if urllib.parse.urlsplit(local_endpoint(normal_ds['dolphinscheduler']['server-url'])).port != 12345:
            raise ValueError('仅允许正常本地 DolphinScheduler 12345')
        self.expected_project = str(normal_ds['dataforge']['integration']['ds-project-code'])
        self.db, self.schema, self.secrets = connect_target()
        self.secrets += [self.config['apiToken'], normal_ds['dolphinscheduler']['token']]
        self.run_id = args.run_id
        self.timeout = args.timeout
        self.output = args.output or ROOT / 'verification/sql-hooks-platform-20261102' / self.run_id
        self.output.mkdir(parents=True, exist_ok=False)
        self.names = {key: 'codex_phooks_' + key + '_' + self.run_id for key in ('src', 'target', 'audit')}
        if any(len(name) > 63 for name in self.names.values()):
            raise ValueError('测试表名称超过 PostgreSQL 限制')
        self.tables = {key: sql.Identifier(self.schema, name) for key, name in self.names.items()}
        self.quoted = {key: value.as_string(self.db) for key, value in self.tables.items()}
        self.task_id = None
        self.report = {'status': 'RUNNING', 'runId': self.run_id, 'scope': 'DataForge API -> normal DolphinScheduler -> PostgreSQL hooks V2',
                       'platformDatabaseId': '1', 'projectCode': self.expected_project, 'normalDsPort': 12345,
                       'additionalServices': 0, 'schedulesEnabled': 0, 'schema': self.schema, 'tables': self.names,
                       'runs': [], 'cleanup': {}}
        self.save()

    def scrub(self, value):
        for secret in sorted({value for value in self.secrets if isinstance(value, str) and value}, key=len, reverse=True):
            value = value.replace(secret, '[REDACTED]')
        return value

    def save(self):
        (self.output / 'result.json').write_text(self.scrub(json.dumps(self.report, ensure_ascii=False, indent=2, default=str)) + '\n')

    def call(self, path, method='GET', body=None, query=None, operations=False):
        base = self.admin_base if operations else self.api_base
        url = base + path + ('?' + urllib.parse.urlencode(query) if query else '')
        encoded = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        request = urllib.request.Request(url, encoded, headers={
            'Authorization': 'Bearer ' + self.config['apiToken'], 'Content-Type': 'application/json'}, method=method)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                result = json.load(response)
        except urllib.error.HTTPError as failure:
            raise RuntimeError('正式 API HTTP 失败：' + path + ' / ' + str(failure.code)) from None
        if result.get('code') != 0:
            raise RuntimeError(self.scrub(json.dumps({'path': path, 'code': result.get('code'), 'message': result.get('msg')}, ensure_ascii=False)))
        return result.get('data')

    def prepare(self):
        self.expected_rows = [(1, '中文-前后置 SQL', Decimal('90071992547409.93')),
                              (2, "引号 ' 与分号 ;", Decimal('-12.50')),
                              (3, None, Decimal('0.01')),
                              (4, '换行\nemoji-🧪', Decimal('33.25')),
                              (5, '  保留空格  ', Decimal('1.00'))]
        with self.db.cursor() as query:
            query.execute(sql.SQL('CREATE TABLE {} (id bigint PRIMARY KEY,name text,amount numeric(18,2))').format(self.tables['src']))
            query.execute(sql.SQL('CREATE TABLE {} (id bigint PRIMARY KEY,name text,amount numeric(18,2))').format(self.tables['target']))
            query.execute(sql.SQL('CREATE TABLE {} (id integer PRIMARY KEY,pre_count bigint NOT NULL,post_count bigint NOT NULL)').format(self.tables['audit']))
            query.executemany(sql.SQL('INSERT INTO {} VALUES(%s,%s,%s)').format(self.tables['src']), self.expected_rows)
            query.execute(sql.SQL('INSERT INTO {} VALUES(1,0,0)').format(self.tables['audit']))
        self.report['sourceRows'] = len(self.expected_rows)
        category_id = self.call('/category/create', 'POST', {'parentId': 0, 'name': 'SQL钩子验收_' + self.run_id, 'sort': 999, 'validFlag': True})
        self.report['categoryId'] = str(category_id)
        self.task_id = self.call('/create', 'POST', {
            'mode': 'OFFLINE', 'categoryId': category_id, 'name': 'codex_SQL钩子_正式API_' + self.run_id,
            'ownerId': 1, 'status': 0, 'engine': 'DATAX', 'scheduler': 'DOLPHIN_SCHEDULER',
            'cronExpression': '0 0 0 * * ?', 'workerGroup': 'default', 'retryCount': 0, 'retryInterval': 1,
            'delayMinutes': 0, 'priority': 'HIGHEST', 'executionStrategy': 'SERIAL_WAIT',
            'engineConfig': {'jvmInitialMemory': 1024, 'jvmMaximumMemory': 1024, 'channel': 1,
                             'byteSpeed': 0, 'recordSpeed': 0, 'errorLimit': 0},
            'description': '正常本地DS前后置SQL事务与精确数据验收；仅手动执行，结束后下线，保留测试表和日志'})
        self.report['taskId'] = str(self.task_id)
        self.save()
        task = self.call('/get', query={'id': self.task_id})
        if task['status'] != 0 or task.get('scheduleStatus') != 0:
            raise AssertionError('新测试任务不是离线且周期关闭')
        self.flow = self.graph()
        request = {'id': self.task_id, 'revision': task['revision'], 'draft': False,
                   'flowJson': json.dumps(self.flow, ensure_ascii=False)}
        self.report['flowSha256'] = hashlib.sha256(request['flowJson'].encode()).hexdigest()
        checked = self.call('/check', 'POST', request)
        self.report['flowCheck'] = checked
        self.save()
        if checked.get('valid') is not True:
            raise AssertionError('正常平台 SQL hooks 流程检查未通过')
        saved = self.call('/flow', 'PUT', request)
        if str(saved.get('dsProjectCode')) != self.expected_project or not saved.get('dsDefinitionCode'):
            raise AssertionError('正式流程没有发布到预期的正常本地 DS 项目')
        self.report['definitionCode'] = str(saved['dsDefinitionCode'])
        self.call('/status', 'PUT', {'id': self.task_id, 'revision': saved['revision'], 'status': 1})
        self.save()

    def graph(self):
        def node(identifier, component, config, kind, y):
            return {'id': identifier, 'type': 'etl-node', 'x': 200, 'y': y, 'properties': {
                'name': '表输入组件' if kind == 1 else '表输出组件', 'componentCode': component,
                'toolCode': component, 'toolType': kind, 'nodeJson': config}}
        audit = self.quoted['audit']
        return {'nodes': [
            node('read', 'read-db', {'databaseId': '1', 'schemaName': self.schema, 'tableName': self.names['src'],
                                    'ifSql': 0, 'readMode': 'FULL', 'selectedColumns': ['id', 'name', 'amount']}, 1, 100),
            node('write', 'insert-db', {'databaseId': '1', 'schemaName': self.schema, 'tableName': self.names['target'],
                                       'dataSyncType': 2, 'writeMode': 'UPSERT', 'upsertKeys': ['id'],
                                       'targetTruncate': False, 'batchSize': 2,
                                       'fieldMappings': [{'source': name, 'target': name, 'enabled': True} for name in ('id', 'name', 'amount')],
                                       'preSql': f'UPDATE {audit} SET pre_count=pre_count+1 WHERE id=1;',
                                       'postSql': f'UPDATE {audit} SET post_count=post_count+1 WHERE id=1;'}, 3, 320)],
                'edges': [{'id': 'read_write', 'sourceNodeId': 'read', 'targetNodeId': 'write'}]}

    def snapshot(self):
        with self.db.cursor() as query:
            query.execute(sql.SQL('SELECT id,name,amount FROM {} ORDER BY id').format(self.tables['target']))
            rows = query.fetchall()
            query.execute(sql.SQL('SELECT pre_count,post_count FROM {} WHERE id=1').format(self.tables['audit']))
            before, after = query.fetchone()
        return {'rows': len(rows), 'uniqueIds': len({row[0] for row in rows}), 'fullExactRowsMatch': rows == self.expected_rows,
                'preCount': before, 'postCount': after,
                'targetSha256': hashlib.sha256(json.dumps(rows, ensure_ascii=False, default=str).encode()).hexdigest()}

    def run(self, attempt):
        record_id = self.call('/run', 'POST', query={'id': self.task_id})
        result = {'attempt': attempt, 'recordId': str(record_id), 'observedRunning': False}
        self.report['runs'].append(result)
        self.save()
        print(json.dumps({'phase': 'WAITING', 'taskId': str(self.task_id), 'recordId': str(record_id), 'attempt': attempt}), flush=True)
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            records = self.call('/records', query={'taskId': self.task_id, 'pageNo': 1, 'pageSize': 20})
            current = next((row for row in records['list'] if str(row['id']) == str(record_id)), None)
            if current:
                result['status'] = current['status']
                result['dsInstanceId'] = current.get('dsInstanceId')
                result['dsProjectCode'] = str(current.get('dsProjectCode'))
                result['observedRunning'] |= current['status'] == 'RUNNING'
                if current['status'] in TERMINAL:
                    break
            time.sleep(2)
        else:
            raise TimeoutError('正常平台实例等待终态超时')
        identity = {'id': record_id, 'kind': 'INTEGRATION', 'origin': 'MANAGED'}
        operations = self.call('/dataforge/ops-instance/get', query=identity, operations=True)
        result['operations'] = {key: operations.get(key) for key in ('id', 'origin', 'kind', 'taskId', 'status', 'dsProjectCode', 'dsInstanceId')}
        log = self.call('/dataforge/ops-instance/log', query=identity, operations=True)
        message = self.scrub(log.get('message') or '')
        (self.output / ('attempt-' + str(attempt) + '.log')).write_text(message)
        result['logAvailable'] = log.get('available')
        result['hookMarkers'] = [line.strip() for line in message.splitlines() if 'UPSERT_HOOKS_V2' in line]
        result['snapshot'] = self.snapshot()
        self.save()
        if result['status'] != 'SUCCESS' or operations.get('status') != 'SUCCESS':
            raise AssertionError('正常平台或运维实例未确认成功')
        if result['dsProjectCode'] != self.expected_project or str(operations.get('dsInstanceId')) != str(result['dsInstanceId']):
            raise AssertionError('平台记录与运维实例的正常 DS 身份不一致')
        if not result['snapshot']['fullExactRowsMatch'] or result['snapshot']['rows'] != result['snapshot']['uniqueIds']:
            raise AssertionError('目标包含重复、遗漏或精确小数/Unicode 数据差异')
        if (result['snapshot']['preCount'], result['snapshot']['postCount']) != (attempt, attempt):
            raise AssertionError('每个新逻辑执行的前后置 SQL 未各执行一次')
        if not log.get('available') or not any('publish-committed' in line for line in result['hookMarkers']):
            raise AssertionError('运维日志未包含 SQL hooks 提交成功标记')
        result['passed'] = True
        self.save()
        print(json.dumps({'attempt': attempt, 'status': result['status'], 'recordId': str(record_id),
                          'dsInstanceId': result['dsInstanceId'], 'snapshot': result['snapshot']}, ensure_ascii=False), flush=True)

    def cleanup(self):
        if self.task_id is None:
            return
        task = self.call('/get', query={'id': self.task_id})
        if task.get('scheduleStatus') != 0:
            self.call('/schedule', 'PUT', {'id': self.task_id, 'revision': task['revision'], 'scheduleStatus': 0,
                                           'cronExpression': task.get('cronExpression')})
            task = self.call('/get', query={'id': self.task_id})
        if task.get('activeRunning'):
            self.call('/stop', 'POST', query={'id': self.task_id})
            deadline = time.monotonic() + 90
            while task.get('activeRunning') and time.monotonic() < deadline:
                time.sleep(2)
                self.call('/records', query={'taskId': self.task_id, 'pageNo': 1, 'pageSize': 20})
                task = self.call('/get', query={'id': self.task_id})
            if task.get('activeRunning'):
                raise RuntimeError('测试实例尚未停止，不能将任务标记为已下线')
        if task['status'] != 0:
            self.call('/status', 'PUT', {'id': self.task_id, 'revision': task['revision'], 'status': 0})
        task = self.call('/get', query={'id': self.task_id})
        self.report['cleanup'] = {'status': task['status'], 'scheduleStatus': task.get('scheduleStatus'),
                                  'activeRunning': task.get('activeRunning'), 'dataAndLogsRetained': True}
        if task['status'] != 0 or task.get('scheduleStatus') != 0 or task.get('activeRunning'):
            raise AssertionError('测试任务未确认下线或周期未关闭')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    parser.add_argument('--run-id', default=datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(2))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--runs', type=int, choices=(1, 2), default=2)
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args()
    if not re.fullmatch('[a-zA-Z0-9_]{1,28}', args.run_id) or not 30 <= args.timeout <= 600:
        parser.error('run-id 必须为 1-28 位字母数字下划线，timeout 必须为 30-600 秒')
    acceptance = PlatformAcceptance(args)
    failed = None
    try:
        acceptance.prepare()
        for attempt in range(1, args.runs + 1):
            acceptance.run(attempt)
        acceptance.report['status'] = 'PASS'
    except Exception as error:
        failed = error
        acceptance.report['status'] = 'FAIL'
        acceptance.report['error'] = acceptance.scrub(str(error))
    finally:
        try:
            acceptance.cleanup()
        except Exception as error:
            acceptance.report['status'] = 'FAIL'
            acceptance.report['cleanupError'] = acceptance.scrub(str(error))
            failed = failed or error
        acceptance.save()
        acceptance.db.close()
    print(json.dumps({'status': acceptance.report['status'], 'taskId': str(acceptance.task_id),
                      'report': str(acceptance.output / 'result.json'), 'cleanup': acceptance.report['cleanup']}, ensure_ascii=False), flush=True)
    if failed is not None:
        raise SystemExit(acceptance.scrub(str(failed)))


if __name__ == '__main__':
    main()
