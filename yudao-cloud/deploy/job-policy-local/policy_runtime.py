"""隔离策略验收的实际 HTTP、PostgreSQL 与短事务锁工具。"""

import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import time
import urllib.parse
import urllib.request


class Runtime:
    def __init__(self, config, output):
        self.config = Path(config)
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        if self.config.stat().st_mode & 0o777 != 0o600:
            raise RuntimeError('私密配置必须为0600')
        self.private = json.loads(self.config.read_text())
        if self.private.get('dockerContext') != 'colima-job-policy':
            raise RuntimeError('仅允许独立策略验收context')
        self.fixture = self.private['fixture']
        for value in [self.fixture['database'], self.fixture['sourceTable'], *self.fixture['targets'].values()]:
            if not re.fullmatch(r'codex_job_e2e_[a-z0-9_]{1,48}', value):
                raise RuntimeError('只允许独立codex_job_e2e测试数据库和表')
        self.secret_values = [self.private.get('accessToken', ''), self.private['ds']['token'],
                              self.private['ds']['databasePassword'], self.private['platform']['password'],
                              self.private['postgres']['adminPassword'], self.private['xxl']['accessToken']]
        self.secret_values += [value for role in self.fixture['roles'].values() for value in role.values()]
        self.secret_values += self.private.get('usedAccessTokens', [])

    def scrub(self, text):
        for value in sorted(self.secret_values, key=len, reverse=True):
            if value:
                text = text.replace(value, '[REDACTED]')
        return text

    def save(self, name, value):
        text = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
        if any(secret and secret in text for secret in self.secret_values):
            raise RuntimeError('证据包含实际私密值，拒绝保存且不输出命中内容')
        path = self.output / name
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as target:
            target.write(text)

    def request(self, base, path, method='GET', body=None, query=None, raw=False, scheduler=False):
        endpoint = urllib.parse.urlsplit(base)
        if endpoint.hostname != '127.0.0.1' or endpoint.scheme != 'http' or endpoint.username:
            raise RuntimeError('只允许当前本机受管验收端点')
        url = base.rstrip('/') + path
        if query:
            url += '?' + urllib.parse.urlencode(query)
        header = {'token': self.private['ds']['token']} if scheduler else {'Authorization': 'Bearer ' + self.private['accessToken']}
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(url, data=data, method=method, headers={'Content-Type': 'application/json', **header})
        with urllib.request.urlopen(req, timeout=45) as response:
            value = response.read(5 * 1024 * 1024 + 1)
        if len(value) > 5 * 1024 * 1024:
            raise RuntimeError('验收响应超过上限')
        if raw:
            return value
        result = json.loads(value)
        if result.get('code') != 0:
            raise RuntimeError(self.scrub(path + ' code=' + str(result.get('code')) + ' ' + str(result.get('msg'))))
        return result.get('data')

    def api(self, path, method='GET', body=None, query=None, raw=False):
        return self.request('http://127.0.0.1:50027/admin-api', path, method, body, query, raw)

    def ds(self, path, query=None):
        return self.request(self.private['ds']['server'], path, query=query, scheduler=True)

    def psql_command(self, kind='query'):
        role = self.fixture['roles'][kind]
        return ['docker', '--context', 'colima-job-policy', 'exec', '-i', self.fixture['pgContainer'],
                'sh', '-c', 'read -r PGPASSWORD; export PGPASSWORD; exec psql -h 127.0.0.1 -p 55427 -U "$1" -d "$2" -At -q -F "\t" -v ON_ERROR_STOP=1',
                'fixture', role['name'], self.fixture['database']]

    def sql(self, sql, kind='query'):
        result = subprocess.run(self.psql_command(kind), input=self.fixture['roles'][kind]['password'] + '\n' + sql,
                                text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError('独立fixture SQL失败，未输出私密参数或原始数据库错误')
        return result.stdout.strip()

    def totals(self, policy):
        table = self.fixture['targets'][policy]
        value = self.sql('SELECT count(*),coalesce(sum(amount),0) FROM public."' + table + '";\n').split('\t')
        return {'count': int(value[0]), 'sum': int(value[1])}

    def lock(self, policy, seconds=150):
        return TableLock(self, self.fixture['targets'][policy], seconds)

    def multiset(self, policy):
        table = self.fixture['targets'][policy]
        text = self.sql('SELECT id,name,amount,count(*) FROM public."' + table + '" GROUP BY id,name,amount ORDER BY id;\n')
        return [[int(row[0]), row[1], int(row[2]), int(row[3])]
                for line in text.splitlines() if line for row in [line.split('\t')]]

    def admin_query(self, sql):
        command = ['docker', '--context', 'colima-job-policy', 'exec', '-i', self.fixture['pgContainer'],
                   'psql', '-p', '55427', '-U', 'postgres', '-d', self.fixture['database'],
                   '-At', '-q', '-F', '\t', '-v', 'ON_ERROR_STOP=1']
        result = subprocess.run(command, input=sql, text=True, capture_output=True, timeout=15)
        if result.returncode:
            raise RuntimeError('只读fixture运行观测失败，未输出私密角色或SQL')
        return result.stdout.strip()

    def writer_waiters(self, policy):
        table = self.fixture['targets'][policy]
        role = self.fixture['roles']['writer']['name']
        if not re.fullmatch(r'cjp_writer_[a-z0-9_]+', role):
            raise RuntimeError('仅允许本次生成的fixture writer')
        sql = "SELECT pid,wait_event_type,wait_event,state FROM pg_stat_activity WHERE datname='" + self.fixture['database']
        sql += "' AND usename='" + role + "' AND position('" + table + "' in query)>0 AND wait_event_type='Lock';\n"
        text = self.admin_query(sql)
        return [{'pid': int(row[0]), 'waitEventType': row[1], 'waitEvent': row[2], 'state': row[3]}
                for line in text.splitlines() if line for row in [line.split('\t')]]

    def writer_pid_exists(self, pid):
        if type(pid) is not int or pid <= 0:
            raise RuntimeError('观测PID必须来自真实PG writer快照')
        return self.admin_query("SELECT count(*) FROM pg_stat_activity WHERE pid=" + str(pid)
                                + " AND datname='" + self.fixture['database'] + "';\n") != '0'


class TableLock:
    def __init__(self, runtime, table, seconds):
        if not 5 <= seconds <= 180:
            raise RuntimeError('短事务锁最长180秒')
        self.runtime = runtime
        self.table = table
        self.seconds = seconds
        self.process = None

    def __enter__(self):
        self.process = subprocess.Popen(self.runtime.psql_command('owner'), stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        sql = self.runtime.fixture['roles']['owner']['password'] + '\n'
        sql += "SET lock_timeout='5s'; SET idle_in_transaction_session_timeout='" + str(self.seconds) + "s';\n"
        sql += 'BEGIN; LOCK TABLE public."' + self.table + '" IN SHARE MODE; SELECT \'CODEX_JOB_E2E_LOCK_READY\';\n'
        self.process.stdin.write(sql)
        self.process.stdin.flush()
        deadline = time.monotonic() + 10
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            while time.monotonic() < deadline:
                if not selector.select(max(0, deadline - time.monotonic())):
                    break
                line = self.process.stdout.readline()
                if 'CODEX_JOB_E2E_LOCK_READY' in line:
                    return self
                if not line and self.process.poll() is not None:
                    break
        self.__exit__(None, None, None)
        raise RuntimeError('真实目标SHARE锁未在10秒内获得，未执行作业')

    def __exit__(self, *_):
        if self.process is None:
            return
        try:
            if self.process.poll() is None:
                self.process.stdin.write('ROLLBACK;\n\\q\n')
                self.process.stdin.flush()
                self.process.communicate(timeout=10)
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.communicate(timeout=5)
        finally:
            self.process = None
