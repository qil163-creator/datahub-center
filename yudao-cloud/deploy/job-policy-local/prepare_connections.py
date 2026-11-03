#!/usr/bin/env python3
"""通过真实管理接口准备隔离fixture连接；不提交或运行工作流。"""

import argparse
import json
from pathlib import Path

from policy_runtime import Runtime
from prepare_fixture import secure_write


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--scheduler-only', action='store_true')
    args = parser.parse_args()
    runtime = Runtime(args.config, args.output_dir)
    private = runtime.private
    fixture = private['fixture']
    scheduler_ids = fixture.setdefault('dsDatasources', {})
    platform_ids = fixture.setdefault('platformConnections', {})
    postgres_type = None
    if not args.scheduler_only:
        types = runtime.api('/dataforge/database-type/simple-list')
        postgres_type = next(item['id'] for item in types if item['typeCode'].upper() in ['POSTGRESQL', 'POSTGRES', 'PG'])
    for kind in ['reader', 'writer', 'query']:
        role = fixture['roles'][kind]
        name = 'codex_job_e2e_' + kind + '_' + fixture['suffix']
        if not scheduler_ids.get(kind):
            body = {'type': 'POSTGRESQL', 'name': name, 'note': '独立周期与队列真实验收，最小权限角色',
                    'host': '127.0.0.1', 'port': 55427, 'database': fixture['database'],
                    'userName': role['name'], 'password': role['password'], 'other': {}}
            runtime.request(private['ds']['server'], '/datasources/connect', method='POST', body=body, scheduler=True)
            created = runtime.request(private['ds']['server'], '/datasources', method='POST', body=body, scheduler=True)
            if type(created) is int:
                scheduler_ids[kind] = created
            elif isinstance(created, dict) and created.get('id'):
                scheduler_ids[kind] = created['id']
            else:
                rows = runtime.ds('/datasources/list', {'type': 'POSTGRESQL'})
                scheduler_ids[kind] = next(item['id'] for item in rows if item['name'] == name)
            secure_write(args.config, private)
        runtime.ds('/datasources/' + str(scheduler_ids[kind]) + '/connect-test')
        if not args.scheduler_only and not platform_ids.get(kind):
            platform_ids[kind] = runtime.api('/dataforge/database/create', method='POST', body={
                'name': name, 'databaseType': postgres_type, 'databaseIp': '127.0.0.1', 'databasePort': '55427',
                'databaseName': fixture['database'], 'databaseSchema': 'public', 'status': 0, 'usageType': 1,
                'userName': role['name'], 'password': role['password'], 'sort': 999,
                'remark': '独立可重复追加fixture，不允许任务角色DDL、清空或覆盖'})
            secure_write(args.config, private)
        if not args.scheduler_only:
            if runtime.api('/dataforge/database/test', query={'id': platform_ids[kind]}) is not True:
                raise RuntimeError('平台真实连接测试未通过')
    public = {key: value for key, value in fixture.items() if key != 'roles'}
    public['target'] = {key: private['ds'][key] for key in ['server', 'projectCode', 'tenantCode', 'workerGroup', 'environmentCode']}
    runtime.save('fixture.json', public)
    if not args.scheduler_only:
        # 引擎编号由管理员私密配置绑定，任务请求不接受调用方传入任意DS数据源。
        path = args.config.parent / 'managed-runtime.properties'
        text = path.read_text()
        for kind in ['reader', 'writer']:
            key = 'dataforge.integration.database-bindings[' + str(platform_ids[kind]) + ']'
            if key + '.ds-datasource-id=' not in text:
                text += key + '.ds-datasource-id=' + str(scheduler_ids[kind]) + '\n' + key + '.ds-type=POSTGRESQL\n'
        path.write_text(text)
        path.chmod(0o600)
    print(json.dumps({'schedulerConnectionIds': scheduler_ids, 'platformConnectionIds': platform_ids,
                      'workflowSubmitted': False, 'sourceCredentialsPrinted': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
