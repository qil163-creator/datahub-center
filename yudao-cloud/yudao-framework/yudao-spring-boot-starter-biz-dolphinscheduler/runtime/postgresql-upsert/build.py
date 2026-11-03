#!/usr/bin/env python3
"""针对固定 DS 3.4.2 / 官方 DataX 运行时构建独立插件，不修改原生 DATAX。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request

SOURCE = 'https://raw.githubusercontent.com/apache/dolphinscheduler/3.4.2/dolphinscheduler-task-plugin/dolphinscheduler-task-datax/src/main/java/org/apache/dolphinscheduler/plugin/task/datax/DataxTask.java'
SOURCE_SHA256 = 'f4b8e5536fabb93471984c8a5bb20aa78e5d8a16bf1e9e4c49c6f31597202143'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True)
    parser.add_argument('--container',required=True)
    parser.add_argument('--lombok',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if (args.context, args.container) not in {
        ('colima-job-policy', 'datahub-job-policy-scheduler-1'),
        ('desktop-linux', 'datahub-ds-local-standalone-1'),
    }:
        raise RuntimeError('仅允许已知本地 DS 构建环境，不启动额外服务')
    here=Path(__file__).resolve().parent;root=here.parents[3]
    helper=root/'yudao-framework/yudao-dbswitch/yudao-dbswitch-product/yudao-dbswitch-product-postgresql/src/main/java/cn/iocoder/yudao/framework/dbswitch/dbwriter/pg/PostgresUpsertSql.java'
    docker=['docker','--context',args.context]
    def call(*parts):subprocess.run(docker+list(parts),check=True)
    source=urllib.request.urlopen(SOURCE,timeout=30).read()
    if hashlib.sha256(source).hexdigest()!=SOURCE_SHA256:raise RuntimeError('上游源码校验失败，拒绝构建')
    text=source.decode()
    text=re.sub(r'\bDataxTask\b','DatahubDataxUpsertTask',text)
    text=re.sub(r'\bDataxParameters\b','DatahubDataxUpsertParameters',text)
    marker='        ObjectNode writer = JSONUtils.createObjectNode();'
    assert text.count(marker)==1
    text=text.replace(marker,'''        // 凭据仍由 DS 受管数据源资源解析，仅传递冻结的目标身份和唯一键。
        writerParam.put("writeContract", dataXParameters.getWriteContract());
        writerParam.put("upsertSchema", dataXParameters.getUpsertSchema());
        writerParam.put("upsertTable", dataXParameters.getUpsertTable());
        dataXParameters.getUpsertKeys().forEach(writerParam.putArray("upsertKeys")::add);
        if ("POSTGRESQL_UPSERT_HOOKS_V2".equals(dataXParameters.getWriteContract())) {
            if (taskRequest.getWorkflowInstanceId() <= 0 || taskRequest.getProjectCode() == null
                    || taskRequest.getWorkflowDefinitionCode() == null || taskRequest.getTaskName() == null)
                throw new IllegalArgumentException("事务钩子缺少稳定的 DS 执行身份");
            writerParam.put("upsertExecutionIdentity", taskRequest.getProjectCode() + "/"
                    + taskRequest.getWorkflowDefinitionCode() + "/" + taskRequest.getWorkflowInstanceId() + "/" + taskRequest.getTaskName());
            writerParam.put("upsertSourceIdentity", dataXParameters.getDsType() + "/" + dataXParameters.getDataSource()
                    + "/" + dataXParameters.getDataTarget() + "/" + dataXParameters.getSql());
        }
'''+marker)
    old='writer.put("name", DataxUtils.getWriterPluginName(dataxTaskExecutionContext.getTargetType()));'
    assert text.count(old)==1
    text=text.replace(old,'writer.put("name", "datahubpostgresqlwriter");')
    with tempfile.TemporaryDirectory(prefix='datahub-upsert-build-') as directory:
        stage=Path(directory)
        for item in here.glob('*.java'):shutil.copy2(item,stage/item.name)
        for name in ('PostgresUpsertSql.java','PostgresUpsertHooks.java','PostgresUpsertPublication.java'):
            shared=helper.parent/name;shutil.copy2(shared,stage/shared.name)
        shutil.copy2(args.lombok,stage/'lombok.jar')
        (stage/'DatahubDataxUpsertTask.java').write_text(text)
        remote='/tmp/datahub-upsert-build'
        call('exec','--user','root',args.container,'rm','-rf',remote)
        call('exec','--user','root',args.container,'mkdir','-p',remote)
        call('cp',str(stage)+'/.',args.container+':'+remote)
        # 使用固定运行时自己的依赖编译，独立目录不影响正在运行的原插件。
        script='''set -eu
cd /tmp/datahub-upsert-build
rm -rf classes bundle
mkdir -p classes bundle/opt/dolphinscheduler/plugins/task-plugins bundle/opt/datax/plugin/writer/datahubpostgresqlwriter/libs
cp=$(find /opt/dolphinscheduler/libs /opt/dolphinscheduler/plugins/task-plugins /opt/datax/plugin/writer/postgresqlwriter /opt/datax/lib -name '*.jar' -printf '%p:' )
/opt/java/openjdk/bin/javac -source 8 -target 8 -encoding UTF-8 -cp "$cp:lombok.jar" -processorpath lombok.jar -d classes *.java
/opt/java/openjdk/bin/java -cp "classes:$cp" RuntimeContractTest
mkdir -p classes/META-INF/services
echo org.apache.dolphinscheduler.plugin.task.datax.DatahubDataxUpsertTaskChannelFactory > classes/META-INF/services/org.apache.dolphinscheduler.plugin.task.api.TaskChannelFactory
cp /opt/dolphinscheduler/plugins/task-plugins/dolphinscheduler-task-datax-3.4.2-shade.jar bundle/opt/dolphinscheduler/plugins/task-plugins/datahub-task-datax-upsert-3.4.2-v1.jar
/opt/java/openjdk/bin/jar uf bundle/opt/dolphinscheduler/plugins/task-plugins/datahub-task-datax-upsert-3.4.2-v1.jar -C classes org -C classes META-INF
/opt/java/openjdk/bin/jar cf bundle/opt/datax/plugin/writer/datahubpostgresqlwriter/datahub-postgresqlwriter-v1.jar -C classes cn
cp /opt/datax/plugin/writer/postgresqlwriter/libs/*.jar bundle/opt/datax/plugin/writer/datahubpostgresqlwriter/libs/
'''
        call('exec','--user','root',args.container,'bash','-c',script)
        plugin={'name':'datahubpostgresqlwriter','class':'cn.iocoder.yudao.runtime.datax.DatahubPostgresqlWriter','description':'PostgreSQL 唯一键幂等批次写入 V1','developer':'DataHub'}
        (stage/'plugin.json').write_text(json.dumps(plugin,ensure_ascii=False))
        call('cp',str(stage/'plugin.json'),args.container+':'+remote+'/bundle/opt/datax/plugin/writer/datahubpostgresqlwriter/plugin.json')
        call('exec','--user','root',args.container,'tar','-czf',remote+'/datax-upsert.tar.gz','-C',remote+'/bundle','opt')
        args.output.parent.mkdir(parents=True,exist_ok=True)
        call('cp',args.container+':'+remote+'/datax-upsert.tar.gz',str(args.output))
        print(json.dumps({'output':str(args.output),'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),'upstreamSha256':SOURCE_SHA256}))

if __name__=='__main__':main()
