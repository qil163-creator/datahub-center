#!/usr/bin/env python3
"""以目标Spark API和固定SDK构建Java17批消息桥，默认仅产物验证，不改工作节点。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape


def run(*arguments):
    result = subprocess.run(arguments, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout[-5000:] + result.stderr[-5000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='components-stable-ds')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--install', action='store_true')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    core = module / 'src/main/java/cn/iocoder/yudao/framework/etl/core'
    sources = [module / ('runtime/' + name) for name in ['flink/bounded/ManagedFlinkMessageTransport.java', 'spark/EtlBoundedMessageProvider.java',
               'spark/EtlSparkRowValues.java', 'rabbitmq/ManagedRabbitConnection.java', 'rocketmq/ManagedRocketResource.java', 'messages-smoke/BoundedMessageContractProbe.java', 'messages-smoke/BoundedMessageSparkPlanProbe.java', 'messages-smoke/BoundedMessageKafkaFixture.java', 'messages-smoke/BoundedMessageRabbitFixture.java']]
    sources += [core / ('executor/support/' + name + '.java') for name in ['EtlBoundedConnectorTransport', 'EtlManagedSecrets', 'EtlNativeRowGuard']]
    snapshots = [(source, source.read_bytes()) for source in sources]
    digest = hashlib.sha256(b''.join(data for source, data in snapshots)).hexdigest()
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    paths = json.loads(run('docker', 'exec', options.container, 'python3', '-c', "import glob,json;names=['spark-sql_','spark-sql-api_','spark-catalyst_','spark-core_','spark-common-utils_','spark-unsafe_','scala-library-','spark-tags_','slf4j-api-','kryo-shaded-'];print(json.dumps([p for p in glob.glob('/opt/spark/jars/*.jar') if any(p.rsplit('/',1)[-1].startswith(n) for n in names)]))").splitlines()[-1])
    if not paths:
        raise RuntimeError('目标Spark API未安装')
    with tempfile.TemporaryDirectory(prefix='datahub-bounded-messages-') as temporary:
        project = Path(temporary); java = project / 'src/main/java'; java.mkdir(parents=True)
        for source, data in snapshots:
            (java / source.name).write_bytes(data)
        service = project / 'src/main/resources/META-INF/services'; service.mkdir(parents=True)
        (service / 'cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport').write_bytes((module / 'runtime/flink/bounded/messages.provider').read_bytes())
        dependencies = []; provided = project / 'provided'; provided.mkdir()
        for index, remote in enumerate(paths):
            local = provided / Path(remote).name; run('docker', 'cp', options.container + ':' + remote, str(local))
            dependencies.append('<dependency><groupId>datahub.provided.spark</groupId><artifactId>api-' + str(index) + '</artifactId><version>1</version><scope>system</scope><systemPath>' + escape(str(local)) + '</systemPath></dependency>')
        versions = [('org.apache.kafka', 'kafka-clients', '4.2.1'), ('com.rabbitmq', 'amqp-client', '5.13.1'),
                    ('org.apache.rocketmq', 'rocketmq-client', '5.3.2'), ('org.apache.rocketmq', 'rocketmq-acl', '5.3.2'),
                    ('com.taosdata.jdbc', 'taos-jdbcdriver', '3.7.3'), ('cn.hutool', 'hutool-all', '5.8.47')]
        exclusions = ''.join('<exclusion><groupId>' + value + '</groupId><artifactId>*</artifactId></exclusion>' for value in ['org.slf4j', 'org.apache.logging.log4j', 'ch.qos.logback'])
        for group, artifact, version in versions:
            dependencies.append('<dependency><groupId>' + group + '</groupId><artifactId>' + artifact + '</artifactId><version>' + version + '</version><exclusions>' + exclusions + '</exclusions></dependency>')
        relocations = ''.join('<relocation><pattern>' + package + '</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.messages.shaded.' + package + '</shadedPattern></relocation>' for package in ['org.apache.kafka', 'org.apache.rocketmq', 'org.apache.commons', 'org.jctools', 'com', 'io', 'net.jpountz', 'org.xerial', 'org.lz4'])
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>cn.iocoder.datahub.runtime</groupId><artifactId>managed-bounded-messages</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>''' + ''.join(dependencies) + '''</dependencies><build><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><proc>none</proc></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration><createDependencyReducedPom>false</createDependencyReducedPom>
<artifactSet><excludes><exclude>datahub.provided.spark:*</exclude></excludes></artifactSet><filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/versions/*/module-info.class</exclude></excludes></filter></filters><relocations>''' + relocations + '''</relocations><transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/></transformers>
</configuration></execution></executions></plugin></plugins></build></project>'''
        (project / 'pom.xml').write_text(pom)
        build = subprocess.run(['mvn', '-q', '-f', str(project / 'pom.xml'), 'package', '-DskipTests', '-Dstyle.color=never'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output.parent / (output.stem + '-build.log')).write_text(build.stdout); build.check_returncode()
        jar = project / 'target/managed-bounded-messages-1.0.0.jar'
        # 纯合同探针不访问外部服务、不读取秘密，实际SDK类也必须能被工作端JVM加载。
        probe = run('java', '-cp', str(jar) + os.pathsep + str(provided / '*'), 'cn.iocoder.yudao.framework.etl.messages.BoundedMessageContractProbe')
        data = jar.read_bytes(); checksum = hashlib.sha256(data).hexdigest(); artifact = output.parent / ('managed-bounded-messages-' + checksum[:16] + '.jar')
        artifact.write_bytes(data)
        if hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest() != digest:
            raise RuntimeError('构建期间源码变化，拒绝发布混合版本')
    destinations = ['/opt/spark/jars/datahub-managed-bounded-messages.jar', '/opt/flink/lib/datahub-managed-bounded-messages.jar']
    if options.install:
        for destination in destinations:
            stage = '/tmp/datahub-bounded-messages-' + checksum[:16] + '.jar'; run('docker', 'cp', str(artifact), options.container + ':' + stage)
            run('docker', 'exec', options.container, 'chmod', '0644', stage); run('docker', 'exec', options.container, 'mv', stage, destination)
    result = {'sourceSha256': digest, 'jarSha256': checksum, 'artifact': str(artifact), 'workerJarPaths': destinations, 'installed': options.install,
              'javaRelease': 17, 'sparkVersion': '3.5.6', 'flinkFactoryDependency': 'datahub-bounded', 'kinds': ['kafka', 'rabbitmq', 'rocketmq', 'tdengine'],
              'probe': probe, 'credentialEnvironment': ['DATAHUB_ETL_SECRET_DIR', 'DATAHUB_ETL_SECRET_REFS'],
              'semantics': 'Kafka/Rocket固定offset边界且不提交消费位点；Rabbit启动基数独占浏览后重入队；TDengine固定表JDBC；输出确认后至少一次，重试可能重复。',
              'classIdentity': 'Hutool、EtlBoundedConnectorTransport及EtlNativeRowGuard保留原包；仅SDK重定位，不包含Flink/Spark API。'}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(output), 'jarSha256': checksum, 'installed': options.install, 'probe': probe}, ensure_ascii=False))


if __name__ == '__main__':
    main()
