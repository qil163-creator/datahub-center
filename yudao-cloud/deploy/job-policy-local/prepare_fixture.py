#!/usr/bin/env python3
"""为独立作业策略环境新建只追加的 PostgreSQL 测试数据，不触碰已有表。"""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
from zoneinfo import ZoneInfo


def secure_write(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if stat.S_IMODE(args.config.stat().st_mode) != 0o600:
        raise RuntimeError('私密配置必须为0600')
    private = json.loads(args.config.read_text())
    if private.get('dockerContext') != 'colima-job-policy':
        raise RuntimeError('仅允许本次独立Docker context，拒绝默认或旧环境')
    if private.get('fixture'):
        raise RuntimeError('已保留测试数据配置，拒绝重新建表或替换；需要新的验收请新建配置')
    suffix = dt.datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(2)
    prefix = 'codex_job_e2e_' + suffix
    roles = {kind: {'name': 'cjp_' + kind + '_' + suffix, 'password': secrets.token_urlsafe(32)}
             for kind in ['owner', 'reader', 'writer', 'query']}
    source = prefix + '_source'
    targets = {kind: prefix + '_' + kind for kind in ['cron', 'wait', 'discard', 'priority', 'parallel']}
    fixture = {'suffix': suffix, 'database': prefix, 'sourceTable': source, 'targets': targets,
               'expectedRowsPerRun': [[1, '  alice  ', 7], [2, 'Bob', 11], [3, "q'Data", 13]],
               'expectedCountPerRun': 3, 'expectedSumPerRun': 31, 'pgContainer': 'datahub-job-policy-postgres-1',
               'dockerContext': 'colima-job-policy', 'postgresPort': 55427, 'roles': roles}
    identifiers = [prefix, source, *targets.values(), *(role['name'] for role in roles.values())]
    if any(not re.fullmatch(r'[a-z][a-z0-9_]{1,62}', value) for value in identifiers):
        raise RuntimeError('生成的隔离标识符不满足白名单')
    private['fixture'] = fixture
    secure_write(args.config, private)
    command = ['docker', '--context', 'colima-job-policy', 'exec', '-i', fixture['pgContainer'],
               'psql', '-p', '55427', '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-q']
    # 口令只通过标准输入发送到新环境；不放入命令行、公开SQL或日志。
    setup = '\n'.join('CREATE ROLE ' + role['name'] + " LOGIN PASSWORD '" + role['password'] + "';"
                      for role in roles.values())
    setup += '\nCREATE DATABASE ' + prefix + ' OWNER ' + roles['owner']['name'] + ';\n'
    setup += 'REVOKE CONNECT ON DATABASE ' + prefix + ' FROM PUBLIC;\n'
    setup += 'GRANT CONNECT ON DATABASE ' + prefix + ' TO ' + ','.join(role['name'] for role in roles.values()) + ';\n'
    for kind in ['reader', 'query']:
        setup += 'ALTER ROLE ' + roles[kind]['name'] + ' SET default_transaction_read_only=on;\n'
    setup += 'ALTER ROLE ' + roles['writer']['name'] + " SET statement_timeout='120s';\n"
    setup += 'ALTER ROLE ' + roles['writer']['name'] + " SET client_connection_check_interval='1s';\n"
    setup += 'ALTER ROLE ' + roles['writer']['name'] + " SET idle_in_transaction_session_timeout='30s';\n"
    result = subprocess.run(command, input=setup, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('独立角色/数据库创建失败，私密配置已保留；未输出口令或私密SQL')
    ddl = 'CREATE TABLE public."' + source + '" (id bigint PRIMARY KEY, name text NOT NULL, amount bigint NOT NULL);\n'
    ddl += 'INSERT INTO public."' + source + '" (id,name,amount) VALUES (1,\'  alice  \',7),(2,\'Bob\',11),(3,\'q\'\'Data\',13);\n'
    for target in targets.values():
        # 来源ID不设唯一键，允许每次真实执行追加相同三条源数据；禁止清空或覆盖。
        ddl += 'CREATE TABLE public."' + target + '" (id bigint NOT NULL, name text NOT NULL, amount bigint NOT NULL);\n'
    grants = 'GRANT USAGE ON SCHEMA public TO ' + ','.join(role['name'] for role in roles.values()) + ';\n'
    grants += 'GRANT SELECT ON public."' + source + '" TO ' + roles['reader']['name'] + ';\n'
    for target in targets.values():
        grants += 'GRANT SELECT,INSERT ON public."' + target + '" TO ' + roles['writer']['name'] + ';\n'
        grants += 'GRANT SELECT ON public."' + target + '" TO ' + roles['query']['name'] + ';\n'
    command[command.index('-d') + 1] = prefix
    result = subprocess.run(command, input='SET ROLE ' + roles['owner']['name'] + ';\n' + ddl + grants,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('新隔离表创建失败；不会删除、重建或覆盖已有表')
    fixture['phase'] = 'DATABASE_PREPARED'
    private['fixture'] = fixture
    secure_write(args.config, private)
    public = {key: value for key, value in fixture.items() if key != 'roles'}
    public['target'] = {key: private['ds'][key] for key in ['server', 'projectCode', 'tenantCode', 'workerGroup', 'environmentCode']}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    secure_write(args.output_dir / 'fixture.json', public)
    (args.output_dir / '001_fixture_schema.sql').write_text(ddl)
    print(json.dumps({'phase': fixture['phase'], 'database': prefix, 'targetTableCount': len(targets),
                      'sourceRowCount': 3, 'overwriteOrTruncate': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
