#!/usr/bin/env python3
"""独立 FTP 测试身份下验证正式 Flink 文件源、FTP 输出与读回；所有秘密仅在工作节点生成。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import uuid


def run(arguments, timeout=60, check=True):
    result = subprocess.run(arguments, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError((result.stdout + result.stderr)[-5000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='components-stable-ds')
    parser.add_argument('--binding', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--only', default='')
    options = parser.parse_args()
    binding = json.loads(options.binding.read_text())
    artifact = Path(binding['artifact'])
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != binding['jarSha256']:
        raise RuntimeError('被测连接器摘要与构建绑定不一致')
    root = Path(__file__).resolve().parents[2]
    remote = '/tmp/datahub-flink-bounded-' + uuid.uuid4().hex
    local = options.output.resolve().parent / ('flink-bounded-evidence-' + uuid.uuid4().hex)
    local.mkdir(parents=True, mode=0o700)
    fixture = local / 'fixture.py'
    fixture.write_text('''import json,secrets,pathlib,os,sys
sys.path.insert(0,'/opt/datahub-fixtures/ftp-pyftpdlib-2.2.0')
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer
p=pathlib.Path(sys.argv[1]); (p/'pid').write_text(str(os.getpid()))
password=secrets.token_urlsafe(32)
secret=p/'secrets/fixtureFtp';secret.write_text(json.dumps({'username':'fixture','password':password}));secret.chmod(0o600)
a=DummyAuthorizer();a.add_user('fixture',password,str(p/'ftp'),perm='elradfmwMT')
FTPHandler.authorizer=a
server=FTPServer(('127.0.0.1',0),FTPHandler)
(p/'port').write_text(str(server.socket.getsockname()[1]))
server.serve_forever()
''')
    (local / 'input.csv').write_text('id,amount,label,at\n9007199254740993,12345678901234567890123456.7800,"河道 O\'Reilly😀",2026-11-03 08:09:10.123456\n9007199254740994,0.0000,"第二行",2026-11-03 08:09:11.000000\n')
    cache = Path(tempfile.gettempdir()) / 'datahub-flink-sdk-cache'
    sdk = []
    for entry in binding['targetSdk']:
        path = cache / (entry['sha256'] + '-' + entry['name'])
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise RuntimeError('目标官方 SDK 缓存缺失或摘要不符，请重新准备连接器')
        sdk.append(str(path))
    source = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/runtime/flink/bounded-smoke/ManagedFlinkBoundedSmoke.java'
    run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', ':'.join([str(artifact)] + sdk), '-d', str(local / 'classes'), str(source)])
    run(['docker', 'exec', options.container, 'mkdir', '-p', remote + '/classes', remote + '/files', remote + '/ftp/output', remote + '/secrets'])
    run(['docker', 'exec', options.container, 'chmod', '0700', remote, remote + '/secrets'])
    cases = []
    try:
        for path, target in [(artifact, 'bounded.jar'), (fixture, 'fixture.py'), (local / 'input.csv', 'files/input.csv')]:
            run(['docker', 'cp', str(path), options.container + ':' + remote + '/' + target])
        run(['docker', 'cp', str(local / 'classes') + '/.', options.container + ':' + remote + '/classes'])
        run(['docker', 'exec', options.container, 'sh', '-c', 'printf "%s\\n" ' + remote + '/files > ' + remote + '/roots.txt'])
        run(['docker', 'exec', '-d', options.container, 'sh', '-c', 'python3 ' + remote + '/fixture.py ' + remote + ' > ' + remote + '/fixture.log 2>&1'])
        run(['docker', 'exec', options.container, 'python3', '-c', 'import pathlib,time,sys; p=pathlib.Path(sys.argv[1]); deadline=time.monotonic()+10\nwhile not p.exists():\n if time.monotonic()>deadline: raise RuntimeError("FTP fixture未就绪")\n time.sleep(.05)', remote + '/port'])
        port = run(['docker', 'exec', options.container, 'cat', remote + '/port']).stdout.strip()
        prefix = ['docker', 'exec', '-e', 'DATAHUB_ETL_FILE_ROOTS_FILE=' + remote + '/roots.txt', '-e', 'DATAHUB_ETL_SECRET_DIR=' + remote + '/secrets',
                  '-e', 'DATAHUB_ETL_SECRET_REFS=fixtureFtp', '-e', 'DATAHUB_ETL_FTP_ROOTS=' + json.dumps(['ftp://127.0.0.1:' + port + '/']), options.container,
                  'timeout', '--kill-after=8s', '100s', '/opt/java17/bin/java', '-Xmx512m', '-XX:ActiveProcessorCount=2',
                  '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
                  '-cp', '/opt/flink/lib/*:' + remote + '/bounded.jar:' + remote + '/classes', 'cn.iocoder.yudao.framework.etl.flink.bounded.ManagedFlinkBoundedSmoke', remote, port]
        selected = set(options.only.split(',')) if options.only else set()
        for case in ['roundtrip', 'row-budget', 'unbound-root', 'ftp-path-kind', 'auth-scope']:
            if selected and case not in selected: continue
            result = run(prefix + [case], timeout=115, check=False)
            (local / (case + '.log')).write_text(result.stdout + result.stderr)
            expected = 'DATAHUB_FLINK_BOUNDED_PASS' if case in ['roundtrip', 'auth-scope'] else 'DATAHUB_FLINK_BOUNDED_REJECT ' + case
            if result.returncode or expected not in result.stdout:
                raise RuntimeError('真实 Flink 场景不符：' + case + '\n' + (result.stdout + result.stderr)[-5000:])
            cases.append({'case': case, 'exitCode': result.returncode, 'expectedFailure': case not in ['roundtrip', 'auth-scope']})
        if not cases: raise RuntimeError('没有选择有效的验证场景')
        result = {'jarSha256': binding['jarSha256'], 'sourceSha256': binding['sourceSha256'], 'cases': cases, 'evidenceDirectory': str(local)}
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(result, ensure_ascii=False))
    finally:
        run(['docker', 'exec', options.container, 'python3', '-c', 'import pathlib,os,signal,sys; p=pathlib.Path(sys.argv[1]); f=p/"pid"\nif f.exists():\n try: os.kill(int(f.read_text()),signal.SIGTERM)\n except ProcessLookupError: pass\nfor x in (p/"secrets").glob("*"): x.unlink()', remote], check=False)


if __name__ == '__main__':
    main()
