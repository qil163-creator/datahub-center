#!/usr/bin/env python3
"""验证官方 TMQ 原生持续读取、checkpoint 恢复与凭据不进入作业状态；不使用 JDBC 全量替代。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl'


def command(arguments, data=None, timeout=45):
    result = subprocess.run(arguments, input=data, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('TDengine 验证子命令失败；退出码=' + str(result.returncode))
    return result.stdout


def prepare(directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name in ['root-password', 'consumer-password']:
        target = directory / name
        if not target.exists():
            target.write_text('Dh' + secrets.token_hex(16) + 'aZ9'); target.chmod(0o600)
    (directory / 'taos.cfg').write_text('fqdn datahub-native-tdengine\nfirstEp datahub-native-tdengine:6030\nnumOfCores 1\nsupportVnodes 8\ntotalMemoryKB 524288\nmonitor 0\ntimezone UTC\n')
    (directory / 'worker-secrets').mkdir(exist_ok=True, mode=0o700)
    write_secret(directory, (directory / 'consumer-password').read_text())


def write_secret(directory, password):
    path = directory / 'worker-secrets/tdengine-test'
    path.write_text('username=tmq_reader\npassword=' + password + '\nbootstrap.servers=datahub-native-tdengine:6041\ntopics=sensor_topic\ngroups=sensor_group\nuseSsl=false\n'); path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--fixture-root', type=Path, default=Path('/private/tmp/datahub-native-tdengine-fixture'))
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-tdengine-native-binding.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/native-tdengine-components-20261102.json')
    parser.add_argument('--prepare', action='store_true')
    options = parser.parse_args(); directory = options.fixture_root.resolve(); prepare(directory)
    if options.prepare:
        print(json.dumps({'fixtureRoot': str(directory), 'started': False})); return
    binding = json.loads(options.binding.read_text())
    if not binding.get('installed'):
        raise RuntimeError('请先安装经构建记录固定的受管 TDengine connector')
    root_password = (directory / 'root-password').read_text(); passwords = [(directory / 'consumer-password').read_text()]
    authorization = 'Basic ' + base64.b64encode(('root:' + root_password).encode()).decode()

    def sql(text):
        request = urllib.request.Request('http://127.0.0.1:16641/rest/sql', data=text.encode(), headers={'Authorization': authorization})
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
        if result.get('code') != 0:
            raise RuntimeError('TDengine SQL 验证失败；代码=' + str(result.get('code')))
        return result

    deadline = time.monotonic() + 60
    while True:
        try:
            version = sql('SELECT SERVER_VERSION()')['data'][0][0]; break
        except Exception:
            if time.monotonic() > deadline: raise RuntimeError('TDengine 服务未在期限内就绪')
            time.sleep(1)
    assert version == '3.3.8.8', version
    sql('DROP TOPIC IF EXISTS FORCE sensor_topic'); sql('DROP DATABASE IF EXISTS native_tmq')
    sql("CREATE DATABASE native_tmq VGROUPS 1 BUFFER 8 PRECISION 'ms'")
    sql('CREATE TABLE native_tmq.sensor_values(ts TIMESTAMP, reading DOUBLE, station NCHAR(128))')
    sql('CREATE TOPIC sensor_topic AS SELECT ts,reading,station FROM native_tmq.sensor_values')
    if not any(row[0] == 'tmq_reader' for row in sql('SHOW USERS')['data']):
        sql("CREATE USER tmq_reader PASS '" + passwords[0] + "'")
    sql("ALTER USER tmq_reader PASS '" + passwords[0] + "'")
    sql('GRANT SUBSCRIBE ON sensor_topic TO tmq_reader')
    sql('INSERT INTO native_tmq.sensor_values VALUES (1793577600123,1.25,\'汉字🙂\')')
    identifier = uuid.uuid4().hex; remote = '/tmp/datahub-tdengine-' + identifier
    command(['docker', 'exec', options.worker, 'mkdir', '-p', remote + '/secrets', remote + '/logs', remote + '/probe'])
    command(['docker', 'cp', str(directory / 'worker-secrets/tdengine-test'), options.worker + ':' + remote + '/secrets/tdengine-test'])
    command(['docker', 'exec', options.worker, 'chown', '-R', '10001:10001', remote])
    command(['docker', 'exec', options.worker, 'chmod', '0700', remote + '/secrets'])
    environment = ['-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_SECRET_DIR=' + remote + '/secrets', '-e', 'DATAHUB_ETL_SECRET_REFS=tdengine-test', '-e', 'FLINK_LOG_DIR=' + remote + '/logs']
    worker_prefix = ['docker', 'exec', '-u', '10001'] + environment + [options.worker]
    ddl_path = MODULE / 'target/native-sql/tdengine-source.sql'
    ddl = ddl_path.read_text(); alias = re.search(r'CREATE TEMPORARY VIEW (`[^`]+`)', ddl).group(1)
    sql_sha = hashlib.sha256(ddl.encode()).hexdigest()
    source = MODULE / 'runtime/tdengine-smoke/ManagedTdengineProbe.java'
    libs = directory / 'provided'; libs.mkdir(exist_ok=True)
    paths = json.loads(command(['docker', 'exec', options.worker, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')+glob.glob('/opt/flink/lib/slf4j-*.jar')+glob.glob('/opt/flink/lib/log4j-*.jar')))"]))
    for path in paths:
        local = libs / Path(path).name
        if not local.exists(): command(['docker', 'cp', options.worker + ':' + path, str(local)])
    classes = directory / 'probe-classes'; classes.mkdir(exist_ok=True)
    command(['javac', '--release', '17', '-cp', str(libs / '*') + ':' + binding['artifact'], '-d', str(classes), str(source)])
    probe_jar = directory / 'probe.jar'; command(['jar', '--create', '--file', str(probe_jar), '-C', str(classes), '.'])
    command(['docker', 'cp', str(probe_jar), options.worker + ':' + remote + '/probe.jar'])
    probe_command = ['/opt/java17/bin/java', '-Xmx256m', '-cp', '/opt/flink/lib/*:' + remote + '/probe.jar', 'ManagedTdengineProbe', remote + '/probe']
    probe_log = command(worker_prefix + probe_command)
    deny_environment = ['docker', 'exec', '-u', '10001', '-e', 'DATAHUB_ETL_SECRET_REFS=', '-e', 'DATAHUB_ETL_SECRET_DIR=' + remote + '/secrets', options.worker]
    command(deny_environment + probe_command + ['deny'])
    probe = json.loads(command(['docker', 'exec', options.worker, 'cat', remote + '/probe/probe.json']))

    def content():
        script = "import pathlib,json;p=pathlib.Path(" + repr(remote + '/output') + ");r=[]\nfor f in p.rglob('*'):\n if f.is_file() and not f.name.startswith('.'):\n  for line in f.read_text().splitlines():\n   if line.strip():r.append(json.loads(line))\nprint(json.dumps(r,ensure_ascii=False))"
        return json.loads(command(['docker', 'exec', options.worker, 'python3', '-c', script]))

    base = "SET 'execution.target'='local';\nSET 'execution.runtime-mode'='streaming';\nSET 'parallelism.default'='1';\nSET 'table.dml-sync'='true';\nSET 'execution.checkpointing.interval'='2s';\nSET 'execution.checkpointing.externalized-checkpoint-retention'='RETAIN_ON_CANCELLATION';\nSET 'execution.checkpointing.dir'='file://" + remote + "/checkpoints';\n" + ddl
    base += "CREATE TEMPORARY TABLE dh_tmq_output(ts TIMESTAMP(3), reading DOUBLE, station STRING) WITH ('connector'='filesystem','path'='file://" + remote + "/output','format'='json','sink.rolling-policy.rollover-interval'='1s','sink.rolling-policy.check-interval'='1s');\n"
    dml = 'INSERT INTO dh_tmq_output SELECT ts,reading,station FROM ' + alias + ';\n'
    logs = []; runtime_logs = []; job_ids = []; checkpoint = None

    def job(program, expected_station, insert=None):
        local = directory / ('job-' + str(len(logs)) + '.sql'); local.write_text(program)
        target = remote + '/' + local.name; command(['docker', 'cp', str(local), options.worker + ':' + target])
        marker = remote + '/job.pid'; invocation = 'echo $$ > ' + marker + '; exec timeout --signal=INT --kill-after=10s 90s /opt/flink/bin/sql-client-managed-original.sh -f ' + target
        process = subprocess.Popen(worker_prefix + ['/bin/bash', '-c', invocation], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 55
            while time.monotonic() < deadline:
                if process.poll() is not None: raise RuntimeError('TMQ 原生持续作业提前退出')
                rows = content()
                if any(row.get('station') == expected_station for row in rows): break
                time.sleep(1)
            else: raise RuntimeError('TMQ 未在期限内产生预期数据')
            if insert is not None:
                sql(insert); deadline = time.monotonic() + 25
                while time.monotonic() < deadline:
                    if process.poll() is not None: raise RuntimeError('TMQ 未持续运行')
                    if any(row.get('station') == 'live_after_start' for row in content()): break
                    time.sleep(1)
                else: raise RuntimeError('TMQ 未采集启动后追加的数据')
        finally:
            snapshot = "import pathlib,shutil;p=pathlib.Path(" + repr(remote + '/logs') + ");v=list(p.glob('*.log'));out=pathlib.Path(" + repr(remote + '/runtime-job-') + "+'" + str(len(logs)) + ".log');data='\\n'.join(f.read_text() for f in v if f.stat().st_size<=16*1024*1024);out.write_text(data);print(data)"
            runtime_log = command(worker_prefix + ['python3', '-c', snapshot]); runtime_logs.append(runtime_log)
            if process.poll() is None:
                stop = "import pathlib,os,signal;p=pathlib.Path(" + repr(marker) + ");pid=int(p.read_text());cmd=pathlib.Path('/proc/'+str(pid)+'/cmdline').read_bytes();assert b'timeout' in cmd and b'" + local.name + "' in cmd;os.kill(pid,signal.SIGINT)"
                command(worker_prefix + ['python3', '-c', stop], timeout=10)
            log = process.communicate(timeout=20)[0]; logs.append(log)
            safe_log = log
            for secret in [root_password] + passwords: safe_log = safe_log.replace(secret, '[REDACTED]')
            (directory / (local.stem + '-native.log')).write_text(safe_log)
            job_ids.extend(re.findall(r'Job ID:\s*([a-f0-9]{32})', log))
            job_ids.extend(re.findall(r'(?:for job |jobId=|Starting job |Restoring job )([a-f0-9]{32})', runtime_log))
            if '[ERROR] Could not execute SQL statement.' in log: raise RuntimeError('TMQ 原生 SQL 执行失败；请核查已脱敏日志')
        return process.returncode

    first_exit = job(base + dml, '汉字🙂', "INSERT INTO native_tmq.sensor_values VALUES (1793577601123,2.5,'live_after_start')")
    checkpoint_script = "import pathlib,json;p=pathlib.Path(" + repr(remote + '/checkpoints') + ");v=list(p.rglob('_metadata'));v.sort(key=lambda p:p.stat().st_mtime);print(json.dumps(str(v[-1]) if v else None))"
    checkpoint = json.loads(command(['docker', 'exec', options.worker, 'python3', '-c', checkpoint_script]))
    if not checkpoint: raise RuntimeError('真实流作业没有生成可恢复 checkpoint')
    rotated = 'Dh' + secrets.token_hex(16) + 'aZ9'; passwords.append(rotated)
    sql("ALTER USER tmq_reader PASS '" + rotated + "'"); write_secret(directory, rotated)
    command(['docker', 'cp', str(directory / 'worker-secrets/tdengine-test'), options.worker + ':' + remote + '/secrets/tdengine-test'])
    command(['docker', 'exec', options.worker, 'chown', '10001:10001', remote + '/secrets/tdengine-test'])
    sql("INSERT INTO native_tmq.sensor_values VALUES (1793577602123,3.75,'after_checkpoint_restore')")
    second_exit = job(base + "SET 'execution.savepoint.path'='file://" + checkpoint + "';\n" + dml, 'after_checkpoint_restore')
    if any('switched from RUNNING to FAILED' in log or 'received unexpected exception' in log for log in runtime_logs):
        raise RuntimeError('TMQ 真实作业发生失败或自动重启，不能把最终读到消息算稳定通过')
    restore_lines = [line for line in runtime_logs[-1].splitlines() if any(marker in line for marker in ['Restoring job ', 'Starting job ', 'Restoring SplitEnumerator'])]
    if not any('Starting job ' in line and checkpoint in line for line in restore_lines) or not any('Restoring SplitEnumerator' in line for line in restore_lines):
        raise RuntimeError('原生引擎未证明从指定 checkpoint 恢复官方枚举状态')
    collected = content()
    expected = [(station, reading, datetime.fromtimestamp(millis / 1000, ZoneInfo(probe['timestampZone'])).strftime('%Y-%m-%d %H:%M:%S') + '.123')
                for station, reading, millis in [('汉字🙂', 1.25, 1793577600123), ('live_after_start', 2.5, 1793577601123), ('after_checkpoint_restore', 3.75, 1793577602123)]]
    for station, reading, timestamp in expected:
        assert any(row.get('station') == station and row.get('reading') == reading and row.get('ts') == timestamp for row in collected), '实际 TMQ 值不一致'
    scan = "import pathlib,base64,json;sentinels=[base64.b64decode(v) for v in input().split(',')];root=pathlib.Path(" + repr(remote) + ");files=[p for p in root.rglob('*') if p.is_file() and '/secrets/' not in str(p)];hits=sum(any(s in p.read_bytes() for s in sentinels) for p in files);print(json.dumps({'files':len(files),'sentinelMatches':hits}))"
    sentinel_data = ','.join(base64.b64encode(password.encode(charset)).decode() for password in [root_password] + passwords for charset in ['utf-8', 'utf-16le', 'utf-16be'])
    scanned = json.loads(command(['docker', 'exec', '-i', options.worker, 'python3', '-c', scan], data=sentinel_data + '\n'))
    for log in logs + runtime_logs + [probe_log]:
        if any(password in log for password in [root_password] + passwords): raise RuntimeError('凭据材料进入了日志')
    assert scanned['sentinelMatches'] == 0
    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Flink 1.20.3 native SQL TMQ', 'tdengine': version,
              'binding': binding, 'sqlSha256': sql_sha, 'jobIds': sorted(set(job_ids)), 'initialAndLiveMessagesExact': True,
              'actualRows': collected, 'checkpointRestored': True, 'stableCheckpointHandling': True, 'credentialRotatedBeforeRestore': True, 'defaultEmptySecretGrantDenied': True,
              'probe': probe, 'actualCheckpointAndLogsSentinelScan': scanned, 'controlledStopExitCodes': [first_exit, second_exit],
              'restoreLogEvidence': restore_lines, 'checkpointPath': checkpoint,
              'limits': ['仅 Flink TMQ 持续输入；Spark、TDengine 输出、任意主题投影和未验证原生类型仍明确拒绝', 'TIMESTAMP无时区按官方SDK和工作节点JVM时区解释；本次明确记录实际ZoneId验证', '本次由原生 SQL 执行真实端到端样例，不等于重启后管理端完整触发验收', '测试环境 WebSocket 明确不启用 TLS；生产绑定缺省要求 TLS']}
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    for index, log in enumerate(logs): options.output.with_name(options.output.stem + '-' + str(index) + '.log').write_text(log)
    for index, log in enumerate(runtime_logs): options.output.with_name(options.output.stem + '-runtime-' + str(index) + '.log').write_text(log)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
