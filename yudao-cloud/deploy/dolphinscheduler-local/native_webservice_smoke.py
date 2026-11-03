#!/usr/bin/env python3
"""用正式编译器生成的SQL在原生Spark验收WSDL四种协议与执行授权。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

from native_http_smoke import run


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-functions-http-binding.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-webservice.json')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    generated = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-webservice-sql'
    binding = json.loads(options.binding.read_text())
    stage = '/tmp/datahub-webservice-' + uuid.uuid4().hex
    methods = ['soap11', 'soap12', 'httpGet', 'httpPost']
    sources = {method: (generated / (method + '.sql')).read_text() for method in methods}
    declarations, selects = [], []
    for method, source in sources.items():
        alias = re.search(r'CREATE TEMPORARY VIEW `([A-Za-z0-9_]+)`', source).group(1)
        declarations.append(source[:source.rindex('SELECT id, label FROM')])
        selects.append("SELECT '" + method + "' AS protocol,id,label FROM `" + alias + '`')
    positive = '\n'.join(declarations) + '\n' + ' UNION ALL '.join(selects) + ' ORDER BY protocol,id;\n'
    fixture = '''import http.server,json,pathlib,sys,urllib.parse,uuid
root=pathlib.Path(sys.argv[1]);secret='Bearer '+uuid.uuid4().hex
(root/'secrets').mkdir(mode=0o700);(root/'secrets'/'apiAccess').write_text(secret)
class Handler(http.server.BaseHTTPRequestHandler):
 def handle_request(self):
  if self.path=='/wsdl':
   body=(root/'fixture.wsdl').read_bytes()
  else:
   if self.headers.get('Authorization')!=secret:self.send_response(401);self.end_headers();return
   method=self.path.split('/')[1];content='<ListResponse><rows><row><id>9007199254740993</id><label>河道😀</label></row><row><id>9007199254740994</id><label>O\\'Reilly水情</label></row></rows></ListResponse>'
   request=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode()
   if method.startswith('soap'):
    ns='http://schemas.xmlsoap.org/soap/envelope/' if method=='soap11' else 'http://www.w3.org/2003/05/soap-envelope'
    if (root/'fault').exists():content='<s:Fault><message>fixture secret must never be echoed</message></s:Fault>'
    content='<s:Envelope xmlns:s="'+ns+'"><s:Body>'+content+'</s:Body></s:Envelope>'
   if (root/'xxe').exists():content='<!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>'
   body=content.encode()
  self.send_response(200);self.send_header('Content-Type','text/xml;charset=UTF-8');self.end_headers();self.wfile.write(body)
 def do_GET(self):self.handle_request()
 def do_POST(self):self.handle_request()
 def log_message(self,*args):pass
http.server.HTTPServer(('127.0.0.1',19085),Handler).serve_forever()
'''
    setup = 'import pathlib,sys,json;p=pathlib.Path(sys.argv[1]);p.mkdir(mode=0o700);[(p/name).write_text(value) for name,value in json.load(sys.stdin).items()]'
    run(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', setup, stage], json.dumps({'fixture.py': fixture, 'fixture.wsdl': (generated / 'fixture.wsdl').read_text()})).check_returncode()
    server = subprocess.Popen(['docker', 'exec', '-u', '10001', options.container, 'python3', stage + '/fixture.py', stage], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    allowed = ['http://127.0.0.1:19085/wsdl', *['http://127.0.0.1:19085/' + method + ('' if method.startswith('soap') else '/rows') for method in methods]]
    report = {'status': 'RUNNING', 'scope': 'compiler SQL + real Spark3.5.6 + WSDL1.1/HTTP fixture; not management API or scheduler acceptance', 'checks': {}}
    try:
        ready = 'import socket,time\nfor _ in range(100):\n try:\n  s=socket.create_connection(("127.0.0.1",19085),.1);s.close();break\n except OSError:time.sleep(.1)\nelse:raise RuntimeError("WSDL fixture未就绪")'
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', ready]).check_returncode()
        for name in ['four_protocols', 'unbound_endpoint', 'soap_fault', 'xxe']:
            sql = binding['sparkInitializationSql'] + '\n' + (positive if name == 'four_protocols' else sources['soap11'])
            write = 'import pathlib,sys;pathlib.Path(sys.argv[1]).write_text(sys.stdin.read())'
            run(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', write, stage + '/task.sql'], sql).check_returncode()
            if name in ['soap_fault', 'xxe']:
                run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', 'import pathlib,sys;pathlib.Path(sys.argv[1]).touch()', stage + ('/fault' if name == 'soap_fault' else '/xxe')]).check_returncode()
            result = run(['docker', 'exec', '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_HTTP_ALLOWLIST=' + json.dumps(allowed if name != 'unbound_endpoint' else allowed[:1]),
                          '-e', 'DATAHUB_ETL_SECRET_DIR=' + stage + '/secrets', '-e', 'DATAHUB_ETL_SECRET_REFS=apiAccess', '-w', stage, options.container,
                          '/opt/spark/bin/spark-sql-managed-original', '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.ui.enabled=false',
                          '--conf', 'spark.sql.parser.escapedStringLiterals=false', '-f', stage + '/task.sql'])
            output = result.stdout + result.stderr
            rows = [line.split('\t') for line in result.stdout.splitlines() if re.match(r'^(soap11|soap12|httpGet|httpPost)\t900719925474099[34]\t', line)]
            expected = [[method, identifier, label] for method in sorted(methods) for identifier, label in [('9007199254740993', '河道😀'), ('9007199254740994', "O'Reilly水情")]]
            passed = result.returncode == 0 and rows == expected if name == 'four_protocols' else result.returncode != 0
            passed = passed and 'fixture secret must never be echoed' not in output
            report['checks'][name] = {'passed': passed, 'exitCode': result.returncode, 'rows': rows, 'sqlSha256': hashlib.sha256(sql.encode()).hexdigest(), 'outputSha256': hashlib.sha256(output.encode()).hexdigest()}
            if not passed:
                Path('/tmp/datahub-webservice-' + name + '.log').write_text(output)
                raise RuntimeError('原生WebService验证失败，诊断记录在本机/tmp')
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'; raise
    finally:
        stop = 'import os,pathlib,signal,sys\nfor path in pathlib.Path("/proc").glob("[0-9]*/cmdline"):\n try:\n  args=path.read_bytes().split(bytes([0]));\n  if len(args)>1 and args[1]==sys.argv[1].encode():os.kill(int(path.parent.name),signal.SIGTERM)\n except (OSError,ProcessLookupError):pass'
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', stop, stage + '/fixture.py']).check_returncode()
        server.communicate(timeout=10)
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', 'import shutil,sys;shutil.rmtree(sys.argv[1])', stage]).check_returncode()
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
