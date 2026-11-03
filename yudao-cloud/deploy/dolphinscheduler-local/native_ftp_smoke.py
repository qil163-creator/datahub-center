#!/usr/bin/env python3
"""在工作节点回环FTP服务上验收STOU不覆盖、秘密隔离与Spark精确CSV往返。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

from native_http_smoke import run


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-ftp-binding.json'))
    parser.add_argument('--probe-only', action='store_true')
    parser.add_argument('--cases', default='write,read,gzip,text-invalid,unbound-root,unbound-secret')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-ftp.json')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    generated = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-ftp-sql'
    binding = json.loads(options.binding.read_text())
    stage = '/tmp/datahub-ftp-' + uuid.uuid4().hex
    fixture = '''import csv,json,logging,pathlib,sys,uuid
sys.path.insert(0,'/opt/datahub-fixtures/ftp-pyftpdlib-2.2.0')
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer
logging.disable(logging.CRITICAL)
root=pathlib.Path(sys.argv[1]);home=root/'bound';home.mkdir()
for name in ['output','compressed','reject','probe']: (home/name).mkdir()
(root/'secrets').mkdir(mode=0o700);password=uuid.uuid4().hex
(root/'secrets'/'ftpAccess').write_text(json.dumps({'username':'fixture','password':password}))
(home/'probe'/'existing.csv').write_text('EXISTING MUST SURVIVE')
(root/'probe.data').write_text('新文件😀')
(root/'outside.csv').write_text('OUTSIDE MUST NEVER BE READ');(home/'escape').symlink_to(root/'outside.csv')
with (home/'input.csv').open('w',newline='') as stream:
 writer=csv.writer(stream);writer.writerow(['id','label','amount','flag','day','observed'])
 writer.writerow(['9007199254740993','河道😀,\\nO\\'Reilly"','123456.120000000000000001','true','2026-11-02','2026-11-02T08:01:02.123456'])
 writer.writerow(['9007199254740994','','\\\\N','false','\\\\N','\\\\N'])
authorizer=DummyAuthorizer();authorizer.add_user('fixture',password,str(home),perm='elradfmwMT')
class Handler(FTPHandler):pass
Handler.authorizer=authorizer
FTPServer(('127.0.0.1',19086),Handler).serve_forever()
'''
    probe = r'''import java.nio.file.*;import java.nio.charset.*;import java.io.*;import java.util.*;
public class FtpProbe {
 static boolean contains(byte[] all,byte[] part){outer:for(int i=0;i<=all.length-part.length;i++){for(int j=0;j<part.length;j++)if(all[i+j]!=part[j])continue outer;return true;}return false;}
 public static void main(String[] args)throws Exception{
  String source=Files.readString(Path.of(args[0]));Class<?> json=Class.forName("cn.iocoder.yudao.framework.etl.vendor.ftp.hutool.json.JSONUtil");
  Object plan=json.getMethod("parseObj",String.class).invoke(null,source);
  Class<?> ftp=Class.forName("cn.iocoder.yudao.framework.etl.vendor.ftp.support.EtlManagedFtp");
  Object value=ftp.getMethod("fromEnvironment",plan.getClass()).invoke(null,plan);
  try(AutoCloseable session=(AutoCloseable)value){
   Class<?> type=session.getClass();var read=type.getMethod("read",String.class,int.class);
   byte[] before=(byte[])read.invoke(session,"probe/existing.csv",4096);
   String created=(String)type.getMethod("storeUnique",String.class,String.class,Path.class,long.class).invoke(session,"probe","existing.csv",Path.of(args[1],"probe.data"),4096L);
   if(created.equals("probe/existing.csv")||!Arrays.equals(before,(byte[])read.invoke(session,"probe/existing.csv",4096)))throw new IllegalStateException("STOU覆盖了已有文件");
   if(!Arrays.equals(Files.readAllBytes(Path.of(args[1],"probe.data")),(byte[])read.invoke(session,created,4096)))throw new IllegalStateException("FTP字节往返不一致");
   type.getMethod("deleteCreated",String.class).invoke(session,created);
   boolean refused=false;try{read.invoke(session,"escape",4096);}catch(java.lang.reflect.InvocationTargetException expected){refused=true;}if(!refused)throw new IllegalStateException("FTP服务端未阻止越界符号链接");
  }
  var schema=Class.forName("org.apache.spark.sql.types.StructType").getConstructor().newInstance();
  Class<?> factory=Class.forName("cn.iocoder.yudao.framework.etl.spark.EtlFtpTextProvider$Factory");
  var constructor=factory.getDeclaredConstructors()[0];constructor.setAccessible(true);
  Object object=constructor.newInstance(schema,Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)),"probe");
  var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(object);}
  Object credential=json.getMethod("parseObj",String.class).invoke(null,Files.readString(Path.of(System.getenv("DATAHUB_ETL_SECRET_DIR"),"ftpAccess")));
  String password=(String)credential.getClass().getMethod("getStr",Object.class).invoke(credential,"password");
  for(String encoding:List.of("UTF-8","UTF-16LE","UTF-16BE"))if(contains(bytes.toByteArray(),password.getBytes(Charset.forName(encoding))))throw new IllegalStateException("FTP工厂序列化包含密码");
  System.out.println("STOU_NO_OVERWRITE_AND_JAIL_AND_SECRET_SERIALIZATION_PASS");
 }
}'''
    compile(fixture, 'ftp-fixture.py', 'exec')
    plan = {'rootUri': 'ftp://127.0.0.1:19086/', 'credentialRef': 'ftpAccess'}
    setup = 'import pathlib,sys,json;p=pathlib.Path(sys.argv[1]);p.mkdir(mode=0o700);[(p/name).write_text(value) for name,value in json.load(sys.stdin).items()]'
    run(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', setup, stage], json.dumps({'fixture.py': fixture, 'probe-plan.json': json.dumps(plan)})).check_returncode()
    with tempfile.TemporaryDirectory(prefix='datahub-ftp-probe-') as temporary:
        local = Path(temporary); (local / 'FtpProbe.java').write_text(probe)
        subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(local), str(local / 'FtpProbe.java')], check=True)
        run(['docker', 'cp', str(local / 'FtpProbe.class'), options.container + ':' + stage + '/FtpProbe.class']).check_returncode()
    server = subprocess.Popen(['docker', 'exec', '-u', '10001', options.container, 'python3', stage + '/fixture.py', stage], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    report = {'status': 'RUNNING', 'scope': 'real pyftpdlib2.2.0 + CommonsNet3.13 worker probe' + ('' if options.probe_only else ' + real Spark3.5.6 compiler SQL'), 'managementApiAccepted': False, 'checks': {}}
    environment = ['docker', 'exec', '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_FTP_ROOTS=["ftp://127.0.0.1:19086/"]', '-e', 'DATAHUB_ETL_SECRET_DIR=' + stage + '/secrets', '-e', 'DATAHUB_ETL_SECRET_REFS=ftpAccess']
    try:
        ready = 'import socket,time\nfor _ in range(100):\n try:\n  s=socket.create_connection(("127.0.0.1",19086),.1);s.close();break\n except OSError:time.sleep(.1)\nelse:raise RuntimeError("FTP fixture未就绪")'
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', ready]).check_returncode()
        result = run([*environment, options.container, '/opt/java17/bin/java', '-Xmx128m', '-cp', stage + ':/opt/spark/jars/*', 'FtpProbe', stage + '/probe-plan.json', stage])
        output = result.stdout + result.stderr
        report['checks']['network_stou_jail_and_factory_secret'] = {'passed': result.returncode == 0 and 'STOU_NO_OVERWRITE_AND_JAIL_AND_SECRET_SERIALIZATION_PASS' in output, 'exitCode': result.returncode, 'outputSha256': hashlib.sha256(output.encode()).hexdigest()}
        if not report['checks']['network_stou_jail_and_factory_secret']['passed']:
            Path('/tmp/datahub-ftp-probe.log').write_text(output); raise RuntimeError('FTP工作节点合同探针失败，诊断记录在/tmp')
        if not options.probe_only:
            expected = [{'id': '9007199254740993', 'label': '河道😀,\nO\'Reilly"', 'amount': '123456.120000000000000001', 'flag': True, 'day': '2026-11-02', 'observed': '2026-11-02 08:01:02.123456'}, {'id': '9007199254740994', 'label': '', 'flag': False}]
            for name in options.cases.split(','):
                if name not in ['write', 'read', 'gzip', 'text-invalid', 'unbound-root', 'unbound-secret']: raise ValueError('未知FTP验收场景')
                sql_name = name if name in ['write', 'read', 'gzip', 'text-invalid'] else 'read'
                sql = binding['sparkInitializationSql'] + '\n' + (generated / (sql_name + '.sql')).read_text()
                run(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', 'import pathlib,sys;pathlib.Path(sys.argv[1]).write_text(sys.stdin.read())', stage + '/task.sql'], sql).check_returncode()
                env = environment.copy()
                if name == 'unbound-root': env[env.index('DATAHUB_ETL_FTP_ROOTS=["ftp://127.0.0.1:19086/"]')] = 'DATAHUB_ETL_FTP_ROOTS=[]'
                if name == 'unbound-secret': env[env.index('DATAHUB_ETL_SECRET_REFS=ftpAccess')] = 'DATAHUB_ETL_SECRET_REFS='
                result = run([*env, '-w', stage, options.container, '/opt/spark/bin/spark-sql-managed-original', '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.ui.enabled=false', '--conf', 'spark.sql.parser.escapedStringLiterals=false', '-f', stage + '/task.sql'])
                output = result.stdout + result.stderr
                rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{"id":"900719925474099')]
                passed = result.returncode == 0 and (name == 'write' or rows == expected) if name in ['write', 'read', 'gzip'] else result.returncode != 0
                report['checks'][name] = {'passed': passed, 'exitCode': result.returncode, 'rows': rows, 'sqlSha256': hashlib.sha256(sql.encode()).hexdigest(), 'outputSha256': hashlib.sha256(output.encode()).hexdigest()}
                if not passed:
                    Path('/tmp/datahub-ftp-' + name + '.log').write_text(output); raise RuntimeError('FTP原生Spark验收失败，诊断记录在/tmp')
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'; raise
    finally:
        stop = 'import os,pathlib,signal,sys\nfor path in pathlib.Path("/proc").glob("[0-9]*/cmdline"):\n try:\n  args=path.read_bytes().split(bytes([0]));\n  if len(args)>1 and args[1]==sys.argv[1].encode():os.kill(int(path.parent.name),signal.SIGTERM)\n except (OSError,ProcessLookupError):pass'
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', stop, stage + '/fixture.py']).check_returncode()
        server_output = server.communicate(timeout=10)[0]
        if report['status'] == 'FAIL': Path('/tmp/datahub-ftp-fixture.log').write_text(server_output)
        run(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', 'import shutil,sys;shutil.rmtree(sys.argv[1])', stage]).check_returncode()
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
