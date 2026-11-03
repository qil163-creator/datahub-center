#!/usr/bin/env python3
"""用原生Spark读取受管HTTP分页行源，验证Unicode、大整数及执行环境地址授权。"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid


def run(arguments, data=None, timeout=120):
    return subprocess.run(arguments, input=data, text=True, capture_output=True, timeout=timeout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-functions-http-binding.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-http.json')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    sql_path = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-http-sql/source.sql'
    sql_source = sql_path.read_text()
    authenticated_sql = sql_path.with_name('authenticated.sql').read_text()
    binding = json.loads(options.binding.read_text())
    stage = '/tmp/datahub-http-' + uuid.uuid4().hex
    fixture = '''import http.server,json,pathlib,sys,urllib.parse
root=pathlib.Path(sys.argv[1])
secret='Bearer '+__import__('uuid').uuid4().hex
(root/'secrets').mkdir(mode=0o700)
(root/'secrets'/'apiAccess').write_text(secret)
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if self.headers.get('Authorization')!=secret:
   self.send_response(401);self.end_headers();return
  query=urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
  page=int(query.get('pageNo',['1'])[0])
  rows=[{'id':9007199254740992+page,'label':'河道😀' if page==1 else "O'Reilly水情"}] if page<3 else []
  body=json.dumps({'data':{'rows':rows}},ensure_ascii=False).encode()
  self.send_response(200);self.send_header('Content-Type','application/json;charset=UTF-8');self.end_headers();self.wfile.write(body)
 def log_message(self,*args): pass
http.server.HTTPServer(('127.0.0.1',19084),Handler).serve_forever()
'''
    setup = 'import pathlib,sys,json;p=pathlib.Path(sys.argv[1]);p.mkdir(mode=0o700);[(p/name).write_text(value) for name,value in json.load(sys.stdin).items()]'
    run(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', setup, stage], json.dumps({'fixture.py': fixture})).check_returncode()
    server = subprocess.Popen(['docker', 'exec', '-u', '10001', options.container, 'python3', stage + '/fixture.py', stage], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    report = {'status': 'RUNNING', 'scope': 'compiler-generated SQL + real Spark3.5.6 + HTTP fixture; not management API or scheduler acceptance', 'checks': {}}
    try:
        ready = 'import socket,time\nfor _ in range(100):\n try:\n  s=socket.create_connection(("127.0.0.1",19084),.1);s.close();break\n except OSError:time.sleep(.1)\nelse:raise RuntimeError("HTTP fixture未就绪")'
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', ready]).check_returncode()
        for name, allowed, source, refs in [
                ('authenticated', ['http://127.0.0.1:19084/rows'], authenticated_sql, 'apiAccess'),
                ('unauthorized_address', [], authenticated_sql, 'apiAccess'),
                ('unauthorized_secret', ['http://127.0.0.1:19084/rows'], authenticated_sql, ''),
                ('missing_authentication', ['http://127.0.0.1:19084/rows'], sql_source, '')]:
            sql = binding['sparkInitializationSql'] + '\n' + source
            write = 'import pathlib,sys;pathlib.Path(sys.argv[1]).write_text(sys.stdin.read())'
            run(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', write, stage + '/task.sql'], sql).check_returncode()
            invocation = ['docker', 'exec', '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_HTTP_ALLOWLIST=' + json.dumps(allowed),
                          '-e', 'DATAHUB_ETL_SECRET_DIR=' + stage + '/secrets', '-e', 'DATAHUB_ETL_SECRET_REFS=' + refs,
                          '-w', stage, options.container, '/opt/spark/bin/spark-sql-managed-original', '--master', 'local[1]', '--driver-memory', '512m',
                          '--conf', 'spark.ui.enabled=false', '--conf', 'spark.sql.parser.escapedStringLiterals=false', '-f', stage + '/task.sql']
            result = run(invocation)
            output = result.stdout + result.stderr
            rows = [line.split('\t', 1) for line in result.stdout.splitlines() if re.match(r'^900719925474099[34]\t', line)]
            expected = [['9007199254740993', '河道😀'], ['9007199254740994', "O'Reilly水情"]]
            passed = result.returncode == 0 and rows == expected if name == 'authenticated' else result.returncode != 0
            report['checks'][name] = {'passed': passed, 'exitCode': result.returncode, 'actualRows': rows,
                                     'sqlSha256': hashlib.sha256(sql.encode()).hexdigest(), 'outputSha256': hashlib.sha256(output.encode()).hexdigest()}
            if not passed:
                Path('/tmp/datahub-http-' + name + '.log').write_text(output)
                raise RuntimeError('原生HTTP行源验证失败，诊断记录在本机/tmp')
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'
        raise
    finally:
        stop = 'import os,pathlib,signal,sys\nfor path in pathlib.Path("/proc").glob("[0-9]*/cmdline"):\n try:\n  args=path.read_bytes().split(bytes([0]));\n  if len(args)>1 and args[1]==sys.argv[1].encode():os.kill(int(path.parent.name),signal.SIGTERM)\n except (OSError,ProcessLookupError):pass'
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', stop, stage + '/fixture.py']).check_returncode()
        server.communicate(timeout=10)
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', 'import shutil,sys;shutil.rmtree(sys.argv[1])', stage]).check_returncode()
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
