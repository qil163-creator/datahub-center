#!/usr/bin/env python3
"""执行并核验隔离环境的真实周期和作业并发策略，不伪造调度或数据库状态。"""

import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import re
import time
from pathlib import Path
from zoneinfo import ZoneInfo

from policy_runtime import Runtime


class Suite:
    def __init__(self, runtime, output, attempt='initial'):
        self.r = runtime
        self.output = Path(output)
        self.tasks = json.loads((self.output / 'tasks.json').read_text())
        self.path = self.output / 'policy-execution.json'
        self.report = json.loads(self.path.read_text()) if self.path.exists() else {'cases': {}, 'executionApiPort': 50027}
        self.current = None
        if not re.fullmatch(r'[a-zA-Z0-9_-]{1,12}', attempt):
            raise RuntimeError('验收attempt标识无效')
        self.attempt = attempt

    def save(self):
        self.r.save('policy-execution.json', self.report)

    def row(self, record):
        return self.r.api('/dataforge/ops-instance/get', query={'kind': 'JOB', 'origin': 'PLATFORM', 'id': record})

    @staticmethod
    def brief(row):
        keys = ['id', 'status', 'queueState', 'queueReason', 'queuedTime', 'scheduledTime', 'executionType',
                'dsProjectCode', 'dsDefinitionCode', 'dsInstanceId', 'submissionStage', 'startTime', 'endTime']
        result = {key: row.get(key) for key in keys}
        result['children'] = [{key: child.get(key) for key in ['id', 'kind', 'origin', 'status', 'taskId']}
                              for child in row.get('children') or []]
        return result

    def wait(self, probe, predicate, label, seconds=120):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = probe()
            if predicate(value):
                return value
            time.sleep(1)
        raise RuntimeError('有界等待超时：' + label + '；保留原record/request，不自动重投')

    def submit(self, policy, label):
        case = self.report['cases'].setdefault(policy, {'records': {}, 'observations': []})
        saved = case['records'].get(label)
        if saved:
            return saved['recordId']
        task = self.tasks['policies'][policy]
        request = {'id': task['jobId'], 'revision': task['jobRevision'],
                   'requestId': 'cjp_' + self.r.fixture['suffix'] + '_' + policy + '_' + self.attempt + '_' + label}
        case['pendingIntent'] = request
        self.save()
        record = self.r.api('/dataforge/job/run', 'POST', request)
        case['records'][label] = {'recordId': record, 'request': request}
        case.pop('pendingIntent', None)
        self.save()
        return record

    def observe(self, policy, records, label):
        values = [self.brief(self.row(record)) for record in records]
        self.report['cases'][policy]['observations'].append({'label': label, 'records': values})
        self.save()
        return values

    def blocked(self, policy, record):
        def probe():
            row = self.row(record)
            if row['status'] in ['SUCCESS', 'FAILURE', 'STOPPED']:
                self.r.save('unexpected-boundary-' + str(record) + '.json', self.brief(row))
                raise RuntimeError('到达真实锁窗口前执行已终态：record=' + str(record) + '/' + row['status'])
            return {'record': self.brief(row), 'writerWaiters': self.r.writer_waiters(policy)}
        value = self.wait(probe, lambda x: x['record']['dsInstanceId'] and x['record']['submissionStage'] == 'BOUND'
                          and x['writerWaiters'], '真实DataX进程在fixture锁上等待')
        parent = self.r.ds('/projects/' + self.r.private['ds']['projectCode'] + '/workflow-instances/' + str(value['record']['dsInstanceId']))
        if parent['state'] != 'RUNNING_EXECUTION':
            raise RuntimeError('fixture writer虽阻塞，但受管DS父未真实运行')
        return value

    def announce_window(self, policy, records, extra=None):
        task = self.tasks['policies'][policy]
        payload = {'policy': policy, 'jobId': task['jobId'], 'jobName': task['jobName'],
                   'recordIds': records, 'records': [self.brief(self.row(value)) for value in records],
                   'apiPort': 50027, 'preview': 'http://127.0.0.1:6006', **(extra or {})}
        self.r.save('window-' + policy + '.json', payload)
        print(json.dumps({'event': 'WINDOW_READY', 'policy': policy, 'jobId': task['jobId'], 'recordIds': records}, ensure_ascii=False), flush=True)

    def ui_hold(self, policy, seconds=25, backend_restart=False):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            ui = (self.output / ('ui-' + policy + '.done')).exists()
            backend = not backend_restart or (self.output / 'backend-restart.done').exists()
            if ui and backend:
                return
            time.sleep(1)
        if backend_restart and not (self.output / 'backend-restart.done').exists():
            raise RuntimeError('受控后端重启尚未就绪；不把中断窗口当成通过')

    def success(self, policy, record):
        row = self.wait(lambda: self.row(record), lambda x: x['status'] in ['SUCCESS', 'FAILURE', 'STOPPED'], '平台真实终态')
        if row['status'] != 'SUCCESS' or not row.get('dsInstanceId') or not row.get('dsDefinitionCode'):
            raise RuntimeError('本次真实父执行未成功：' + str(record) + '/' + str(row['status']))
        children = row.get('children') or []
        if len(children) != 2 or {child['kind']: child['origin'] for child in children} != {'INTEGRATION': 'MANAGED', 'DEVELOPMENT': 'PLATFORM'}:
            raise RuntimeError('成功记录必须恰有两个正确origin的真实平台子记录')
        if any(child['status'] != 'SUCCESS' for child in children):
            raise RuntimeError('平台子记录未全部成功')
        project = self.r.private['ds']['projectCode']
        prefix = '/projects/' + project + '/workflow-instances'
        parent = self.r.ds(prefix + '/' + str(row['dsInstanceId']))
        if parent['id'] != row['dsInstanceId'] or str(parent['projectCode']) != project or str(parent['workflowDefinitionCode']) != str(row['dsDefinitionCode']) or parent['state'] != 'SUCCESS':
            raise RuntimeError('DS父三元身份或真实终态不符')
        tasks = self.r.ds(prefix + '/' + str(parent['id']) + '/tasks').get('taskList', [])
        if len(tasks) != 2:
            raise RuntimeError('真实成功父必须恰有两个SUB_WORKFLOW任务')
        ds_children = []
        for task in tasks:
            if task['taskType'] != 'SUB_WORKFLOW' or task['workflowInstanceId'] != parent['id']:
                raise RuntimeError('父任务身份不符')
            child_id = self.r.ds(prefix + '/query-sub-by-parent', {'taskId': task['id']}).get('subWorkflowInstanceId')
            if type(child_id) is not int:
                raise RuntimeError('缺少真实DS子实例')
            child = self.r.ds(prefix + '/' + str(child_id))
            reverse = self.r.ds(prefix + '/query-parent-by-sub', {'subId': child_id}).get('parentWorkflowInstance')
            if type(reverse) is not int or reverse != parent['id'] or str(child['projectCode']) != project or child['state'] != 'SUCCESS':
                raise RuntimeError('子实例双向父关系、项目或终态不符')
            workers = self.r.ds(prefix + '/' + str(child_id) + '/tasks').get('taskList', [])
            if len(workers) != 1 or workers[0]['state'] != 'SUCCESS':
                raise RuntimeError('每个成功子流程必须有一个真实成功Worker')
            worker = workers[0]
            if worker.get('workflowInstanceId') != child_id or str(worker.get('projectCode')) != project:
                raise RuntimeError('原生Worker项目或工作流实例不匹配真实子身份')
            item = {'instanceId': child_id, 'definitionCode': str(child['workflowDefinitionCode']), 'state': child['state'],
                    'startTime': child['startTime'], 'endTime': child['endTime'], 'workerTaskId': worker['id'],
                    'workerType': worker['taskType'], 'workerStartTime': worker['startTime'], 'workerEndTime': worker['endTime'],
                    'reverseParentVerified': True}
            if worker['taskType'] == 'SQL':
                raw = self.r.ds('/log/detail', {'taskInstanceId': worker['id'], 'skipLineNum': 0, 'limit': 1000})['message']
                counts = re.findall(r'execution_row_count["\s:=]+(\d+)', raw, re.I)
                if not counts:
                    raise RuntimeError('真实SQL Worker日志缺少执行汇总')
                item['executionRowCount'] = int(counts[-1])
                item['rawLogSha256'] = hashlib.sha256(raw.encode()).hexdigest()
                item['rawLogSaved'] = False
                item['secretLeakDetected'] = any(value and value in raw for value in self.r.secret_values)
                if item['secretLeakDetected']:
                    raise RuntimeError('原生SQL日志含私密值，未公开原文')
            ds_children.append(item)
        if len({item['instanceId'] for item in ds_children}) != 2 or len({item['definitionCode'] for item in ds_children}) != 2 or {item['workerType'] for item in ds_children} != {'DATAX', 'SQL'}:
            raise RuntimeError('成功必须包含两个不同的DATAX/SQL子实例和定义，拒绝空集合通过')
        integration = next(item for item in ds_children if item['workerType'] == 'DATAX')
        development = next(item for item in ds_children if item['workerType'] == 'SQL')
        if development['startTime'] < integration['endTime']:
            raise RuntimeError('SQL子开始早于DataX成功结束，图依赖不成立')
        logs = []
        for item in [row, *children]:
            identity = {key: item[key] for key in ['id', 'kind', 'origin']}
            viewed = self.r.api('/dataforge/ops-instance/log', query=identity)['message']
            downloaded = self.r.api('/dataforge/ops-instance/log-download', query=identity, raw=True).decode()
            leak = any(value and (value in viewed or value in downloaded) for value in self.r.secret_values)
            if leak or viewed != downloaded:
                raise RuntimeError('平台查看/下载日志秘密扫描或字节一致性失败')
            file = policy + '-' + str(record) + '-' + item['kind'].lower() + '.txt'
            (self.output / file).write_text(self.r.scrub(downloaded))
            logs.append({'identity': identity, 'file': file, 'sha256': hashlib.sha256(downloaded.encode()).hexdigest(),
                         'bytes': len(downloaded.encode()), 'viewDownloadEqual': True, 'secretLeakDetected': False})
        result = {'platform': self.brief(row), 'parentInstanceId': parent['id'], 'parentDefinitionCode': str(parent['workflowDefinitionCode']),
                  'parentStartTime': parent['startTime'], 'parentEndTime': parent['endTime'], 'dsChildren': ds_children,
                  'globalParams': json.loads(parent.get('globalParams') or '[]'), 'logs': logs, 'dependencyVerified': True}
        return result

    def assert_multiset(self, policy, runs):
        expected = [row + [runs] for row in self.r.fixture['expectedRowsPerRun']] if runs else []
        actual = self.r.multiset(policy)
        totals = self.r.totals(policy)
        if actual != expected or totals != {'count': runs * 3, 'sum': runs * 31}:
            raise RuntimeError('真实目标多重集/累计count/sum不符：' + policy)
        return {'rowsWithMultiplicity': actual, 'totals': totals, 'totalDataxCopies': runs}

    def records(self, policy):
        job = self.tasks['policies'][policy]['jobId']
        page = self.r.api('/dataforge/job/records', query={'taskId': job, 'pageNo': 1, 'pageSize': 100})
        if page['total'] > 100:
            raise RuntimeError('本次隔离记录超出有界验收范围')
        return page['list']

    def no_worker(self, record, state):
        row = self.row(record)
        if row['queueState'] != state or row.get('dsDefinitionCode') or row.get('dsInstanceId') or row.get('children'):
            raise RuntimeError('未执行决策出现DS或子记录：' + str(record))
        name = 'dh_job_run_' + str(record)
        page = self.r.ds('/projects/' + self.r.private['ds']['projectCode'] + '/workflow-definition',
                         {'searchVal': name, 'pageNo': 1, 'pageSize': 100})
        if page['total'] != 0 or page.get('totalList'):
            raise RuntimeError('未执行决策却发现专属DS定义')
        identity = {'kind': 'JOB', 'origin': 'PLATFORM', 'id': record}
        viewed = self.r.api('/dataforge/ops-instance/log', query=identity)['message']
        downloaded = self.r.api('/dataforge/ops-instance/log-download', query=identity, raw=True).decode()
        if viewed != downloaded or not row.get('queueReason') or row['queueReason'] not in viewed:
            raise RuntimeError('未执行决策原因与真实日志/下载不一致')
        if any(value and value in viewed for value in self.r.secret_values):
            raise RuntimeError('决策日志含私密值')
        return {'record': self.brief(row), 'actualDsDefinitionCount': 0, 'workerCount': 0,
                'viewDownloadEqual': True, 'reasonPresent': True}

    def cron_case(self):
        policy = 'cron'
        task = self.tasks['policies'][policy]
        if self.r.totals(policy)['count'] or self.records(policy):
            raise RuntimeError('Cron不是空白新fixture，拒绝清空或伪装首次周期')
        self.report['cases'][policy] = {'records': {}, 'observations': []}
        job = self.r.api('/dataforge/job/get', query={'id': task['jobId']})
        if job['strategy'] != 'SERIAL_WAIT' or job['scheduleStatus'] != 0:
            raise RuntimeError('混合Cron验收必须是未启周期的SERIAL_WAIT图')
        cron = '0/30 * * * * ?'
        self.r.api('/dataforge/job/schedule', 'PUT', {'id': job['id'], 'revision': job['revision'], 'cronExpression': cron, 'scheduleStatus': 1})
        enabled = self.r.api('/dataforge/job/get', query={'id': job['id']})
        self.report['cases'][policy]['enabledGeneration'] = enabled['scheduleGeneration']
        self.report['cases'][policy]['cronExpression'] = cron
        self.save()
        a = b = None
        try:
            with self.r.lock(policy, seconds=150):
                first = self.wait(lambda: self.records(policy), bool, '首个真实Quartz槽', 45)
                first = sorted(first, key=lambda row: row['scheduledTime'])
                a = first[0]['id']
                self.blocked(policy, a)
                two = self.wait(lambda: self.records(policy), lambda rows: len(rows) >= 2, '第二个真实Quartz槽', 45)
                two = sorted(two, key=lambda row: row['scheduledTime'])
                a, b = two[0]['id'], two[1]['id']
                if any(row['executionType'] != 'SCHEDULE' or not row.get('scheduledTime') for row in two):
                    raise RuntimeError('不能把手动运行或创建时间当作Cron槽证据')
                queued = self.row(b)
                if queued['queueState'] != 'QUEUED' or queued.get('dsInstanceId'):
                    raise RuntimeError('第二个混合周期槽未在同一串行控制面排队')
                self.announce_window(policy, [a, b], {'cronExpression': cron, 'generation': enabled['scheduleGeneration']})
                self.ui_hold(policy, seconds=15)
            self.wait(lambda: self.row(b), lambda row: row['queueState'] != 'QUEUED', '第二槽被正式领取', 90)
        finally:
            latest = self.r.api('/dataforge/job/get', query={'id': job['id']})
            if latest['scheduleStatus'] != 0:
                self.r.api('/dataforge/job/schedule', 'PUT', {'id': latest['id'], 'revision': latest['revision'],
                                                            'cronExpression': cron, 'scheduleStatus': 0})
        closed_records = self.records(policy)
        closed_ids = {str(row['id']) for row in closed_records}
        proofs = [self.success(policy, record) for record in [a, b]]
        slots = []
        for proof in proofs:
            row = proof['platform']
            if row['executionType'] != 'SCHEDULE' or not row['scheduledTime']:
                raise RuntimeError('成功父缺少真实计划来源')
            request = next(item['value'] for item in proof['globalParams'] if item['prop'] == 'datahub_job_request')
            match = re.fullmatch(r'CRON_' + str(job['id']) + r'_(\d+)_(\d+)', request)
            when = dt.datetime.fromisoformat(row['scheduledTime']).replace(tzinfo=ZoneInfo('Asia/Shanghai'))
            if not match or int(match[1]) != enabled['scheduleGeneration'] or int(match[2]) != int(when.timestamp()) or when.second not in [0, 30]:
                raise RuntimeError('Cron请求、计划时刻、冻结代次或表达式不一致')
            slots.append({'scheduledTime': row['scheduledTime'], 'generation': int(match[1]), 'requestId': request})
        if len({slot['scheduledTime'] for slot in slots}) != 2 or proofs[1]['parentStartTime'] < proofs[0]['parentEndTime']:
            raise RuntimeError('Cron槽不唯一或父执行重叠')
        counts = [next(child['executionRowCount'] for child in proof['dsChildren'] if child['workerType'] == 'SQL') for proof in proofs]
        if counts != [3, 6]:
            raise RuntimeError('真实Cron两槽SQL累计结果不是3/6')
        cancelled = [self.no_worker(row['id'], 'CANCELLED') for row in closed_records if row['id'] not in [a, b]]
        time.sleep(35)
        after = self.records(policy)
        final = self.r.api('/dataforge/job/get', query={'id': job['id']})
        if final['scheduleStatus'] != 0 or final.get('nextRunTime') or {str(row['id']) for row in after} != closed_ids:
            raise RuntimeError('关闭后跨过新槽仍出现新派发或游标未清空')
        self.report['cases'][policy]['result'] = {'validation': 'PASS', 'completeSuccessfulParents': 2, 'proofs': proofs,
            'realQuartzSlots': slots, 'sqlCounts': counts, 'cancelledUnstartedSlotsAtClose': cancelled,
            'disabledAndObservedBeyondNextSlot': True, 'target': self.assert_multiset(policy, 2), 'manualRunUsed': False}
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'recordIds': [a, b], 'targetRows': 6}), flush=True)

    def discard_case(self):
        policy = 'discard'
        if self.r.totals(policy)['count'] or self.records(policy):
            raise RuntimeError('DISCARD需要新空表与未执行的新fixture')
        with self.r.lock(policy, seconds=150):
            a = self.submit(policy, 'A')
            blocked = self.blocked(policy, a)
            self.announce_window(policy, [a], {'uiRunOnceAuthorized': True, 'blocked': blocked})
            rows = self.wait(lambda: self.records(policy),
                             lambda values: any(row.get('queueState') == 'DISCARDED' for row in values),
                             'UI真实忙时B审计', 50)
            discarded = [row for row in rows if row.get('queueState') == 'DISCARDED']
            if len(discarded) != 1 or len(rows) != 2:
                raise RuntimeError('仅授权UI一次忙时请求，实际记录数不符')
            b = discarded[0]['id']
            self.report['cases'][policy]['records']['B'] = {'recordId': b, 'source': 'actual UI POST /job/run'}
            decision = self.no_worker(b, 'DISCARDED')
            latest = self.r.api('/dataforge/job/get', query={'id': self.tasks['policies'][policy]['jobId']})
            if latest['latestRecord']['id'] != b or not latest['hasActiveRuns'] or latest['activeRunCount'] != 1:
                raise RuntimeError('忙时latest抛弃审计遮住了原活动运行')
            self.report['cases'][policy]['busyLatest'] = {'latestRecordId': b, 'hasActiveRuns': latest['hasActiveRuns'],
                                                         'activeRunCount': latest['activeRunCount']}
            self.save()
            self.ui_hold(policy, seconds=10)
        first = self.success(policy, a)
        c = self.submit(policy, 'C')
        third = self.success(policy, c)
        latest = self.r.api('/dataforge/job/get', query={'id': self.tasks['policies'][policy]['jobId']})
        if latest['latestRecord']['id'] != c or latest['latestRecord']['status'] != 'SUCCESS' or latest['hasActiveRuns']:
            raise RuntimeError('闲时C成功后latest仍被旧B空startTime审计覆盖')
        counts = [next(child['executionRowCount'] for child in proof['dsChildren'] if child['workerType'] == 'SQL') for proof in [first, third]]
        if counts != [3, 6]:
            raise RuntimeError('DISCARD忙时抛弃后闲时恢复的真实SQL计数不符')
        self.report['cases'][policy]['result'] = {'validation': 'PASS', 'completeSuccessfulParents': 2,
            'proofs': [first, third], 'discarded': decision, 'discardedStillHasNoWorker': self.no_worker(b, 'DISCARDED'),
            'idleRecoveryRecordId': c, 'latestSuccessfulRecordId': c, 'sqlCounts': counts,
            'target': self.assert_multiset(policy, 2)}
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'successfulRecords': [a, c], 'discardedRecord': b, 'targetRows': 6}), flush=True)

    def priority_case(self):
        policy = 'priority'
        if self.r.totals(policy)['count'] or self.records(policy):
            raise RuntimeError('PRIORITY需要新空表/新fixture')
        with self.r.lock(policy, seconds=180):
            a = self.submit(policy, 'A')
            blocked = self.blocked(policy, a)
            old_pids = [value['pid'] for value in blocked['writerWaiters']]
            b = self.submit(policy, 'B')
            c = self.submit(policy, 'C')
            queued = self.observe(policy, [a, b, c], '真实旧A停止请求与B/C优先取代')
            superseded = self.no_worker(b, 'SUPERSEDED')
            self.announce_window(policy, [a, b, c], {'oldWriterPids': old_pids, 'requestOrder': ['A', 'B', 'C']})
            old = self.wait(lambda: self.row(a), lambda row: row['status'] in ['STOPPED', 'FAILURE'], '优先旧A真实退出', 45)
            project = self.r.private['ds']['projectCode']
            prefix = '/projects/' + project + '/workflow-instances'
            parent = self.r.ds(prefix + '/' + str(old['dsInstanceId']))
            if parent['state'] not in ['STOP', 'KILL', 'FAILURE']:
                raise RuntimeError('旧父只有控制回执，尚未真实终态')
            parent_tasks = self.r.ds(prefix + '/' + str(parent['id']) + '/tasks').get('taskList', [])
            old_children = []
            for task in parent_tasks:
                child_id = self.r.ds(prefix + '/query-sub-by-parent', {'taskId': task['id']}).get('subWorkflowInstanceId')
                if not child_id:
                    continue
                child = self.r.ds(prefix + '/' + str(child_id))
                workers = self.r.ds(prefix + '/' + str(child_id) + '/tasks').get('taskList', [])
                if child['state'] not in ['STOP', 'KILL', 'FAILURE', 'SUCCESS'] or any(worker['state'] not in ['STOP', 'KILL', 'FAILURE', 'SUCCESS'] for worker in workers):
                    raise RuntimeError('旧A子/Worker没有退出，不能仅凭父回执晋级')
                old_children.append({'instanceId': child_id, 'state': child['state'],
                                     'workers': [{key: worker[key] for key in ['id', 'taskType', 'state']} for worker in workers]})
            if not old_children or not any(item['workers'] for item in old_children):
                raise RuntimeError('旧A缺少真实执行过的Worker，不能用空集合证明停止')
            try:
                self.wait(lambda: [pid for pid in old_pids if self.r.writer_pid_exists(pid)], lambda values: not values,
                          '旧writer数据库backend实际退出', 10)
            except RuntimeError:
                self.r.save('priority-stop-boundary.json', {'recordId': a, 'oldWriterPids': old_pids,
                    'rootCause': 'DS终态后旧writer连接仍存在，未释放测试表锁，不能声称优先通过'})
                print(json.dumps({'event': 'STOP_BOUNDARY', 'policy': policy, 'oldRecordId': a}), flush=True)
                # 仅停止本fixture作业并保留锁至连接真正退出，不用PG强杀替代受管停止。
                self.r.api('/dataforge/job/stop', 'POST', query={'id': self.tasks['policies'][policy]['jobId']})
                self.wait(lambda: self.r.writer_waiters(policy), lambda values: not values,
                          '异常清理时等实际writer锁等待退出', 130)
                raise RuntimeError('优先停止的外部连接未及时退出，本次不通过，原审计保留')
            latest_blocked = self.blocked(policy, c)
            if self.r.totals(policy) != {'count': 0, 'sum': 0}:
                raise RuntimeError('释放锁前已有旧INSERT迟到写入，优先策略不能通过')
            self.report['cases'][policy]['oldExitProof'] = {'record': self.brief(old), 'dsParentState': parent['state'],
                'dsParentEndTime': parent['endTime'], 'children': old_children, 'oldWriterPids': old_pids,
                'oldWriterBackendsGoneBeforeUnlock': True, 'manualPgTerminateUsed': False, 'latestBlocked': latest_blocked}
            self.save()
            self.ui_hold(policy, seconds=15)
        latest = self.success(policy, c)
        if latest['parentStartTime'] < parent['endTime']:
            raise RuntimeError('最新C在旧A父真实退出前启动')
        count = next(child['executionRowCount'] for child in latest['dsChildren'] if child['workerType'] == 'SQL')
        if count != 3:
            raise RuntimeError('优先只应由最新C完整写入3行')
        self.report['cases'][policy]['result'] = {'validation': 'PASS', 'completeSuccessfulParents': 1,
            'latestSuccessfulRecordId': c, 'proof': latest, 'superseded': superseded,
            'oldExitProof': self.report['cases'][policy]['oldExitProof'], 'target': self.assert_multiset(policy, 1)}
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'stopped': a, 'superseded': b, 'success': c, 'targetRows': 3}), flush=True)

    def parallel_case(self, retained=False):
        policy = 'parallel'
        before = self.r.totals(policy)
        if not retained and (before['count'] or self.records(policy)):
            raise RuntimeError('PARALLEL需要新空表/新fixture')
        if before['count'] % 3 or before['sum'] != before['count'] // 3 * 31:
            raise RuntimeError('保留数据必须符合源多重集')
        copies = before['count'] // 3
        self.assert_multiset(policy, copies)
        job = self.r.api('/dataforge/job/get', query={'id': self.tasks['policies'][policy]['jobId']})
        if job['hasActiveRuns'] or job['scheduleStatus']:
            raise RuntimeError('前一真实运行或周期尚未结束')
        with self.r.lock(policy, seconds=150):
            a = self.submit(policy, self.attempt + '_A' if retained else 'A')
            self.blocked(policy, a)
            b = self.submit(policy, self.attempt + '_B' if retained else 'B')
            rejected = self.wait(lambda: self.row(b), lambda row: row['status'] in ['SUCCESS', 'FAILURE', 'STOPPED'],
                                 '共享Dev的第二并行请求明确审计', 30)
            if rejected['status'] != 'FAILURE' or rejected['queueState'] != 'REJECTED':
                raise RuntimeError('共享Dev单活未保留，或第二请求未明确失败审计')
            if retained and ('任务正在运行中' not in rejected['queueReason'] or 'DuplicateKeyException' in rejected['queueReason']):
                raise RuntimeError('资源忙必须使用业务原因，不能仅显示数据库异常类型')
            decision = self.no_worker(b, 'REJECTED')
            if self.row(a)['status'] != 'RUNNING':
                raise RuntimeError('第二并行准备失败误停原运行')
            self.announce_window(policy, [a, b], {'sharedDevelopmentSingleActivePreserved': True})
            self.ui_hold(policy, seconds=10)
        proof = self.success(policy, a)
        count = next(child['executionRowCount'] for child in proof['dsChildren'] if child['workerType'] == 'SQL')
        if count != before['count'] + 3:
            raise RuntimeError('共享Dev的第二并行请求发生额外写入')
        previous = self.report['cases'][policy].get('result')
        if previous:
            self.report['cases'][policy].setdefault('previousResults', []).append(previous)
        self.report['cases'][policy]['result'] = {'validation': 'PASS', 'completeSuccessfulParents': 1,
            'proof': proof, 'rejectedBusySecond': decision, 'sharedDevelopmentSingleActivePreserved': True,
            'claimsSharedDevelopmentConcurrentExecution': False, 'targetBefore': before,
            'target': self.assert_multiset(policy, copies + 1)}
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'success': a, 'rejected': b, 'newRows': 3, 'targetRows': before['count'] + 3}), flush=True)

    def priority_burst_case(self):
        from prepare_coordinator import XxlAdmin
        policy = 'priority'
        if self.r.totals(policy) != {'count': 3, 'sum': 31}:
            raise RuntimeError('第二轮必须保留首轮C真实写入的3行，不清空或复造')
        admin = XxlAdmin(self.r.private['xxl']['adminAddress'], os.environ['XXL_POLICY_ADMIN_USER'], os.environ['XXL_POLICY_ADMIN_PASSWORD'])
        coordinator = self.r.private['xxl']['coordinatorJobId']
        group = self.r.private['xxl']['groupId']
        if coordinator != 4 or group != 3:
            raise RuntimeError('只允许暂停本次隔离协调器job4/group3')
        self.wait(lambda: (self.output / 'ui-priority-browser-closed.done').exists(), bool, 'UI退出被动对账窗口', 30)
        with self.r.lock(policy, seconds=180):
            a = self.submit(policy, 'D')
            blocked = self.blocked(policy, a)
            old_pids = [item['pid'] for item in blocked['writerWaiters']]
            admin.checked('/jobinfo/stop', {'id': coordinator})
            try:
                self.wait(lambda: admin.post('/joblog/pageList', {'jobGroup': group, 'jobId': coordinator, 'logStatus': 0, 'start': 0, 'length': 20})['data'],
                          lambda rows: not any(row['triggerCode'] == 200 and row['handleCode'] == 0 for row in rows),
                          '隔离协调器当前tick真正结束', 30)
                # 两个真实POST之间不读取作业，也不伪造队列；避免旁观者提前推进旧父对账。
                b = self.submit(policy, 'E')
                c = self.submit(policy, 'F')
            finally:
                admin.checked('/jobinfo/start', {'id': coordinator})
            decision = self.no_worker(b, 'SUPERSEDED')
            self.observe(policy, [a, b, c], '第二轮真正QUEUED取代与受管停止')
            old = self.wait(lambda: self.row(a), lambda row: row['status'] in ['STOPPED', 'FAILURE'], '第二轮旧父真实退出', 45)
            prefix = '/projects/' + self.r.private['ds']['projectCode'] + '/workflow-instances'
            parent = self.r.ds(prefix + '/' + str(old['dsInstanceId']))
            if parent['state'] not in ['STOP', 'KILL', 'FAILURE']:
                raise RuntimeError('旧父尚未真实退出')
            self.wait(lambda: [pid for pid in old_pids if self.r.writer_pid_exists(pid)], lambda rows: not rows,
                      '第二轮旧writer实际数据库连接退出', 10)
            current = self.blocked(policy, c)
            if self.r.totals(policy) != {'count': 3, 'sum': 31}:
                raise RuntimeError('释放锁前旧writer迟到写入，不能通过')
            window = {'policy': 'priority-burst', 'jobId': 7, 'recordIds': [a, b, c],
                      'records': [self.brief(self.row(value)) for value in [a, b, c]], 'oldWriterPids': old_pids,
                      'oldWriterBackendsGone': True, 'targetBeforeUnlock': self.r.totals(policy), 'latestBlocked': current}
            self.r.save('window-priority-burst.json', window)
            self.report['cases'][policy]['coordinatorPause'] = {'groupId': group, 'jobId': coordinator,
                'officialStopAndResume': True, 'currentTickFinished': True, 'oldJob2OrMainJob5Touched': False}
            self.save()
            print(json.dumps({'event': 'WINDOW_READY', 'policy': 'priority-burst', 'jobId': 7, 'recordIds': [a, b, c]}), flush=True)
            self.ui_hold('priority-burst', seconds=20)
        proof = self.success(policy, c)
        if proof['parentStartTime'] < parent['endTime']:
            raise RuntimeError('最新F启动早于旧D真实退出')
        count = next(item['executionRowCount'] for item in proof['dsChildren'] if item['workerType'] == 'SQL')
        if count != 6:
            raise RuntimeError('第二轮必须仅新增F的3行，总计6')
        self.report['cases'][policy]['result'] = {'validation': 'PASS_WITH_RETAINED_FIRST_RACE',
            'completeSuccessfulParents': 2, 'firstSuccessfulRecordId': 18, 'secondSuccessfulRecordId': c,
            'proof': proof, 'superseded': decision, 'oldStopped': self.brief(old), 'oldDsEndTime': parent['endTime'],
            'oldWriterPids': old_pids, 'oldWriterBackendsGoneBeforeUnlock': True, 'manualPgTerminateUsed': False,
            'target': self.assert_multiset(policy, 2), 'firstRaceEvidence': 'priority-first-race-boundary.json'}
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'stopped': a, 'superseded': b, 'success': c, 'targetRows': 6}), flush=True)

    def priority_retained_case(self):
        """保留历史数据，用独立请求验证抢占，绝不清表或覆盖旧验收记录。"""
        from prepare_coordinator import XxlAdmin
        policy = 'priority'
        job = self.tasks['policies'][policy]['jobId']
        current = self.r.api('/dataforge/job/get', query={'id': job})
        if current['hasActiveRuns'] or current['scheduleStatus']:
            raise RuntimeError('须先确认旧运行真实终态且业务周期关闭')
        before = self.r.totals(policy)
        if before['count'] % 3 or before['sum'] != before['count'] // 3 * 31:
            raise RuntimeError('既有目标数据不符合可核对的完整源多重集')
        copies = before['count'] // 3
        self.assert_multiset(policy, copies)
        admin = XxlAdmin(self.r.private['xxl']['adminAddress'], os.environ['XXL_POLICY_ADMIN_USER'], os.environ['XXL_POLICY_ADMIN_PASSWORD'])
        coordinator = self.r.private['xxl']['coordinatorJobId']
        group = self.r.private['xxl']['groupId']
        if coordinator != 4 or group != 3:
            raise RuntimeError('只允许本次隔离协调器job4/group3')
        with self.r.lock(policy, seconds=180):
            a = self.submit(policy, self.attempt + '_A')
            blocked = self.blocked(policy, a)
            old_pids = [item['pid'] for item in blocked['writerWaiters']]
            admin.checked('/jobinfo/stop', {'id': coordinator})
            try:
                self.wait(lambda: admin.post('/joblog/pageList', {'jobGroup': group, 'jobId': coordinator, 'logStatus': 0, 'start': 0, 'length': 20})['data'],
                          lambda rows: not any(row['triggerCode'] == 200 and row['handleCode'] == 0 for row in rows),
                          '隔离协调器当前tick结束', 30)
                b = self.submit(policy, self.attempt + '_B')
                c = self.submit(policy, self.attempt + '_C')
            finally:
                admin.checked('/jobinfo/start', {'id': coordinator})
            decision = self.no_worker(b, 'SUPERSEDED')
            old = self.wait(lambda: self.row(a), lambda row: row['status'] in ['STOPPED', 'FAILURE'], '旧父真实退出', 45)
            prefix = '/projects/' + str(self.r.private['ds']['projectCode']) + '/workflow-instances'
            parent = self.r.ds(prefix + '/' + str(old['dsInstanceId']))
            if parent['state'] not in ['STOP', 'KILL', 'FAILURE']:
                raise RuntimeError('旧DS父尚未退出')
            self.wait(lambda: [pid for pid in old_pids if self.r.writer_pid_exists(pid)], lambda rows: not rows,
                      '旧writer数据库连接真正退出', 10)
            latest = self.blocked(policy, c)
            if self.r.totals(policy) != before:
                raise RuntimeError('释放锁前出现迟到写入')
            window = {'policy': policy, 'jobId': job, 'recordIds': [a, b, c],
                      'records': [self.brief(self.row(value)) for value in [a, b, c]],
                      'oldWriterPids': old_pids, 'oldWriterBackendsGoneBeforeUnlock': True,
                      'targetBeforeUnlock': before, 'latestBlocked': latest}
            self.r.save('window-priority-retained.json', window)
            self.report['cases'][policy]['window'] = window
            self.save()
            print(json.dumps({'event': 'WINDOW_READY', 'policy': 'priority-retained', 'recordIds': [a, b, c]}), flush=True)
            self.ui_hold('priority-retained', seconds=15)
        proof = self.success(policy, c)
        if proof['parentStartTime'] < parent['endTime']:
            raise RuntimeError('新父开始早于旧父真实退出')
        count = next(item['executionRowCount'] for item in proof['dsChildren'] if item['workerType'] == 'SQL')
        if count != before['count'] + 3:
            raise RuntimeError('本轮必须仅追加最新请求的三行')
        self.report['cases'][policy]['result'] = {'validation': 'PASS', 'proof': proof, 'superseded': decision,
            'oldStopped': self.brief(old), 'oldDsEndTime': parent['endTime'], 'oldWriterPids': old_pids,
            'oldWriterBackendsGoneBeforeUnlock': True, 'manualPgTerminateUsed': False,
            'targetBefore': before, 'target': self.assert_multiset(policy, copies + 1),
            'coordinatorPause': {'jobId': coordinator, 'groupId': group, 'officialStopAndResume': True,
                                 'oldJob2OrMainJob5Touched': False}}
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'stopped': a, 'superseded': b, 'success': c,
                          'newRows': 3, 'retainedRows': before['count']}), flush=True)

    def wait_recovery_case(self):
        policy = 'wait'
        saved = self.report['cases'][policy]['records']
        a, b, c = [saved[label]['recordId'] for label in ['A', 'B', 'C']]
        with self.r.lock(policy, seconds=150):
            before = self.row(b)
            stopping = {'platformBefore': self.brief(before), 'targetBefore': self.r.totals(policy), 'stopAccepted': False}
            if before['status'] not in ['SUCCESS', 'FAILURE', 'STOPPED']:
                dev = next(child for child in before['children'] if child['kind'] == 'DEVELOPMENT')
                if dev['origin'] != 'PLATFORM' or dev['id'] != 3 or before['dsInstanceId'] != 6:
                    raise RuntimeError('旧B/Dev3/DS父6身份已变，不发送停止')
                prefix = '/projects/' + self.r.private['ds']['projectCode'] + '/workflow-instances'
                child = self.r.ds(prefix + '/8')
                reverse = self.r.ds(prefix + '/query-parent-by-sub', {'subId': 8}).get('parentWorkflowInstance')
                workers = self.r.ds(prefix + '/8/tasks').get('taskList', [])
                if reverse != 6 or child['state'] != 'SERIAL_WAIT' or workers:
                    raise RuntimeError('旧SQL子8已变化，不按旧窗口发送停止')
                stopping['oldSqlChild'] = {'id': 8, 'state': child['state'], 'reverseParent': reverse, 'workerCount': 0}
                stopping['stopAccepted'] = self.r.api('/dataforge/dev-record/stop', 'PUT', query={'id': 3}) is True
            d = self.submit(policy, 'D')
            e = self.submit(policy, 'E')
            self.wait(lambda: self.row(b), lambda row: row['status'] in ['SUCCESS', 'FAILURE', 'STOPPED'], '原B真实父子退出', 60)
            blocked = self.blocked(policy, c)
            states = [self.brief(self.row(record)) for record in [c, d, e]]
            if any(row['queueState'] != 'QUEUED' or row['dsInstanceId'] or row['children'] for row in states[1:]):
                raise RuntimeError('原C后的D/E未保持真实排队')
            window = {'policy': 'wait-recovery', 'jobId': self.tasks['policies'][policy]['jobId'],
                      'jobName': self.tasks['policies'][policy]['jobName'], 'recordIds': [c, d, e],
                      'records': states, 'blocked': blocked, 'oldPartialRecords': [a, b], 'targetBefore': self.r.totals(policy)}
            self.r.save('window-wait-recovery.json', window)
            self.report['cases'][policy]['oldBControlledConvergence'] = stopping
            self.save()
            print(json.dumps({'event': 'WINDOW_READY', 'policy': 'wait-recovery', 'jobId': 5, 'recordIds': [c, d, e]}), flush=True)
            self.ui_hold('wait-recovery', seconds=30)
        proofs = [self.success(policy, record) for record in [c, d, e]]
        old = self.row(b)
        if old['status'] == 'SUCCESS' or not old.get('endTime') or proofs[0]['parentStartTime'] < old['endTime']:
            raise RuntimeError('新C未等待旧B真实非成功终态')
        for previous, following in zip(proofs, proofs[1:]):
            if following['parentStartTime'] < previous['parentEndTime']:
                raise RuntimeError('C/D/E真实父流程重叠或非FIFO')
        counts = [next(child['executionRowCount'] for child in proof['dsChildren'] if child['workerType'] == 'SQL') for proof in proofs]
        if counts != [9, 12, 15]:
            raise RuntimeError('完整成功C/D/E的SQL累计结果必须为9/12/15')
        request = saved['D']['request']
        repeated = self.r.api('/dataforge/job/run', 'POST', request)
        if str(repeated) != str(d):
            raise RuntimeError('D幂等重复没有返回原记录')
        definition = proofs[1]['parentDefinitionCode']
        prefix = '/projects/' + self.r.private['ds']['projectCode'] + '/workflow-instances'
        time.sleep(6)
        instances = self.r.ds(prefix, {'workflowDefinitionCode': definition, 'pageNo': 1, 'pageSize': 10})
        if instances.get('total') != 1 or len(instances.get('totalList', [])) != 1:
            raise RuntimeError('重复request产生了额外DS父实例')
        result = {'validation': 'PASS_WITH_PRESERVED_RUNTIME_FAILURES', 'completeSuccessfulParents': 3,
                  'partialStoppedParents': [self.brief(self.row(a)), self.brief(self.row(b))],
                  'completeSuccessSequence': [c, d, e], 'proofs': proofs, 'sqlCounts': counts,
                  'target': self.assert_multiset(policy, 5), 'persistedOriginalQueueRecordC': c,
                  'sameRequestReturnedSameRecord': True, 'repeatedDefinitionInstanceCount': 1,
                  'businessRowsClearedOrRecreated': False}
        self.report['cases'][policy]['result'] = result
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': 'wait', 'fullSuccessRecords': [c, d, e], 'partialStoppedRecords': [a, b], 'targetRows': 15}), flush=True)

    def wait_case(self):
        policy = 'wait'
        if self.r.totals(policy)['count']:
            raise RuntimeError('WAIT目标不是空的新表，拒绝清空或重跑')
        with self.r.lock(policy, seconds=150):
            a = self.submit(policy, 'A')
            blocked = self.blocked(policy, a)
            b = self.submit(policy, 'B')
            c = self.submit(policy, 'C')
            snapshots = self.observe(policy, [a, b, c], 'A真实BOUND且B/C排队')
            if any(row['queueState'] != 'QUEUED' or row['dsInstanceId'] or row['children'] for row in snapshots[1:]):
                raise RuntimeError('B/C未保持未派发的真实队列状态')
            self.announce_window(policy, [a, b, c], {'blocked': blocked, 'backendRestartAllowed': True})
            self.ui_hold(policy, seconds=85, backend_restart=True)
            after = self.observe(policy, [a, b, c], '后端受控重启后原记录仍在')
            if after[0]['dsInstanceId'] != blocked['record']['dsInstanceId'] or any(row['queueState'] != 'QUEUED' or row['dsInstanceId'] for row in after[1:]):
                raise RuntimeError('重启改变原父身份或提前派发排队请求')
        proofs = [self.success(policy, record) for record in [a, b, c]]
        for previous, following in zip(proofs, proofs[1:]):
            if following['parentStartTime'] < previous['parentEndTime']:
                raise RuntimeError('WAIT真实父流程重叠或非FIFO')
        counts = [next(child['executionRowCount'] for child in proof['dsChildren'] if child['workerType'] == 'SQL') for proof in proofs]
        if counts != [3, 6, 9]:
            raise RuntimeError('WAIT各真实SQL累计输出不符合FIFO3/6/9')
        request = self.report['cases'][policy]['records']['B']['request']
        repeated = self.r.api('/dataforge/job/run', 'POST', request)
        if str(repeated) != str(b):
            raise RuntimeError('重复request未返回同一记录')
        result = {'validation': 'PASS', 'proofs': proofs, 'sqlCounts': counts, 'target': self.assert_multiset(policy, 3),
                  'sameRequestReturnedSameRecord': True, 'persistedAcrossBackendRestart': True}
        self.report['cases'][policy]['result'] = result
        self.save()
        print(json.dumps({'event': 'CASE_PASS', 'policy': policy, 'recordIds': [a, b, c], 'targetRows': 9}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--case', choices=['wait', 'wait-recovery', 'cron', 'discard', 'priority', 'priority-burst', 'priority-retained', 'parallel', 'parallel-retained'], required=True)
    parser.add_argument('--attempt', default='initial')
    args = parser.parse_args()
    runtime = Runtime(args.config, args.output_dir)
    suite = Suite(runtime, args.output_dir, args.attempt)
    if args.case == 'wait-recovery':
        suite.wait_recovery_case()
    elif args.case == 'cron':
        suite.cron_case()
    elif args.case == 'discard':
        suite.discard_case()
    elif args.case == 'priority':
        suite.priority_case()
    elif args.case == 'priority-burst':
        suite.priority_burst_case()
    elif args.case == 'priority-retained':
        suite.priority_retained_case()
    elif args.case == 'parallel':
        suite.parallel_case()
    elif args.case == 'parallel-retained':
        suite.parallel_case(retained=True)
    else:
        suite.wait_case()


if __name__ == '__main__':
    main()
