#!/usr/bin/env python3
"""以目标 Flink API 构建 Java17 RocketMQ 受管表适配，隔离官方 SDK 依赖。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
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
    sources = sorted((module / 'runtime/rocketmq').glob('*.java')) + [module / 'runtime/common/EtlJsonRowGuard.java', module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedSecrets.java']
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    provided = json.loads(run('docker', 'exec', options.container, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')+glob.glob('/opt/flink/lib/slf4j-api-*.jar')))").splitlines()[-1])
    if not provided:
        raise RuntimeError('未找到目标 Flink API')
    with tempfile.TemporaryDirectory(prefix='datahub-rocket-build-') as directory:
        project = Path(directory); (project / 'src/main/java').mkdir(parents=True)
        for source in sources:
            (project / 'src/main/java' / source.name).write_bytes(source.read_bytes())
        (project / 'provided').mkdir(); dependencies = []
        for index, remote in enumerate(provided):
            local = project / 'provided' / Path(remote).name; run('docker', 'cp', options.container + ':' + remote, str(local))
            dependencies.append('<dependency><groupId>datahub.provided.flink</groupId><artifactId>api-' + str(index) + '</artifactId><version>1</version><scope>system</scope><systemPath>' + escape(str(local)) + '</systemPath></dependency>')
        exclusions = ''.join('<exclusion><groupId>' + value + '</groupId><artifactId>*</artifactId></exclusion>' for value in ['org.apache.flink', 'org.slf4j', 'org.apache.logging.log4j'])
        for artifact in ['rocketmq-client', 'rocketmq-acl']:
            dependencies.append('<dependency><groupId>org.apache.rocketmq</groupId><artifactId>' + artifact + '</artifactId><version>5.3.2</version><exclusions>' + exclusions + '</exclusions></dependency>')
        relocations = ''.join('<relocation><pattern>' + value + '</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.rocketmq.shaded.' + value + '</shadedPattern></relocation>' for value in ['org.apache.rocketmq', 'org.apache.commons', 'org.jctools', 'com', 'io'])
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>cn.iocoder.datahub.runtime</groupId><artifactId>managed-rocket-native</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>''' + ''.join(dependencies) + '''</dependencies><build><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><proc>none</proc></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration>
<createDependencyReducedPom>false</createDependencyReducedPom><artifactSet><excludes><exclude>datahub.provided.flink:*</exclude></excludes></artifactSet>
<filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude></excludes></filter></filters><relocations>''' + relocations + '''</relocations><transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/></transformers>
</configuration></execution></executions></plugin></plugins></build></project>'''
        (project / 'pom.xml').write_text(pom)
        result = subprocess.run(['mvn', '-q', '-f', str(project / 'pom.xml'), 'package', '-DskipTests', '-Dstyle.color=never'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output.parent / (output.stem + '-build.log')).write_text(result.stdout); result.check_returncode()
        jar = project / 'target/managed-rocket-native-1.0.0.jar'; service = project / 'services/META-INF/services'; service.mkdir(parents=True)
        (service / 'org.apache.flink.table.factories.Factory').write_text('cn.iocoder.yudao.framework.etl.flink.rocketmq.ManagedRocketFactory\n')
        run('jar', '--update', '--file', str(jar), '-C', str(project / 'services'), '.')
        contents = jar.read_bytes(); checksum = hashlib.sha256(contents).hexdigest(); artifact = output.parent / ('managed-rocket-native-' + checksum[:16] + '.jar'); artifact.write_bytes(contents)
    destination = '/opt/flink/lib/datahub-managed-rocket.jar'
    if options.install:
        stage = '/tmp/datahub-managed-rocket-' + checksum[:16] + '.jar'; run('docker', 'cp', str(artifact), options.container + ':' + stage)
        run('docker', 'exec', options.container, 'chmod', '0644', stage); run('docker', 'exec', options.container, 'mv', stage, destination)
    manifest = {'sdk': ['org.apache.rocketmq:rocketmq-client:5.3.2', 'org.apache.rocketmq:rocketmq-acl:5.3.2'], 'javaRelease': 17,
        'providedFlinkVersion': '1.20.3', 'sourceSha256': hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest(),
        'jarSha256': checksum, 'artifact': str(artifact), 'workerJarPath': destination, 'installed': options.install, 'factory': 'datahub-rocketmq',
        'credentialEnvironment': ['DATAHUB_ETL_SECRET_DIR', 'DATAHUB_ETL_SECRET_REFS'],
        'isolation': '官方RocketMQ及第三方SDK包重定位；Flink API来自目标引擎，构建和规划均不读取凭据。'}
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'); print(json.dumps({'output': str(output), 'jarSha256': checksum, 'installed': options.install}))


if __name__ == '__main__':
    main()
