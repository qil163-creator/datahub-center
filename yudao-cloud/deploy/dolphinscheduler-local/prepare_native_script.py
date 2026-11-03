#!/usr/bin/env python3
"""快照固定隔离 worker 类路径，Java17 编译只含可信 RPC 的 Spark launcher。"""
import argparse,hashlib,json,os,subprocess,tempfile,zipfile,fcntl
from pathlib import Path

def run(*args):return subprocess.check_output(args,text=True).strip()
def immutable(data,directory,label):
    digest=hashlib.sha256(data).hexdigest();path=directory/(label+'-'+digest[:16]+'.jar')
    try:
        with path.open('xb') as stream:stream.write(data)
    except FileExistsError:
        if path.read_bytes()!=data:raise RuntimeError('不可变脚本依赖摘要冲突')
    path.chmod(0o444);return {'path':str(path),'sha256':digest}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--container',default='datahub-ds-local-standalone-1');parser.add_argument('--output',type=Path,required=True);parser.add_argument('--directory',type=Path,required=True);options=parser.parse_args()
    root=Path(__file__).resolve().parents[2];module=root/'yudao-framework/yudao-spring-boot-starter-biz-etl';options.directory.mkdir(parents=True,exist_ok=True)
    classes=module/'target/classes';common=root/'yudao-framework/yudao-dbswitch/yudao-dbswitch-common';repo=Path.home()/'.m2/repository'
    names=['cn/iocoder/yudao/framework/etl/core/script/'+name for name in ['EtlScriptWorker','EtlScriptProtocol','EtlJavaCompiler','EtlJavaScript','EtlScriptWorkerHttp']]+['tech/qiantong/qdata/common/core/base/EtlBase']
    common_names=['cn/iocoder/yudao/framework/dbswitch/common/'+name for name in ['util/BoundedRowCodec','util/JdbcTypesUtils','entity/JdbcArrayValue','entity/NativeCellValue','entity/MissingCellValue']]
    with tempfile.TemporaryDirectory(prefix='datahub-native-script-') as temporary:
        directory=Path(temporary);worker=directory/'worker.jar'
        with Path('/tmp/datahub-components-maven.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            with zipfile.ZipFile(worker,'w',zipfile.ZIP_DEFLATED) as jar:
                for classroot,patterns in [(classes,names),(common/'target/classes',common_names)]:
                    for pattern in patterns:
                        found=list(classroot.glob(pattern+'.class'))+list(classroot.glob(pattern+'$*.class'))
                        if not found:raise RuntimeError('请先受锁构建固定worker组件：'+pattern)
                        for source in sorted(found):
                            info=zipfile.ZipInfo(str(source.relative_to(classroot)),(2026,1,1,0,0,0));jar.writestr(info,source.read_bytes())
        worker_binding=immutable(worker.read_bytes(),options.directory,'worker')
        dependencies=[]
        for group,artifact in [('cn/hutool','hutool-all'),('com/alibaba/fastjson2','fastjson2'),('org/openjdk/nashorn','nashorn-core'),('org/ow2/asm','asm'),('org/ow2/asm','asm-commons'),('org/ow2/asm','asm-tree'),('org/ow2/asm','asm-analysis'),('org/ow2/asm','asm-util'),('org/apache/commons','commons-lang3')]:
            matches=sorted((repo/group/artifact).glob('*/*.jar'));matches=[path for path in matches if not path.name.endswith(('-sources.jar','-javadoc.jar','-tests.jar'))]
            if not matches:raise RuntimeError('固定worker运行依赖缺失：'+artifact)
            # 有效构建依赖版本从dependency.list中锁定，多个版本拒绝自行猜测。
            lock=root/'deploy/dolphinscheduler-local/native-script-dependencies.json'
            pinned=json.loads(lock.read_text());version=pinned[artifact];source=repo/group/artifact/version/(artifact+'-'+version+'.jar')
            dependencies.append({'name':artifact,'version':version,**immutable(source.read_bytes(),options.directory,artifact)})
        sources=[module/('src/main/java/cn/iocoder/yudao/framework/etl/core/script/'+name+'.java') for name in ['EtlNativeScriptProgram','EtlScriptBrokerClient','EtlScriptBrokerTransport','EtlScriptBrokerProtocol','EtlScriptProtocol','EtlScriptTls','EtlScriptAuthenticatedHttp']]
        sources+=[module/'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedSecrets.java',module/'src/main/java/cn/iocoder/yudao/framework/etl/core/http/EtlBoundedHttp.java',module/'runtime/script-spark/EtlNativeScriptMain.java']
        sources+=[common/('src/main/java/'+name+'.java') for name in common_names]
        api=directory/'api';api.mkdir();paths=run('docker','exec',options.container,'sh','-c','ls /opt/spark/jars/spark-sql_*.jar /opt/spark/jars/spark-sql-api_*.jar /opt/spark/jars/spark-core_*.jar /opt/spark/jars/spark-catalyst_*.jar /opt/spark/jars/spark-common-utils_*.jar /opt/spark/jars/scala-library-*.jar').splitlines()
        for path in paths:run('docker','cp',options.container+':'+path,str(api/Path(path).name))
        output=directory/'classes';output.mkdir();run('javac','--release','17','-encoding','UTF-8','-cp',str(api/'*'),'-d',str(output),*[str(path) for path in sources])
        launcher=directory/'launcher.jar';run('jar','--create','--date=2026-01-01T00:00:00Z','--file',str(launcher),'-C',str(output),'.');binding=immutable(launcher.read_bytes(),options.directory,'spark-launcher')
    manifest={'workerJavaRelease':25,'launcherJavaRelease':17,'workerClasspath':[worker_binding]+dependencies,'sparkLauncher':binding,'sparkMainClass':'cn.iocoder.yudao.framework.etl.script.spark.EtlNativeScriptMain','workerImage':'eclipse-temurin:25-jdk@sha256:e12d89b0c1719dd0377172597b141c27cc548fd3b2096442cd5f747ce158ca7d'}
    options.output.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print(json.dumps(manifest,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
