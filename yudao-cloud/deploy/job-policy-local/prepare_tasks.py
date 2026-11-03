#!/usr/bin/env python3
"""保存独立混合作业fixture；此脚本不触发执行、不启用周期。"""

import argparse
import json
from pathlib import Path

from policy_runtime import Runtime


STRATEGIES = {'cron': 'SERIAL_WAIT', 'wait': 'SERIAL_WAIT', 'discard': 'SERIAL_DISCARD',
              'priority': 'SERIAL_PRIORITY', 'parallel': 'PARALLEL'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    r = Runtime(args.config, args.output_dir)
    f = r.fixture
    state_path = args.output_dir / 'tasks.json'
    state = json.loads(state_path.read_text()) if state_path.exists() else {'suffix': f['suffix'], 'policies': {}}

    def remember(key, value):
        state[key] = value
        r.save('tasks.json', state)
        return value

    def create(key, path, body):
        return state.get(key) or remember(key, r.api(path, 'POST', body))

    label = 'codex_job_e2e_policy_' + f['suffix']
    integration_category = create('integrationCategoryId', '/dataforge/integration/category/create',
                                  {'parentId': 0, 'name': label, 'sort': 999, 'validFlag': True})
    dev_directory = create('devDirectoryId', '/dataforge/dev-dir/create',
                            {'parentId': 0, 'name': label, 'sort': 999, 'status': 0})
    job_category = create('jobCategoryId', '/dataforge/job/category/create',
                          {'parentId': 0, 'name': label, 'sort': 999})
    for policy, strategy in STRATEGIES.items():
        item = state['policies'].setdefault(policy, {})

        def set_item(key, value):
            item[key] = value
            r.save('tasks.json', state)
            return value

        integration_name = 'codex_job_e2e_' + policy + ('_幂等写入_' if f.get('upsertKeys') else '_集成追加_') + f['suffix']
        dev_name = 'codex_job_e2e_' + policy + '_只读核验_' + f['suffix']
        job_name = 'codex_job_e2e_' + policy + '_混合策略验收_' + f['suffix']
        integration = item.get('integrationTaskId') or set_item('integrationTaskId', r.api('/dataforge/integration/create', 'POST', {
            'mode': 'OFFLINE', 'categoryId': integration_category, 'name': integration_name, 'ownerId': 1,
            'status': 0, 'engine': 'DATAX', 'scheduler': 'DOLPHIN_SCHEDULER', 'workerGroup': r.private['ds']['workerGroup'],
            'retryCount': 0, 'retryInterval': 1, 'delayMinutes': 0, 'priority': 'MEDIUM', 'executionStrategy': 'PARALLEL',
            'engineConfig': {'jvmInitialMemory': 1024, 'jvmMaximumMemory': 1024, 'channel': 1,
                             'byteSpeed': 0, 'recordSpeed': 0, 'errorLimit': 0},
            'description': '按目标非空唯一键幂等写入，保留所有恢复日志' if f.get('upsertKeys') else '独立新表INSERT，每次真实执行追加3行；无清空/覆盖/重试，由平台Job策略控制排队'}))
        task = r.api('/dataforge/integration/get', query={'id': integration})
        if not task.get('dsDefinitionCode'):
            nodes = [
                {'id': 'read', 'type': 'etl-node', 'x': 200, 'y': 100, 'properties': {
                    'name': '表输入组件', 'componentCode': 'read-db', 'toolCode': 'read-db', 'toolType': 1,
                    'nodeJson': {'databaseId': f['platformConnections']['reader'], 'tableName': f['sourceTable'],
                                 'schemaName': 'public', 'readMode': 'FULL', 'ifSql': 0,
                                 'selectedColumns': ['id', 'name', 'amount']}}},
                {'id': 'write', 'type': 'etl-node', 'x': 200, 'y': 300, 'properties': {
                    'name': '表输出组件', 'componentCode': 'insert-db', 'toolCode': 'insert-db', 'toolType': 3,
                    'nodeJson': {'databaseId': f['platformConnections']['writer'], 'tableName': f['targets'][policy],
                                 'schemaName': 'public', 'dataSyncType': 2 if f.get('upsertKeys') else 1,
                                 'writeMode': 'UPSERT' if f.get('upsertKeys') else 'INSERT',
                                 **({'upsertKeys': f['upsertKeys']} if f.get('upsertKeys') else {}),
                                 'targetTruncate': False, 'batchSize': 1000,
                                 'fieldMappings': [{'source': key, 'target': key, 'enabled': True} for key in ['id', 'name', 'amount']]}}}]
            flow = {'nodes': nodes, 'edges': [{'id': 'read_write', 'sourceNodeId': 'read', 'targetNodeId': 'write'}]}
            payload = {'id': integration, 'revision': task['revision'], 'flowJson': json.dumps(flow, ensure_ascii=False), 'draft': False}
            check = r.api('/dataforge/integration/check', 'POST', payload)
            if not check.get('valid'):
                raise RuntimeError('真实集成图预检失败：' + json.dumps(check, ensure_ascii=False))
            task = r.api('/dataforge/integration/flow', 'PUT', payload)
            set_item('integrationDefinitionCode', str(task['dsDefinitionCode']))
        if task['status'] != 1:
            r.api('/dataforge/integration/status', 'PUT', {'id': integration, 'revision': task['revision'], 'status': 1})
        dev = item.get('developmentTaskId') or set_item('developmentTaskId', r.api('/dataforge/dev-task/create', 'POST', {
            'dirId': dev_directory, 'name': dev_name, 'ownerId': 1, 'executionUserId': 1, 'taskType': 'SQL',
            'scheduler': 'DOLPHIN_SCHEDULER', 'dbType': 'POSTGRESQL', 'databaseId': f['platformConnections']['query'],
            'content': 'SELECT id,name,amount FROM public."' + f['targets'][policy] + '";', 'timeoutSeconds': 120, 'maxRows': 5000 if f.get('upsertKeys') else 10,
            'executionConfig': {'priority': 'MEDIUM', 'workerGroup': r.private['ds']['workerGroup'], 'retryTimes': 0,
                                'retryIntervalMinutes': 1, 'delayMinutes': 0, 'sqlType': 'QUERY', 'localParams': []},
            'description': '冻结普通SQL单表只读版本；真实Worker汇总行数，独立角色核验累计count/sum'}))
        developer = r.api('/dataforge/dev-task/get', query={'id': dev})
        if f.get('upsertKeys') and developer.get('executionConfig', {}).get('workerGroup') != r.private['ds']['workerGroup']:
            update = {key: developer[key] for key in ['id', 'dirId', 'name', 'ownerId', 'executionUserId', 'taskType', 'scheduler', 'dbType', 'databaseId', 'content', 'timeoutSeconds', 'maxRows', 'executionConfig', 'description'] if key in developer}
            update['expectedRevision'] = developer['revision']
            update['executionConfig']['workerGroup'] = r.private['ds']['workerGroup']
            r.api('/dataforge/dev-task/update', 'PUT', update)
            developer = r.api('/dataforge/dev-task/get', query={'id': dev})
        if developer['status'] != 1:
            r.api('/dataforge/dev-task/update-status', 'PUT', query={'id': dev, 'status': 1})
        set_item('developmentVersionId', developer['currentVersionId'])
        flow = {'nodes': [{'id': 'integration', 'kind': 'INTEGRATION', 'taskId': str(integration), 'name': integration_name, 'x': 200, 'y': 100},
                          {'id': 'development', 'kind': 'DEVELOPMENT', 'taskId': str(dev), 'name': dev_name, 'x': 200, 'y': 320}],
                'edges': [{'source': 'integration', 'target': 'development'}]}
        job = item.get('jobId') or set_item('jobId', r.api('/dataforge/job/create', 'POST', {
            'categoryId': job_category, 'name': job_name, 'ownerId': 1, 'strategy': strategy,
            'description': '独立' + policy + ('幂等恢复验收；每键仅一行；保留故障与重放日志' if f.get('upsertKeys') else '真实混合作业验收；源3行，每次只追加；所有验收周期结束后关闭'),
            'flowJson': json.dumps(flow, ensure_ascii=False)}))
        check = r.api('/dataforge/job/check', 'POST', {'flowJson': json.dumps(flow, ensure_ascii=False)})
        if not check.get('valid') or not check.get('executable'):
            raise RuntimeError('真实混合图不可执行：' + json.dumps(check, ensure_ascii=False))
        current = r.api('/dataforge/job/get', query={'id': job})
        if current['strategy'] != strategy:
            if current.get('hasActiveRuns') or current['scheduleStatus'] != 0:
                raise RuntimeError('已有活动运行或周期，拒绝修改fixture策略')
            if current['status'] == 1:
                r.api('/dataforge/job/status', 'PUT', {'id': job, 'revision': current['revision'], 'status': 0})
                current = r.api('/dataforge/job/get', query={'id': job})
            update = {key: current[key] for key in ['id', 'revision', 'categoryId', 'name', 'ownerId', 'flowJson', 'description'] if key in current}
            update['strategy'] = strategy
            r.api('/dataforge/job/update', 'PUT', update)
            current = r.api('/dataforge/job/get', query={'id': job})
        if current['status'] != 1:
            r.api('/dataforge/job/status', 'PUT', {'id': job, 'revision': current['revision'], 'status': 1})
            current = r.api('/dataforge/job/get', query={'id': job})
        item.update({'jobName': job_name, 'integrationName': integration_name, 'developmentName': dev_name,
                     'jobRevision': current['revision'], 'strategy': strategy, 'scheduleStatus': current['scheduleStatus'],
                     'phase': 'READY'})
        if item['scheduleStatus'] != 0:
            raise RuntimeError('准备阶段不允许自动启用周期')
        r.save('tasks.json', state)
    print(json.dumps({'preparedPolicies': {key: {'jobId': value['jobId'], 'integrationTaskId': value['integrationTaskId'],
                                               'developmentTaskId': value['developmentTaskId'], 'strategy': value['strategy']}
                                          for key, value in state['policies'].items()}, 'workflowSubmitted': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
