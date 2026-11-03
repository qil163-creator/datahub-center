#!/usr/bin/env python3
"""核验真实 DATAX→只读 SQL 作业；凭据仅从外部 0600 JSON 读取。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--tasks', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--api-base', default='http://127.0.0.1:50026/admin-api')
    parser.add_argument('--record-id')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--expected-state', choices=['SUCCESS', 'FAILURE'], default='SUCCESS')
    parser.add_argument('--idempotency-check', action='store_true')
    parser.add_argument('--timeout-seconds', type=int, default=360)
    args = parser.parse_args()
    if not args.record_id and not args.run:
        parser.error('只读核验必须指定 --record-id；真实触发需显式指定 --run')
    if stat.S_IMODE(args.config.stat().st_mode) != 0o600:
        raise RuntimeError('私密配置必须为0600，不读取宽松权限的凭据文件')
    private = json.loads(args.config.read_text())
    fixture = json.loads(args.fixture.read_text())
    tasks = json.loads(args.tasks.read_text())
    for base in [args.api_base, private['ds']['server']]:
        url = urllib.parse.urlsplit(base)
        if url.scheme != 'http' or url.hostname not in ['127.0.0.1', 'localhost'] or url.username or url.password:
            raise RuntimeError('此脚本只允许本机受管验收地址，拒绝向其他目标发送凭据')
    for name in [fixture['database'], fixture['targetTable']]:
        if not re.fullmatch(r'codex_job_e2e_[a-z0-9_]{1,48}', name):
            raise RuntimeError('只允许 codex_job_e2e 前缀的独立验收库表')
    if not 1 <= args.timeout_seconds <= 600:
        raise RuntimeError('等待时间必须在1至600秒内')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    values = [private['accessToken'], private['ds']['token'], *(role['password'] for role in private['roles'].values())]

    def scrub(text):
        for value in sorted(values, key=len, reverse=True):
            if value:
                text = text.replace(value, '[REDACTED]')
        return text

    def write(name, text):
        fd = os.open(args.output_dir / name, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            output.write(scrub(text))

    def request(base, path, query=None, method='GET', body=None, raw=False, scheduler=False):
        url = base.rstrip('/') + path
        if query:
            url += '?' + urllib.parse.urlencode(query)
        header = {'token': private['ds']['token']} if scheduler else {'Authorization': 'Bearer ' + private['accessToken']}
        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                     headers={'Content-Type': 'application/json', **header})
        with urllib.request.urlopen(req, timeout=60) as response:
            data = response.read(5 * 1024 * 1024 + 1)
        if len(data) > 5 * 1024 * 1024:
            raise RuntimeError('响应超过有界验收限制')
        if raw:
            return data
        result = json.loads(data)
        if result.get('code') != 0:
            raise RuntimeError(scrub(path + ' code=' + str(result.get('code')) + ' ' + str(result.get('msg'))))
        return result.get('data')

    def api(path, query=None, method='GET', body=None, raw=False):
        return request(args.api_base, path, query, method, body, raw)

    def ds(path, query=None):
        return request(private['ds']['server'], path, query, scheduler=True)

    def target_rows():
        role = private['roles']['query']
        cmd = ['docker', 'exec', '-i', fixture['pgContainer'], 'sh', '-c',
               'read -r PGPASSWORD; export PGPASSWORD; exec psql -h 127.0.0.1 -U "$1" -d "$2" -At -F "\t" -v ON_ERROR_STOP=1',
               'fixture', role['name'], fixture['database']]
        sql = 'SELECT id,name,amount FROM public."' + fixture['targetTable'] + '" ORDER BY id;\n'
        response = subprocess.run(cmd, input=role['password'] + '\n' + sql, text=True, capture_output=True)
        if response.returncode:
            raise RuntimeError('独立只读目标校验失败，未输出连接或 SQL 私密参数')
        return [[int(row[0]), row[1], int(row[2])] for line in response.stdout.splitlines()
                if line for row in [line.split('\t')]]

    before = target_rows()
    run_request = {'id': tasks['jobId'], 'revision': tasks['jobRevision'], 'requestId': tasks['requestId']}
    record_id = args.record_id
    if not record_id:
        if args.expected_state == 'SUCCESS' and before:
            raise RuntimeError('首次搬数前目标必须为空；不清空、不覆盖，请新建隔离样例')
        if args.expected_state == 'FAILURE' and before != fixture['expectedRows']:
            raise RuntimeError('只读失败样例必须保留成功验收的既有3行目标')
        record_id = api('/dataforge/job/run', method='POST', body=run_request)
    identity = {'kind': 'JOB', 'origin': 'PLATFORM', 'id': record_id}
    report = {'jobId': tasks['jobId'], 'jobName': tasks['jobName'], 'recordId': record_id,
              'expectedState': args.expected_state, 'phase': 'OBSERVING',
              'executionMode': 'READ_ONLY' if args.record_id else 'TRIGGER_AND_VERIFY',
              'triggeredNewRun': not bool(args.record_id), 'idempotencyCheckRequested': args.idempotency_check}
    deadline = time.monotonic() + args.timeout_seconds
    last = None
    while time.monotonic() < deadline:
        record = api('/dataforge/ops-instance/get', query=identity)
        if record['status'] != last:
            print(json.dumps({'recordId': record_id, 'status': record['status']}, ensure_ascii=False), flush=True)
            last = record['status']
        if record['status'] in ['SUCCESS', 'FAILURE', 'STOPPED']:
            break
        time.sleep(2)
    else:
        raise RuntimeError('有界等待超时；保留原 record/requestId，不重新投递')
    report['status'] = record['status']
    report['children'] = record.get('children') or []
    logs = []

    def log_evidence(item):
        key = {name: item[name] for name in ['kind', 'origin', 'id']}
        viewed = api('/dataforge/ops-instance/log', query=key)['message']
        downloaded = api('/dataforge/ops-instance/log-download', query=key, raw=True).decode('utf-8')
        leaked = any(value and (value in viewed or value in downloaded) for value in values)
        name = item['kind'].lower() + '-' + str(item['id']) + '.log'
        write(name, downloaded)
        logs.append({'identity': key, 'logFile': name, 'bytes': len(downloaded.encode()),
                     'sha256': hashlib.sha256(downloaded.encode()).hexdigest(), 'viewDownloadEqual': viewed == downloaded,
                     'secretLeakDetected': leaked, 'fixtureSuffixPresent': fixture['suffix'] in viewed})
        return viewed

    parent_log = log_evidence(identity)
    match = re.search(r'真实父实例\s+(\d+)', parent_log)
    if not match:
        raise RuntimeError('父日志缺少已核验 DS 实例编号')
    parent_id = int(match.group(1))
    project = str(fixture['target']['projectCode'])
    prefix = '/projects/' + project + '/workflow-instances'
    parent = ds(prefix + '/' + str(parent_id))
    parent_tasks = ds(prefix + '/' + str(parent_id) + '/tasks')
    parent_tasks = parent_tasks.get('taskList', []) if isinstance(parent_tasks, dict) else parent_tasks
    children = []
    for task in parent_tasks:
        if task.get('taskType') != 'SUB_WORKFLOW' or task.get('workflowInstanceId') != parent_id or str(task.get('projectCode')) != project:
            raise RuntimeError('DS父任务身份与受管父实例不一致')
        mapping = ds(prefix + '/query-sub-by-parent', {'taskId': task['id']})
        child_id = mapping.get('subWorkflowInstanceId')
        if not child_id:
            continue
        child = ds(prefix + '/' + str(child_id))
        reverse = ds(prefix + '/query-parent-by-sub', {'subId': child_id}).get('parentWorkflowInstance')
        if type(reverse) is not int or reverse != parent_id or str(child.get('projectCode')) != project:
            raise RuntimeError('DS 子流程双向父关系或受管项目不符')
        workers = ds(prefix + '/' + str(child_id) + '/tasks')
        workers = workers.get('taskList', []) if isinstance(workers, dict) else workers
        children.append({'instanceId': child_id, 'definitionCode': str(child['workflowDefinitionCode']),
                         'state': child['state'], 'startTime': child.get('startTime'), 'endTime': child.get('endTime'),
                         'parentTaskInstanceId': task['id'], 'parentTaskCode': str(task['taskCode']),
                         'workerTasks': [{key: worker.get(key) for key in ['id', 'taskType', 'state', 'host', 'startTime', 'endTime']} for worker in workers],
                         'reverseParentVerified': True})
    sql_workers = [worker for child in children for worker in child['workerTasks'] if worker['taskType'] == 'SQL']
    for item in report['children']:
        text = log_evidence(item)
        item['logMarker'] = ('execution_row_count' if item['kind'] == 'DEVELOPMENT' and len(sql_workers) == 1
                             else fixture.get('sourceTable', fixture['suffix']))
        item['logMarkerPresent'] = item['logMarker'] in text
    after = target_rows()
    report.update({'dsParentInstanceId': parent_id, 'dsParentDefinitionCode': str(parent['workflowDefinitionCode']),
                   'dsParentState': parent['state'], 'dsProjectCode': project, 'dsChildren': children,
                   'targetRows': after, 'targetRowCount': len(after), 'amountTotal': sum(row[2] for row in after),
                   'developmentSummary': '受管单表投影SELECT的execution_row_count=3；独立只读角色另核验目标三条完整行及sum=31',
                   'logs': logs, 'secretLeakDetected': any(item['secretLeakDetected'] for item in logs)})
    if args.expected_state == 'SUCCESS':
        valid = len(children) == 2 and len({child['definitionCode'] for child in children}) == 2
        valid &= len({child['instanceId'] for child in children}) == 2
        valid &= sorted(sorted(worker['taskType'] for worker in child['workerTasks']) for child in children) == [['DATAX'], ['SQL']]
        valid &= len(report['children']) == 2 and {child['kind'] for child in report['children']} == {'INTEGRATION', 'DEVELOPMENT'}
        valid &= {child['kind']: child['origin'] for child in report['children']} == {'INTEGRATION': 'MANAGED', 'DEVELOPMENT': 'PLATFORM'}
        valid &= all(child['status'] == 'SUCCESS' for child in report['children'])
        valid &= all(worker['state'] == 'SUCCESS' for child in children for worker in child['workerTasks'])
        integration = next((child for child in children if {worker['taskType'] for worker in child['workerTasks']} == {'DATAX'}), None)
        development = next((child for child in children if {worker['taskType'] for worker in child['workerTasks']} == {'SQL'}), None)
        ordered = bool(integration and development and integration['endTime'] and development['startTime']
                       and development['startTime'] >= integration['endTime'])
        report['dependencyTiming'] = {'integrationEndTime': integration and integration['endTime'],
                                      'developmentStartTime': development and development['startTime'], 'verified': ordered}
        valid &= ordered
        if len(sql_workers) != 1:
            valid = False
        else:
            # Platform log continuation redaction intentionally hides result rows.
            # Inspect the token-protected DS log only in memory, retaining one safe
            # aggregate number and a digest, never raw SQL/result/credential text.
            sql_log = ds('/log/detail', {'taskInstanceId': sql_workers[0]['id'], 'skipLineNum': 0, 'limit': 1000})['message']
            counts = re.findall(r'execution_row_count["\s:=]+(\d+)', sql_log, re.IGNORECASE)
            count = int(counts[-1]) if counts else None
            leak = any(value and value in sql_log for value in values)
            report['sqlWorkerSummary'] = {'workerTaskInstanceId': sql_workers[0]['id'], 'count': count,
                                          'rawWorkerLogSha256': hashlib.sha256(sql_log.encode()).hexdigest(),
                                          'rawWorkerLogBytes': len(sql_log.encode()), 'secretLeakDetected': leak,
                                          'rawLogSaved': False, 'verified': count == len(fixture['expectedRows'])}
            report['secretLeakDetected'] |= leak
            valid &= report['sqlWorkerSummary']['verified'] and not leak
    else:
        valid = len(children) == 1 and {worker['taskType'] for child in children for worker in child['workerTasks']} == {'SQL'}
        valid &= any(worker['state'] == 'FAILURE' for child in children for worker in child['workerTasks'])
        valid &= len(report['children']) == 1 and all(child['kind'] == 'DEVELOPMENT'
                 and child['origin'] == 'PLATFORM' and child['status'] == 'FAILURE' for child in report['children'])
    valid &= record['status'] == args.expected_state and parent['state'] == args.expected_state
    valid &= after == fixture['expectedRows'] and not report['secretLeakDetected']
    valid &= str(parent.get('projectCode')) == project and parent.get('id') == parent_id
    valid &= all(item['viewDownloadEqual'] for item in logs)
    valid &= all(item['logMarkerPresent'] for item in report['children'])
    if args.idempotency_check:
        repeated = api('/dataforge/job/run', method='POST', body=run_request)
        instances = ds(prefix, {'workflowDefinitionCode': parent['workflowDefinitionCode'], 'pageNo': 1, 'pageSize': 10})
        unchanged = target_rows() == after
        report['idempotency'] = {'sameRecord': str(repeated) == str(record_id), 'parentInstanceCount': len(instances.get('totalList', [])), 'targetUnchanged': unchanged}
        valid &= report['idempotency']['sameRecord'] and report['idempotency']['parentInstanceCount'] == 1 and unchanged
    report['validation'] = 'PASS' if valid else 'FAIL'
    report['phase'] = 'VERIFIED' if valid else 'ASSERTION_FAILED'
    write('report.json', json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['jobId', 'recordId', 'status', 'dsParentInstanceId', 'validation', 'secretLeakDetected']}, ensure_ascii=False))
    if not valid:
        raise RuntimeError('真实验收断言未通过，脱敏证据已保留；不推断成功或重投')


if __name__ == '__main__':
    main()
