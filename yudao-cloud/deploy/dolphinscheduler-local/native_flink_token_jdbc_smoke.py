#!/usr/bin/env python3
"""独立mTLS Token初始化→官方Flink JDBC有限源/表输出，不使用Flink禁用的REST组件。"""
import argparse
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import time
import uuid


def run(*arguments, data=None, check=True):
    result = subprocess.run(arguments, input=data, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stdout[-2500:] + result.stderr[-2500:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='components-stable-ds')
    parser.add_argument('--worker-binding', type=Path, default=Path('/tmp/datahub-native-script-binding.json'))
    parser.add_argument('--launcher-binding', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--streaming', action='store_true')
    options = parser.parse_args()
    stamp = uuid.uuid4().hex[:12]; network = 'qdata-token-' + stamp; postgres = 'qdata-token-pg-' + stamp
    private = Path(tempfile.mkdtemp(prefix='qdata-token-jdbc-')); private.chmod(0o700)
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    attached = created = container = False; report = {'status': 'RUNNING', 'engine': 'FLINK 1.20.3 Java17', 'scope': 'Actual task-bound QDATA_TOKEN Docker worker (network=none), trusted mTLS initializer, then JDBC finite source -> JDBC table sink; no REST API component', 'cleanup': []}
    try:
        run('docker', 'network', 'create', '--internal', network); created = True
        run('docker', 'network', 'connect', network, options.worker); attached = True
        run('docker', 'run', '-d', '--name', postgres, '--network', network, '--network-alias', 'token-postgres', '--memory', '128m',
            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=token_qa', 'postgres:18-alpine', '-c', 'shared_buffers=16MB', '-c', 'max_connections=20', '-c', 'work_mem=1MB'); container = True
        for attempt in range(100):
            if subprocess.run(['docker', 'exec', postgres, 'psql', '-X', '-q', '-A', '-t', '-U', 'postgres', '-d', 'token_qa', '-c', 'SELECT 1'], capture_output=True).returncode == 0:
                break
            time.sleep(.2)
        else:
            raise RuntimeError('Token专用PG未就绪')
        def sql(query):
            return run('docker', 'exec', '-i', postgres, 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'token_qa', data=query)
        sql("CREATE ROLE qa_token LOGIN; CREATE SCHEMA qa_token AUTHORIZATION qa_token; SET ROLE qa_token; "
            "CREATE TABLE qa_token.source(id bigint,label text,amount numeric(38,8)); "
            "INSERT INTO qa_token.source VALUES (9007199254740993,'河道 O''Reilly😀',12345678901234567890.12345678),(9007199254740994,NULL,NULL); "
            "CREATE TABLE qa_token.target(LIKE qa_token.source);")
        config = private / 'jdbc.json'; config.write_text(json.dumps({'url': 'jdbc:postgresql://token-postgres:5432/token_qa', 'streaming': options.streaming})); config.chmod(0o600)
        script = Path(__file__).with_name('native_script_flink_smoke.py'); component_report = private / 'token-component.json'
        command = ['python3', str(script), '--worker', options.worker, '--worker-binding', str(options.worker_binding), '--launcher-binding', str(options.launcher_binding),
                   '--only', 'token,token-unauthorized', '--token-jdbc-config', str(config), '--output', str(component_report)]
        process = subprocess.run(command, text=True, capture_output=True)
        (private / 'execution.log').write_text(process.stdout + process.stderr)
        if process.returncode:
            raise RuntimeError('Flink Token真实执行未通过；私密诊断目录=' + str(private) + '\n' + (process.stdout + process.stderr)[-3500:])
        component = json.loads(component_report.read_text())
        retained = output.parent / 'flink-token-jdbc-artifacts'; retained.mkdir(parents=True, exist_ok=True)
        for scan in component['actualSerializationScans']:
            source = component_report.parent / scan['retainedArtifact']; destination = retained / source.name
            shutil.copy2(source, destination); scan['retainedArtifact'] = destination.relative_to(output.parent).as_posix()
        actual = json.loads(sql("SELECT COALESCE(json_agg(r),'[]'::json) FROM (SELECT id::text,label,amount::text FROM qa_token.target ORDER BY id) r;"))
        expected = [{'id': '9007199254740993', 'label': "河道 O'Reilly😀", 'amount': '12345678901234567890.12345678'}, {'id': '9007199254740994', 'label': None, 'amount': None}]
        if actual != expected:
            raise RuntimeError('Token初始化之后JDBC精确结果不符：' + repr(actual))
        report.update({'status': 'PASS', 'cases': component['cases'], 'launcherSha256': component['launcherSha256'], 'exactTargetRows': actual,
                       'runtimeMode': 'STREAMING' if options.streaming else 'BATCH', 'fixtureSourceBounded': True,
                       'brokerSessions': component['brokerSessions'], 'activeWorkersAfterStop': component['activeWorkersAfterStop'], 'rawTokenInLogsOrProgram': component['rawTokenInLogsOrProgram'],
                       'actualSerializationScans': component['actualSerializationScans'], 'negativeIdentityDidNotWrite': True,
                       'limitations': ['Technical runtime acceptance with a dedicated fixture identity, not a management-API published task.', 'Token is a startup initializer; it is not implicitly substituted into JDBC or MQ credentials.', 'No realtime token refresh or exactly-once external sink guarantee.']})
    finally:
        if container:
            run('docker', 'rm', '-f', postgres, check=False)
        if attached:
            run('docker', 'network', 'disconnect', network, options.worker, check=False)
        if created:
            run('docker', 'network', 'rm', network, check=False)
        report['cleanup'].append({'fixtureContainerRemoved': container, 'workerNetworkDetached': attached, 'isolatedNetworkRemoved': created})
        if report['status'] != 'PASS':
            report['status'] = 'FAIL'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'report': str(output), 'status': report['status']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
