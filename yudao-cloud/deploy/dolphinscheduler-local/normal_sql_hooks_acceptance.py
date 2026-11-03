#!/usr/bin/env python3
"""在正常本地 DS 验收 PostgreSQL 前后置 SQL、原子恢复与同实例重放；不创建服务或周期。"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import urllib.parse

import psycopg
from psycopg import sql
import yaml

from normal_upsert_acceptance import decrypt
from prepare_dev_native_runtime import local_endpoint, read_json
from smoke import Client


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
PRIVATE = ROOT / 'runtime/dev-native/application-dolphinscheduler-private.yaml'
OUTPUT = ROOT / 'verification/sql-hooks-20261102'
TERMINAL = ('SUCCESS', 'FAILURE', 'STOP', 'KILL')


def connect_target():
    app = REPO / 'yudao-module-dataforge/yudao-module-dataforge-server/src/main/resources/application-local.yaml'
    document = next(item for item in yaml.safe_load_all(app.read_text())
                    if item and 'datasource' in item.get('spring', {}))
    master = document['spring']['datasource']['dynamic']['datasource']['master']
    endpoint = urllib.parse.urlsplit(master['url'].removeprefix('jdbc:'))
    namespace = urllib.parse.parse_qs(endpoint.query)['currentSchema'][0]
    with psycopg.connect(host=endpoint.hostname, port=endpoint.port or 5432,
                         dbname=endpoint.path.lstrip('/'), user=master['username'],
                         password=master['password'], connect_timeout=10) as platform:
        with platform.cursor() as query:
            query.execute(sql.SQL('SELECT id,name,database_type,database_ip,database_port,database_name,'
                                  'database_schema,user_name,password,secret_key FROM {}.dh_dataforge_database '
                                  'WHERE id=1 AND deleted=0').format(sql.Identifier(namespace)))
            row = query.fetchone()
    if row is None or row[2] != 2 or row[1] != 'postgresql测试库':
        raise ValueError('只允许已确认的 PostgreSQL 测试连接 1')
    password = decrypt(row[8], row[9])
    connection = psycopg.connect(host=row[3], port=int(row[4]), dbname=row[5], user=row[7],
                                 password=password, connect_timeout=10, autocommit=True)
    return connection, row[6], [password, row[8], row[9], master['password']]


class Acceptance:
    def __init__(self, run_id, artifact_sha256=None):
        config = read_json(PRIVATE, private=True)
        endpoint = local_endpoint(config['dolphinscheduler']['server-url'])
        if urllib.parse.urlsplit(endpoint).port != 12345:
            raise ValueError('验收仅允许正常本地 DolphinScheduler 的 12345 端口')
        self.client = Client(endpoint)
        self.client.token = config['dolphinscheduler']['token']
        self.db, self.schema, self.secrets = connect_target()
        self.secrets.append(self.client.token)
        binding = config['dataforge']['integration']
        self.project = str(binding['ds-project-code'])
        self.prefix = '/projects/' + self.project
        self.environment = int(binding['engine-environment-codes']['DATAX'])
        self.datasource = binding['database-bindings']['1']['ds-datasource-id']
        self.worker = binding.get('worker-group', 'default')
        self.tenant = binding.get('tenant-code', 'datahub')
        self.output = OUTPUT / run_id
        self.output.mkdir(parents=True, exist_ok=False)
        self.names = {key: 'codex_sqlhooks_' + key + '_' + run_id for key in ('src', 'target', 'audit', 'control')}
        if any(len(value) > 63 for value in self.names.values()):
            raise ValueError('测试表名称超过 PostgreSQL 限制')
        self.tables = {key: sql.Identifier(self.schema, name) for key, name in self.names.items()}
        self.quoted = {key: identifier.as_string(self.db) for key, identifier in self.tables.items()}
        self.definitions = []
        self.instances = []
        self.report = {'runId': run_id, 'schema': self.schema, 'tables': self.names,
                       'platformDatabaseId': '1', 'dsDatasourceId': self.datasource,
                       'projectCode': self.project, 'normalDsPort': 12345,
                       'additionalServices': 0, 'schedulesCreated': 0, 'fixtureExecutionType': 'PARALLEL',
                       'runs': [], 'status': 'RUNNING'}
        if artifact_sha256:
            self.report['installedArtifactSha256'] = artifact_sha256
        self.save()

    def scrub(self, value):
        for secret in sorted(filter(None, self.secrets), key=len, reverse=True):
            value = value.replace(secret, '[REDACTED]')
        return value

    def save(self):
        encoded = json.dumps(self.report, ensure_ascii=False, indent=2, default=str) + '\n'
        (self.output / 'result.json').write_text(self.scrub(encoded))

    def prepare(self):
        with self.db.cursor() as query:
            query.execute(sql.SQL('CREATE TABLE {} (id bigint PRIMARY KEY,name text,amount numeric(18,2))').format(self.tables['src']))
            query.execute(sql.SQL('CREATE TABLE {} (id bigint PRIMARY KEY,name text,amount numeric(18,2) NOT NULL)').format(self.tables['target']))
            query.execute(sql.SQL('CREATE TABLE {} (id integer PRIMARY KEY,pre_count bigint NOT NULL,post_count bigint NOT NULL)').format(self.tables['audit']))
            query.execute(sql.SQL('CREATE TABLE {} (id integer PRIMARY KEY,n integer NOT NULL,divisor integer NOT NULL)').format(self.tables['control']))
            query.execute(sql.SQL("INSERT INTO {} SELECT n,CASE WHEN n%7=0 THEN NULL ELSE '测试中文-'||n END,n*1.25 FROM generate_series(1,37)n").format(self.tables['src']))
            query.execute(sql.SQL("INSERT INTO {} VALUES(1,'seed-before-copy',1.00)").format(self.tables['target']))
            query.execute(sql.SQL('INSERT INTO {} VALUES(1,0,0)').format(self.tables['audit']))
            query.execute(sql.SQL('INSERT INTO {} VALUES(1,0,1),(2,0,1)').format(self.tables['control']))
        self.report['ledgerBefore'] = self.ledger()
        self.save()

    def ledger(self):
        with self.db.cursor() as query:
            name = sql.Identifier(self.schema, '_datahub_upsert_runs').as_string(self.db)
            query.execute('SELECT to_regclass(%s)', (name,))
            if query.fetchone()[0] is None:
                return []
            query.execute(sql.SQL('SELECT execution_key,config_hash,data_hash,state,stage_name FROM {} ORDER BY execution_key').format(sql.Identifier(self.schema, '_datahub_upsert_runs')))
            return [dict(zip(('executionKey', 'configHash', 'dataHash', 'state', 'stageName'), row)) for row in query.fetchall()]

    def snapshot(self):
        with self.db.cursor() as query:
            query.execute(sql.SQL('SELECT id,name,amount FROM {} ORDER BY id').format(self.tables['target']))
            rows = query.fetchall()
            digest = hashlib.sha256(json.dumps(rows, ensure_ascii=False, default=str).encode()).hexdigest()
            query.execute(sql.SQL('SELECT pre_count,post_count FROM {} WHERE id=1').format(self.tables['audit']))
            pre, post = query.fetchone()
            query.execute(sql.SQL("SELECT count(*) FROM ((SELECT id,CASE WHEN id=2 THEN 'post-after-copy' ELSE name END AS name,amount FROM {} EXCEPT ALL SELECT id,name,amount FROM {}) UNION ALL (SELECT id,name,amount FROM {} EXCEPT ALL SELECT id,CASE WHEN id=2 THEN 'post-after-copy' ELSE name END AS name,amount FROM {})) d").format(self.tables['src'], self.tables['target'], self.tables['target'], self.tables['src']))
            differences = query.fetchone()[0]
        return {'rows': len(rows), 'uniqueIds': len({row[0] for row in rows}), 'targetDigest': digest,
                'preCount': pre, 'postCount': post, 'rowDifferencesAfterPostHook': differences,
                'firstRowName': next((row[1] for row in rows if row[0] == 1), None),
                'postHookRowName': next((row[1] for row in rows if row[0] == 2), None)}

    def definition(self, label):
        task_code = self.client.request('GET', self.prefix + '/task-definition/gen-task-codes', {'genNum': 1})[0]
        source, target, audit, control = (self.quoted[key] for key in ('src', 'target', 'audit', 'control'))
        parameters = {'customConfig': 0, 'dataSource': self.datasource, 'dsType': 'POSTGRESQL',
                      'dataTarget': self.datasource, 'dtType': 'POSTGRESQL',
                      'sql': 'SELECT id,name,amount FROM ' + source, 'targetTable': target,
                      'writeContract': 'POSTGRESQL_UPSERT_HOOKS_V2', 'upsertSchema': self.schema,
                      'upsertTable': self.names['target'], 'upsertKeys': ['id'], 'batchSize': 13,
                      'xms': 1, 'xmx': 1, 'jobChannel': 1, 'jobSpeedByte': 0, 'jobSpeedRecord': 0,
                      'localParams': [], 'resourceList': [],
                      'preStatements': [f'UPDATE {audit} SET pre_count=pre_count+1 WHERE id=1',
                                        f"UPDATE {target} SET name='pre-before-copy' WHERE id=1",
                                        f'UPDATE {control} SET n=100/divisor WHERE id=1'],
                      'postStatements': [f'UPDATE {audit} SET post_count=post_count+1 WHERE id=1',
                                         f"UPDATE {target} SET name='post-after-copy' WHERE id=2",
                                         f'UPDATE {control} SET n=100/divisor WHERE id=2']}
        task_name = 'SQL-hooks-' + label
        task = {'code': task_code, 'name': task_name, 'version': 1, 'description': '前后置 SQL 原子恢复与重放验收',
                'taskType': 'DATAX_UPSERT', 'taskParams': parameters, 'taskExecuteType': 'BATCH', 'flag': 'YES',
                'taskPriority': 'HIGHEST', 'workerGroup': self.worker, 'environmentCode': self.environment,
                'failRetryTimes': 0, 'failRetryInterval': 1, 'timeoutFlag': 'CLOSE', 'timeoutNotifyStrategy': 'FAILED',
                'timeout': 0, 'delayTime': 0, 'cpuQuota': -1, 'memoryMax': -1, 'isCache': 'NO'}
        name = 'datahub_sql_hooks_' + self.report['runId'] + '_' + label
        # 本 fixture 单独验证钩子与凭据行锁，脚本逐次等待终态；平台作业串行策略由主任务另外验收。
        definition = self.client.request('POST', self.prefix + '/workflow-definition', {
            'name': name, 'description': '保留测试数据和日志，无调度周期', 'globalParams': '[]', 'locations': '[]',
            'timeout': 0, 'executionType': 'PARALLEL', 'taskDefinitionJson': json.dumps([task]),
            'taskRelationJson': json.dumps([{'preTaskCode': 0, 'preTaskVersion': 0, 'postTaskCode': task_code,
                                           'postTaskVersion': 1, 'conditionType': 'NONE', 'conditionParams': {}}])})
        result = {'code': definition['code'], 'name': name, 'taskCode': task_code, 'taskName': task_name, 'label': label}
        self.definitions.append(result)
        self.report['definitions'] = self.definitions
        self.save()
        self.client.request('POST', self.prefix + '/workflow-definition/' + str(result['code']) + '/release',
                            {'releaseState': 'ONLINE', 'name': name})
        return result

    def tasks(self, instance):
        result = self.client.request('GET', self.prefix + f'/workflow-instances/{instance}/tasks')
        return result.get('taskList', []) if isinstance(result, dict) else result

    @staticmethod
    def signature(tasks):
        return [(item['id'], item.get('retryTimes'), item.get('startTime'), item.get('endTime')) for item in tasks]

    def run(self, definition, label, instance=None, operation=None):
        previous = self.signature(self.tasks(instance)) if instance is not None else None
        if instance is None:
            ids = self.client.request('POST', self.prefix + '/executors/start-workflow-instance', {
                'workflowDefinitionCode': definition['code'], 'scheduleTime': '', 'failureStrategy': 'END',
                'taskDependType': 'TASK_POST', 'execType': 'START_PROCESS', 'warningType': 'NONE', 'warningGroupId': 0,
                'runMode': 'RUN_MODE_SERIAL', 'workflowInstancePriority': 'MEDIUM', 'workerGroup': self.worker,
                'tenantCode': self.tenant, 'environmentCode': self.environment, 'startParams': '{}',
                'expectedParallelismNumber': 1, 'dryRun': 0})
            instance = ids[0]
        else:
            self.client.request('POST', self.prefix + '/executors/execute', {'workflowInstanceId': instance, 'executeType': operation})
        if instance not in self.instances:
            self.instances.append(instance)
            self.report['instanceIds'] = self.instances
            self.save()
        print(json.dumps({'phase': 'WAITING', 'label': label, 'instanceId': instance, 'operation': operation or 'START_PROCESS'}), flush=True)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            state = self.client.request('GET', self.prefix + f'/workflow-instances/{instance}')
            tasks = self.tasks(instance)
            changed = previous is None or self.signature(tasks) != previous
            if changed and tasks and state['state'] in TERMINAL and all(task['state'] in TERMINAL for task in tasks):
                break
            time.sleep(2)
        else:
            raise RuntimeError('等待 SQL hooks 实例终态超时：' + label)
        logs = []
        task_ids = []
        for task in tasks:
            task_ids.append(task['id'])
            output = self.client.request('GET', '/log/detail', {'taskInstanceId': task['id'], 'skipLineNum': 0, 'limit': 10000})['message']
            logs.append(self.scrub(output))
        log = '\n'.join(logs)
        (self.output / (label + '.log')).write_text(log)
        result = {'label': label, 'workflowCode': str(definition['code']), 'instanceId': instance,
                  'operation': operation or 'START_PROCESS', 'state': state['state'], 'taskInstanceIds': task_ids,
                  'snapshot': self.snapshot(), 'hooksMarkers': [line.strip() for line in log.splitlines() if 'UPSERT_HOOKS_V2' in line]}
        self.report['runs'].append(result)
        self.save()
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return result

    @staticmethod
    def success(result, expected_count):
        snapshot = result['snapshot']
        if result['state'] != 'SUCCESS' or snapshot['preCount'] != expected_count or snapshot['postCount'] != expected_count:
            raise AssertionError('成功运行或钩子执行次数不符：' + result['label'])
        if snapshot['rows'] != snapshot['uniqueIds'] or snapshot['rowDifferencesAfterPostHook'] != 0:
            raise AssertionError('目标行出现重复、遗漏或前后置 SQL 顺序不符：' + result['label'])
        if snapshot['firstRowName'] == 'pre-before-copy' or snapshot['postHookRowName'] != 'post-after-copy':
            raise AssertionError('钩子先后顺序不符：' + result['label'])

    @staticmethod
    def unchanged(result, before, expected_state):
        snapshot = result['snapshot']
        if result['state'] != expected_state:
            raise AssertionError('预期实例状态不符：' + result['label'])
        for field in ('targetDigest', 'rows', 'uniqueIds', 'preCount', 'postCount'):
            if snapshot[field] != before[field]:
                raise AssertionError('回滚或同实例重放改变已提交结果：' + result['label'] + '/' + field)

    def execute(self):
        self.prepare()
        normal = self.definition('normal')
        result = self.run(normal, '01-normal-success')
        self.success(result, 1)
        replay = self.run(normal, '02-committed-replay', result['instanceId'], 'REPEAT_RUNNING')
        self.unchanged(replay, result['snapshot'], 'SUCCESS')
        if not any('replay-skipped' in line for line in replay['hooksMarkers']):
            raise AssertionError('同实例重放没有明确跳过已提交钩子')

        pre = self.definition('pre_failure')
        with self.db.cursor() as query:
            query.execute(sql.SQL('UPDATE {} SET divisor=0 WHERE id=1').format(self.tables['control']))
            query.execute(sql.SQL("UPDATE {} SET name='pre-recovery-data' WHERE id=4").format(self.tables['src']))
        before = self.snapshot()
        failed = self.run(pre, '03-pre-failure')
        self.unchanged(failed, before, 'FAILURE')
        with self.db.cursor() as query:
            query.execute(sql.SQL('UPDATE {} SET divisor=1 WHERE id=1').format(self.tables['control']))
        recovered = self.run(pre, '04-pre-recovered', failed['instanceId'], 'START_FAILURE_TASK_PROCESS')
        self.success(recovered, 2)

        post = self.definition('post_failure')
        with self.db.cursor() as query:
            query.execute(sql.SQL('UPDATE {} SET divisor=0 WHERE id=2').format(self.tables['control']))
            query.execute(sql.SQL("UPDATE {} SET name='post-recovery-data',amount=44.25 WHERE id=3").format(self.tables['src']))
        before = self.snapshot()
        failed = self.run(post, '05-post-failure')
        self.unchanged(failed, before, 'FAILURE')
        with self.db.cursor() as query:
            query.execute(sql.SQL('UPDATE {} SET divisor=1 WHERE id=2').format(self.tables['control']))
        recovered = self.run(post, '06-post-recovered', failed['instanceId'], 'START_FAILURE_TASK_PROCESS')
        self.success(recovered, 3)
        replay = self.run(post, '07-recovered-replay', recovered['instanceId'], 'REPEAT_RUNNING')
        self.unchanged(replay, recovered['snapshot'], 'SUCCESS')

        data = self.definition('data_failure')
        with self.db.cursor() as query:
            query.execute(sql.SQL("UPDATE {} SET name='data-recovery-data' WHERE id=5").format(self.tables['src']))
            query.execute(sql.SQL('UPDATE {} SET amount=NULL WHERE id=16').format(self.tables['src']))
        before = self.snapshot()
        failed = self.run(data, '08-data-failure')
        self.unchanged(failed, before, 'FAILURE')
        with self.db.cursor() as query:
            query.execute(sql.SQL('UPDATE {} SET amount=20.00 WHERE id=16').format(self.tables['src']))
        recovered = self.run(data, '09-data-recovered', failed['instanceId'], 'START_FAILURE_TASK_PROCESS')
        self.success(recovered, 4)

        with self.db.cursor() as query:
            query.execute(sql.SQL("UPDATE {} SET name='new-logical-run',amount=99.75 WHERE id=1").format(self.tables['src']))
            query.execute(sql.SQL("INSERT INTO {} SELECT n,'新增-'||n,n*1.25 FROM generate_series(38,40)n").format(self.tables['src']))
        fresh = self.run(normal, '10-new-logical-run')
        self.success(fresh, 5)
        with self.db.cursor() as query:
            query.execute(sql.SQL("UPDATE {} SET name='changed-after-commit' WHERE id=6").format(self.tables['src']))
        changed = self.run(normal, '11-changed-source-rejected', fresh['instanceId'], 'REPEAT_RUNNING')
        self.unchanged(changed, fresh['snapshot'], 'FAILURE')
        if not any('publish-rollback' in line and 'phase=verify' in line for line in changed['hooksMarkers']):
            raise AssertionError('同实例输入变化没有在发布前明确拒绝')
        with self.db.cursor() as query:
            query.execute(sql.SQL("UPDATE {} SET name='测试中文-6' WHERE id=6").format(self.tables['src']))
        restored = self.run(normal, '12-original-source-recovered', fresh['instanceId'], 'START_FAILURE_TASK_PROCESS')
        self.success(restored, 5)
        self.unchanged(restored, fresh['snapshot'], 'SUCCESS')
        if not any('replay-skipped' in line for line in restored['hooksMarkers']):
            raise AssertionError('恢复原输入后没有按已提交身份跳过钩子')
        self.report['ledgerAfter'] = self.ledger()
        keys = {match.group(1) for run in self.report['runs'] for line in run['hooksMarkers']
                for match in [re.search(r'execution=([a-f0-9]{64})', line)] if match}
        new = [item for item in self.report['ledgerAfter'] if item['executionKey'] in keys]
        if len(new) != 5 or any(item['state'] != 'COMPLETED' for item in new):
            raise AssertionError('持久执行标记不符合五个成功逻辑实例')
        self.report['ownedExecutionKeys'] = sorted(keys)
        self.report['newCompletedLedgerEntries'] = len(new)
        stages = {match.group(1) for run in self.report['runs'] for line in run['hooksMarkers']
                  for match in [re.search(r'stage=(_datahub_upsert_stage_[a-f0-9]{32})', line)] if match}
        if len(stages) < 12:
            raise AssertionError('每次尝试未生成独立暂存表')
        with self.db.cursor() as query:
            query.execute('SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname=%s AND tablename=ANY(%s)',
                          (self.schema, sorted(stages)))
            retained = {row[0] for row in query.fetchall()}
        if retained != stages:
            raise AssertionError('本次验收暂存数据未完整保留')
        self.report['retainedStageTables'] = sorted(retained)
        self.report['status'] = 'PASS'
        self.save()

    def close(self):
        errors = []
        states = {}
        for instance in self.instances:
            try:
                state = self.client.request('GET', self.prefix + f'/workflow-instances/{instance}')['state']
                if state not in TERMINAL:
                    self.client.request('POST', self.prefix + '/executors/execute', {'workflowInstanceId': instance, 'executeType': 'STOP'})
                    deadline = time.monotonic() + 60
                    while state not in TERMINAL and time.monotonic() < deadline:
                        time.sleep(2)
                        state = self.client.request('GET', self.prefix + f'/workflow-instances/{instance}')['state']
                    if state not in TERMINAL:
                        raise RuntimeError('本次验收实例尚未停止：' + str(instance))
                states[str(instance)] = state
            except Exception as failure:
                errors.append(self.scrub(str(failure)))
        self.report['finalInstanceStates'] = states
        for definition in self.definitions:
            try:
                schedules = self.client.request('GET', self.prefix + '/schedules', {
                    'workflowDefinitionCode': definition['code'], 'pageNo': 1, 'pageSize': 100})
                rows = schedules.get('totalList', schedules.get('list', [])) if isinstance(schedules, dict) else schedules
                for item in rows or []:
                    if item.get('releaseState') == 'ONLINE':
                        self.client.request('POST', self.prefix + f"/schedules/{item['id']}/offline")
                self.client.request('POST', self.prefix + '/workflow-definition/' + str(definition['code']) + '/release',
                                    {'releaseState': 'OFFLINE', 'name': definition['name']})
                definition['finalReleaseState'] = 'OFFLINE'
                definition['schedulesFound'] = len(rows or [])
            except Exception as failure:
                errors.append(self.scrub(str(failure)))
        self.report['cleanupErrors'] = errors
        self.report['testSchedulesClosed'] = not errors
        if errors:
            self.report['status'] = 'FAIL'
        self.save()
        self.db.close()
        self.write_markdown()
        if not errors and self.report['status'] == 'PASS':
            (OUTPUT / 'result.json').write_text((self.output / 'result.json').read_text())

    def write_markdown(self):
        run_id = self.report['runId']
        base = 'verification/sql-hooks-20261102/' + run_id
        lines = [
            '# 正常本地 DolphinScheduler 前后置 SQL 验收', '',
            f"执行编号：`{run_id}`；结果：**{self.report['status']}**；DS 项目：`{self.project}`。", '',
            f"安装插件 SHA-256：`{self.report.get('installedArtifactSha256', '由主任务另行记录')}`。", '',
            '本报告记录原正常本地 DolphinScheduler（12345）的真实 DATAX_UPSERT 执行。没有创建独立测试服务或新端口，没有创建调度周期。平台配置表单与编译器的验收由主任务另行记录，不能从本报告推定其已通过。', '',
            f"本次验收定义执行类型：`{self.report.get('fixtureExecutionType', 'SERIAL_WAIT')}`，脚本逐次等待终态。钩子同身份互斥由 PostgreSQL 凭据行锁保证，不依赖 DS 串行队列。最终 fixture 采用 PARALLEL，单独验证 SQL 钩子事务与凭据；平台作业串行策略由主任务独立验收，不能从本报告推定。", '',
            '## 运行契约', '',
            '`POSTGRESQL_UPSERT_HOOKS_V2` 将每次尝试的数据写入独立暂存表，再在同一个 PostgreSQL 事务内执行前置 SQL、目标合并、后置 SQL和 COMPLETED 凭据提交。任一阶段失败则整笔发布回滚。恢复使用原 DS 工作流实例及任务身份；已提交身份只有在输入数据摘要一致时跳过，输入变化明确拒绝。新逻辑实例允许处理新数据。', '',
            '本次 SQL 仅使用同一目标 schema 中的受管、事务性 UPDATE。非幂等计数器加一用于检测钩子是否重复，除零和 NOT NULL 约束用于制造真实数据库失败。没有在本次验收中运行 DDL 钩子、函数、跨 schema 或外部副作用。', '',
            '## 实际执行矩阵', '',
            '| 场景 | 工作流实例 | 任务实例 | DS 状态 | 行数/唯一键 | 前/后置计数 | 行差异 | 日志 |',
            '| --- | --- | --- | --- | --- | --- | --- | --- |',
        ]
        for run in self.report['runs']:
            snapshot = run['snapshot']
            task_ids = ','.join(str(value) for value in run['taskInstanceIds'])
            lines.append(f"| {run['label']} | {run['instanceId']} | {task_ids} | {run['state']} | {snapshot['rows']}/{snapshot['uniqueIds']} | {snapshot['preCount']}/{snapshot['postCount']} | {snapshot['rowDifferencesAfterPostHook']} | [日志]({base}/{run['label']}.log) |")
        lines += ['', '失败场景的行差异是与已改变的当前源数据比较，不代表已提交目标出现遗漏；失败及重放均独立比较完整目标摘要与前后置计数器，要求与本次尝试前完全一致。成功场景按后置 SQL 的明确转换结果逐行进行双向 EXCEPT ALL 校验。', '',
                  '前置 SQL 将目标 id=1 写成临时标记，随后必须被数据合并覆盖；后置 SQL 将 id=2 写成最终标记，必须保留。该断言同时检验真实执行顺序。', '',
                  '## 保留对象与结束状态', '']
        for key, name in self.names.items():
            lines.append(f'- {key}：`{self.schema}.{name}`')
        lines += ['', f"本次完成凭据数：`{self.report.get('newCompletedLedgerEntries', '未完成')}`。工作流定义、全部执行日志、每次尝试的暂存表和目标 schema 内 `_datahub_upsert_runs` 均保留；没有删除或清空业务表。暂存表的人工清理应在确认不再用于验收和恢复后进行，本脚本不自动清理。", '',
                  f"最终实例状态：`{json.dumps(self.report.get('finalInstanceStates', {}), ensure_ascii=False)}`。", '',
                  f"测试周期已关闭：`{self.report.get('testSchedulesClosed', False)}`；创建周期数：`0`。定义结束状态如下：", '']
        for definition in self.definitions:
            lines.append(f"- `{definition['code']}` / `{definition['name']}`：{definition.get('finalReleaseState', '未确认')}，周期数 {definition.get('schedulesFound', '未确认')}")
        lines += ['', '## SQL 执行顺序', '',
                  '无需平台版本化 SQL 迁移。本次仅对平台已登记的外部 PostgreSQL 测试连接 1 执行独立测试表 DDL，按 src → target → audit → control 建表及写入测试数据；运行时通过 DB Switch 生成目标 schema 的凭据表和独立暂存表。之后的前置 SQL → 合并 → 后置 SQL → 完成凭据按上述原子事务执行。所有对象、数据与日志保留。', '',
                  f'[完整结构化结果]({base}/result.json)', '']
        content = '\n'.join(lines)
        (self.output / 'VALIDATION.md').write_text(content.replace(base + '/', ''))
        if self.report['status'] == 'PASS':
            (ROOT / 'SQL_HOOKS_VALIDATION_20261102.md').write_text(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', default='20261102_' + str(int(time.time())))
    parser.add_argument('--installed-artifact-sha256')
    arguments = parser.parse_args()
    if not arguments.run_id.replace('_', '').isalnum() or len(arguments.run_id) > 32:
        raise ValueError('验收编号格式无效')
    if arguments.installed_artifact_sha256 and not re.fullmatch(r'[a-f0-9]{64}', arguments.installed_artifact_sha256):
        raise ValueError('插件归档摘要格式无效')
    acceptance = Acceptance(arguments.run_id, arguments.installed_artifact_sha256)
    try:
        acceptance.execute()
    except Exception as failure:
        acceptance.report['status'] = 'FAIL'
        acceptance.report['error'] = acceptance.scrub(str(failure))
        acceptance.save()
        print(json.dumps({'status': 'FAIL', 'error': acceptance.report['error'], 'evidence': str(acceptance.output)}, ensure_ascii=False), flush=True)
        return 1
    finally:
        acceptance.close()
    print(json.dumps({'status': acceptance.report['status'], 'evidence': str(acceptance.output)}, ensure_ascii=False), flush=True)
    return 0 if acceptance.report['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
