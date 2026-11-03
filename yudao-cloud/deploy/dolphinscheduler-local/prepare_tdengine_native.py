#!/usr/bin/env python3
"""在独立临时 Maven 项目构建 Java17 TDengine 受管 connector，隔离厂商运行依赖。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


def run(*arguments):
    return subprocess.check_output(arguments, text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--install', action='store_true')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    repository_entries = ET.parse(root / 'pom.xml').getroot().findall('m:repositories/m:repository', ns)
    repositories = [(entry.findtext('m:id', namespaces=ns), entry.findtext('m:url', namespaces=ns)) for entry in repository_entries]
    repository_xml = '<repositories>' + ''.join('<repository><id>' + escape(identity) + '</id><url>' + escape(url) + '</url></repository>' for identity, url in repositories) + '</repositories>'
    repository_xml += '<pluginRepositories>' + ''.join('<pluginRepository><id>' + escape(identity) + '-plugins</id><url>' + escape(url) + '</url></pluginRepository>' for identity, url in repositories) + '</pluginRepositories>'
    sources = [module / 'runtime/tdengine/ManagedTdengineFactory.java', module / 'runtime/tdengine/ManagedTdengineSource.java', module / 'runtime/tdengine/ManagedTdengineDeserializer.java',
               module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedSecrets.java']
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    provided = json.loads(run('docker', 'exec', options.container, 'python3', '-c',
                             "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')))").splitlines()[-1])
    if not provided:
        raise RuntimeError('未找到目标 Flink 官方 API')
    with tempfile.TemporaryDirectory(prefix='datahub-tdengine-build-') as directory:
        project = Path(directory); (project / 'src/main/java').mkdir(parents=True)
        for source in sources:
            (project / 'src/main/java' / source.name).write_bytes(source.read_bytes())
        (project / 'provided').mkdir(); dependencies = []
        for index, remote in enumerate(provided):
            local = project / 'provided' / Path(remote).name
            run('docker', 'cp', options.container + ':' + remote, str(local))
            dependencies.append('<dependency><groupId>datahub.provided.flink</groupId><artifactId>api-' + str(index)
                                + '</artifactId><version>1.20.3</version><scope>system</scope><systemPath>'
                                + escape(str(local)) + '</systemPath></dependency>')
        relocations = ''.join('<relocation><pattern>' + prefix + '</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.vendor.tdengine.' + prefix + '</shadedPattern></relocation>'
                              for prefix in ['com.taosdata', 'com.google', 'com.fasterxml', 'io.netty', 'org.apache.commons', 'org.java_websocket', 'org.apache.http', 'org.apache.hc', 'org.reactivestreams', 'org.jetbrains'])
        excluded = ''.join('<exclusion><groupId>' + group + '</groupId><artifactId>*</artifactId></exclusion>'
                           for group in ['org.apache.flink', 'org.scala-lang', 'org.slf4j', 'org.apache.logging.log4j',
                                         'org.testcontainers', 'com.github.docker-java', 'org.junit.jupiter', 'org.junit.platform',
                                         'org.junit.vintage', 'junit', 'org.mockito', 'org.opentest4j', 'org.apiguardian', 'org.awaitility'])
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>cn.iocoder.datahub.runtime</groupId><artifactId>managed-tdengine-native</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>''' + repository_xml + '''
<dependencies>''' + ''.join(dependencies) + '''<dependency><groupId>com.taosdata.flink</groupId><artifactId>flink-connector-tdengine</artifactId><version>2.1.4</version><exclusions>''' + excluded + '''</exclusions></dependency></dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><proc>none</proc></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration>
<createDependencyReducedPom>false</createDependencyReducedPom><artifactSet><excludes><exclude>datahub.provided.flink:*</exclude></excludes></artifactSet>
<filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/services/org.apache.flink.table.factories.Factory</exclude></excludes></filter></filters>
<relocations>''' + relocations + '''</relocations><transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/></transformers>
</configuration></execution></executions></plugin></plugins></build></project>'''
        (project / 'pom.xml').write_text(pom)
        repository_url = next((url for identity, url in repositories if 'aliyun' in identity.lower()), repositories[0][1])
        (project / 'settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors><mirror><id>aliyun-mirror</id><mirrorOf>*</mirrorOf><url>' + escape(repository_url) + '</url></mirror></mirrors></settings>')
        with (output.parent / (output.stem + '-build.log')).open('w') as build_log:
            build = subprocess.run(['mvn', '-B', '-s', str(project / 'settings.xml'), '-f', str(project / 'pom.xml'), 'package', '-DskipTests', '-Dstyle.color=never'], text=True, stdout=build_log, stderr=subprocess.STDOUT, timeout=300)
        build.check_returncode()
        jar = project / 'target/managed-tdengine-native-1.0.0.jar'
        service = project / 'services/META-INF/services'; service.mkdir(parents=True)
        (service / 'org.apache.flink.table.factories.Factory').write_text('cn.iocoder.yudao.framework.etl.flink.tdengine.ManagedTdengineFactory\n')
        run('jar', '--update', '--file', str(jar), '-C', str(project / 'services'), '.')
        # 精确包前缀之外，提交等 wire 常量必须保持原值，防止重定位误改 commit_offset。
        action_code = run('javap', '-c', '-p', '-classpath', str(jar), 'cn.iocoder.yudao.framework.etl.vendor.tdengine.com.taosdata.jdbc.ws.tmq.ConsumerAction')
        wire_actions = ['commit', 'commit_offset', 'committed', 'subscribe', 'unsubscribe', 'poll', 'seek']
        if any(not any(line.rstrip().endswith('// String ' + action) for line in action_code.splitlines()) for action in wire_actions):
            raise RuntimeError('TDengine 网络动作名在打包后不再符合官方协议')
        contents = jar.read_bytes(); checksum = hashlib.sha256(contents).hexdigest()
        artifact = output.parent / ('managed-tdengine-native-' + checksum[:16] + '.jar'); artifact.write_bytes(contents)
    destination = '/opt/flink/lib/datahub-managed-tdengine.jar'
    if options.install:
        stage = '/tmp/datahub-managed-tdengine-' + checksum[:16] + '.jar'
        run('docker', 'cp', str(artifact), options.container + ':' + stage)
        run('docker', 'exec', options.container, 'chmod', '0644', stage)
        run('docker', 'exec', options.container, 'mv', stage, destination)
    manifest = {'connector': 'com.taosdata.flink:flink-connector-tdengine:2.1.4', 'jdbc': '3.7.3',
                'javaRelease': 17, 'providedFlinkVersion': '1.20.3', 'sourceSha256': hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest(),
                'jarSha256': checksum, 'artifact': str(artifact), 'workerJarPath': destination, 'installed': options.install,
                'factory': 'datahub-tdengine-tmq', 'wireActionsVerified': wire_actions, 'credentialEnvironment': ['DATAHUB_ETL_SECRET_DIR', 'DATAHUB_ETL_SECRET_REFS'],
                'isolation': '厂商 connector/JDBC/第三方包重定位，Flink API由目标引擎提供；凭据仅在reader/enumerator运行端按引用读取。'}
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
