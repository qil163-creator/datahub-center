#!/usr/bin/env python3
"""在无宿主端口的独立 PostgreSQL 身份/网络中验证真实 Flink JDBC upsert，完成后清理专属资源。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import uuid


def run(arguments, timeout=60, check=True):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode: raise RuntimeError((result.stdout + result.stderr)[-5000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='components-stable-ds')
    parser.add_argument('--binding', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    binding = json.loads(options.binding.read_text()); artifact = Path(binding['artifact'])
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != binding['jarSha256']: raise RuntimeError('运行包摘要不符')
    nonce = uuid.uuid4().hex[:12]; network = 'datahub-upsert-' + nonce; postgres = network + '-pg'; remote = '/tmp/' + network
    evidence = Path(tempfile.mkdtemp(prefix=network+'-')); source = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/runtime/flink/jdbc-smoke/VerifiedJdbcUpsertSmoke.java'
    sdk = Path(tempfile.gettempdir())/'datahub-flink-sdk-cache'
    classpath = [str(artifact)]
    for entry in binding['targetSdk']:
        path = sdk/(entry['sha256']+'-'+entry['name'])
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']: raise RuntimeError('目标SDK缓存不符')
        classpath.append(str(path))
    run(['javac','--release','17','-encoding','UTF-8','-cp',':'.join(classpath),'-d',str(evidence/'classes'),str(source)])
    url = 'jdbc:postgresql://verified-postgres:5432/upsert_qa?connectTimeout=10&socketTimeout=30'
    # SQL模板直接来自本轮组件编译器专测；只替换公开的测试服务摘要，结构与LIKE合同保持一致。
    wrapper = (root/'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-flink-upsert/wrapper.sql').read_text()
    old = hashlib.sha256(b'jdbc:postgresql://127.0.0.1:5432/fixture').hexdigest()
    wrapper = wrapper.replace(old,hashlib.sha256(url.encode()).hexdigest())
    (evidence/'wrapper.sql').write_text(wrapper)
    cases=[];connected=False
    try:
        run(['docker','network','create','--internal',network])
        run(['docker','run','-d','--name',postgres,'--network',network,'--network-alias','verified-postgres','--memory','128m','-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=upsert_qa','postgres:18-alpine','-c','shared_buffers=16MB','-c','max_connections=20'])
        run(['docker','exec',postgres,'sh','-c','for i in $(seq 1 100); do test "$(cat /proc/1/comm)" = postgres && psql -X -q -A -t -U postgres -d upsert_qa -c "SELECT 1" >/dev/null 2>&1 && exit 0; sleep .2; done; exit 1'])
        run(['docker','exec',postgres,'psql','-U','postgres','-d','upsert_qa','-v','ON_ERROR_STOP=1','-c','CREATE ROLE qa_upsert LOGIN; CREATE TABLE public.target(id bigint PRIMARY KEY, label text); GRANT CONNECT ON DATABASE upsert_qa TO qa_upsert; GRANT USAGE ON SCHEMA public TO qa_upsert; GRANT SELECT,INSERT,UPDATE,DELETE ON public.target TO qa_upsert;'])
        run(['docker','network','connect',network,options.container]);connected=True
        run(['docker','exec',options.container,'mkdir','-p',remote+'/classes'])
        for local,target in [(artifact,'verified.jar'),(evidence/'wrapper.sql','wrapper.sql')]:run(['docker','cp',str(local),options.container+':'+remote+'/'+target])
        run(['docker','cp',str(evidence/'classes')+'/.',options.container+':'+remote+'/classes'])
        prefix=['docker','exec',options.container,'timeout','--kill-after=8s','85s','/opt/java17/bin/java','-Xmx512m','-XX:ActiveProcessorCount=2','--add-opens=java.base/java.lang=ALL-UNNAMED','--add-opens=java.base/java.util=ALL-UNNAMED','--add-opens=java.base/java.io=ALL-UNNAMED','--add-opens=java.base/java.nio=ALL-UNNAMED','-cp','/opt/flink/lib/*:'+remote+'/verified.jar:'+remote+'/classes','cn.iocoder.yudao.framework.etl.flink.jdbc.VerifiedJdbcUpsertSmoke']
        for case in ['upsert','changelog','missing-catalog-pk','wrong-url','null-key']:
            run(['docker','exec',postgres,'psql','-U','postgres','-d','upsert_qa','-v','ON_ERROR_STOP=1','-c','TRUNCATE public.target;'])
            result=run(prefix+[case,url,remote+'/wrapper.sql'],timeout=100,check=False)
            (evidence/(case+'.log')).write_text(result.stdout+result.stderr)
            marker='VERIFIED_JDBC_PASS '+case if case in ['upsert','changelog'] else 'VERIFIED_JDBC_REJECT '+case
            if result.returncode or marker not in result.stdout:raise RuntimeError('真实Flink场景失败：'+case+'\n'+(result.stdout+result.stderr)[-5000:])
            cases.append({'case':case,'exitCode':result.returncode,'expectedFailure':case not in ['upsert','changelog']});print(json.dumps(cases[-1]),flush=True)
        report={'jarSha256':binding['jarSha256'],'sourceSha256':binding['sourceSha256'],'flinkVersion':'1.20.3','jdbcConnector':'3.3.0-1.20','postgresVersion':'18','mode':'STREAMING','cases':cases,'evidenceDirectory':str(evidence),'scope':'独立有界测试流；不宣称持续CDC或故障恢复验收'}
        options.output.parent.mkdir(parents=True,exist_ok=True);options.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    finally:
        if connected:run(['docker','network','disconnect',network,options.container],check=False)
        run(['docker','rm','-f',postgres],check=False);run(['docker','network','rm',network],check=False)


if __name__=='__main__':main()
