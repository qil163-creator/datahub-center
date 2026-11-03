#!/usr/bin/env python3
"""使用目标Spark API构建Java17 FTP输入/输出，重定位网络依赖后发布受管JAR。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


def run(*arguments):
    result = subprocess.run(arguments, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr[-5000:] + result.stdout[-5000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path, default=Path('/tmp/datahub-etl-ftp-binding.json'))
    parser.add_argument('--install', action='store_true')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    sources = [module / ('runtime/spark/' + name) for name in ['EtlFtpRowsFunction.java', 'EtlFtpTextProvider.java', 'EtlSparkRowValues.java']]
    sources += [module / ('src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/' + name) for name in ['EtlManagedFtp.java', 'EtlManagedSecrets.java', 'EtlNativeRowGuard.java']]
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    bom = ET.parse(root / 'yudao-dependencies/pom.xml').getroot()
    versions = {'hutool': bom.findtext('m:properties/m:hutool-5.version', namespaces=ns),
                'commons-net': bom.findtext('m:properties/m:commons-net.version', namespaces=ns)}
    if any(not version for version in versions.values()):
        raise RuntimeError('当前BOM未声明FTP运行依赖版本')
    entries = ET.parse(root / 'pom.xml').getroot().findall('m:repositories/m:repository', ns)
    repositories = [(entry.findtext('m:id', namespaces=ns), entry.findtext('m:url', namespaces=ns)) for entry in entries]
    repository_xml = '<repositories>' + ''.join('<repository><id>' + escape(identity) + '</id><url>' + escape(url) + '</url></repository>' for identity, url in repositories) + '</repositories>'
    repository_xml += '<pluginRepositories>' + ''.join('<pluginRepository><id>' + escape(identity) + '-plugins</id><url>' + escape(url) + '</url></pluginRepository>' for identity, url in repositories) + '</pluginRepositories>'
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='datahub-ftp-build-') as temporary:
        project = Path(temporary); source_dir = project / 'src/main/java'; source_dir.mkdir(parents=True)
        for source in sources:
            (source_dir / source.name).write_bytes(source.read_bytes())
        api = project / 'provided'; api.mkdir(); provided = []
        patterns = ['spark-sql_*.jar', 'spark-sql-api_*.jar', 'spark-catalyst_*.jar', 'spark-core_*.jar', 'spark-common-utils_*.jar', 'spark-unsafe_*.jar',
                    'scala-library-*.jar', 'hadoop-client-api-*.jar', 'slf4j-api-*.jar', 'kryo-shaded-*.jar', 'hive-exec-*.jar', 'hive-serde-*.jar']
        for index, pattern in enumerate(patterns):
            paths = run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/spark/jars/' + pattern).splitlines()
            if len(paths) != 1: raise RuntimeError('目标Spark API必须唯一：' + pattern)
            local = api / Path(paths[0]).name; run('docker', 'cp', options.container + ':' + paths[0], str(local))
            provided.append('<dependency><groupId>datahub.provided.spark</groupId><artifactId>api-' + str(index) + '</artifactId><version>3.5.6</version><scope>system</scope><systemPath>' + escape(str(local)) + '</systemPath></dependency>')
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>cn.iocoder.datahub.runtime</groupId><artifactId>managed-ftp-native</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding><project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp></properties>''' + repository_xml + '<dependencies>' + ''.join(provided) + '''
<dependency><groupId>cn.hutool</groupId><artifactId>hutool-all</artifactId><version>''' + versions['hutool'] + '''</version></dependency>
<dependency><groupId>commons-net</groupId><artifactId>commons-net</artifactId><version>''' + versions['commons-net'] + '''</version></dependency></dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><proc>none</proc></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration>
<createDependencyReducedPom>false</createDependencyReducedPom><artifactSet><excludes><exclude>datahub.provided.spark:*</exclude></excludes></artifactSet>
<filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude></excludes></filter></filters>
<relocations><relocation><pattern>cn.hutool</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.vendor.ftp.hutool</shadedPattern></relocation>
<relocation><pattern>org.apache.commons.net</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.vendor.ftp.net</shadedPattern></relocation>
<relocation><pattern>org.apache.commons.io</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.vendor.ftp.io</shadedPattern></relocation>
<relocation><pattern>cn.iocoder.yudao.framework.etl.core.executor.support</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.vendor.ftp.support</shadedPattern></relocation>
<relocation><pattern>cn.iocoder.yudao.framework.etl.spark.EtlSparkRowValues</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.vendor.ftp.EtlSparkRowValues</shadedPattern></relocation></relocations>
</configuration></execution></executions></plugin></plugins></build></project>'''
        (project / 'pom.xml').write_text(pom)
        repository_url = next((url for identity, url in repositories if 'aliyun' in identity.lower()), repositories[0][1])
        (project / 'settings.xml').write_text('<settings><mirrors><mirror><id>datahub-runtime</id><mirrorOf>*</mirrorOf><url>' + escape(repository_url) + '</url></mirror></mirrors></settings>')
        with Path('/tmp/datahub-ftp-build.log').open('w') as log:
            result = subprocess.run(['mvn', '-B', '-s', str(project / 'settings.xml'), '-f', str(project / 'pom.xml'), 'package', '-DskipTests', '-Dstyle.color=never'], stdout=log, stderr=subprocess.STDOUT, timeout=300)
        result.check_returncode()
        artifact = project / 'target/managed-ftp-native-1.0.0.jar'; content = artifact.read_bytes(); digest = hashlib.sha256(content).hexdigest()
        local = Path(__file__).resolve().parent / 'runtime/ftp-native' / ('datahub-etl-ftp-' + digest[:16] + '.jar'); local.parent.mkdir(parents=True, exist_ok=True); local.write_bytes(content)
    destination = '/opt/spark/jars/datahub-etl-ftp.jar'
    if options.install:
        run('docker', 'cp', str(local), options.container + ':' + destination)
        run('docker', 'exec', options.container, 'chmod', '0644', destination)
    manifest = {'sourceSha256': hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest(), 'jarSha256': digest,
                'jarPath': destination, 'javaRelease': 17, 'providedSparkVersion': '3.5.6', 'dependencyVersions': versions, 'installed': options.install,
                'managedFunctions': ['DH_READ_FTP'], 'sparkInitializationSql': "CREATE TEMPORARY FUNCTION DH_READ_FTP AS 'cn.iocoder.yudao.framework.etl.spark.EtlFtpRowsFunction' USING JAR 'file://" + destination + "';",
                'authorizationEnvironment': ['DATAHUB_ETL_FTP_ROOTS', 'DATAHUB_ETL_SECRET_DIR', 'DATAHUB_ETL_SECRET_REFS'],
                'resourcePrerequisite': 'FTP专用账户已在服务端chroot；输出要求MLST和STOU，TLS保留证书与主机名验证。'}
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'); print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
