#!/usr/bin/env python3
"""使用目标 Flink 官方 SDK 构建 Java17 有界连接器；不解析或复制任何业务凭据。"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
import zipfile

from prepare_etl_functions import install_immutable


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
    core = module / 'src/main/java/cn/iocoder/yudao/framework/etl/core'
    names = ['ManagedFlinkBoundedFactory', 'ManagedFlinkBoundedTransport', 'ManagedFlinkRows', 'ManagedFlinkFileRows',
             'ManagedFlinkTextStage', 'ManagedFlinkSinks', 'ManagedFlinkHdfs', 'ManagedFlinkMongo']
    sources = [module / ('runtime/flink/bounded/' + name + '.java') for name in names]
    sources += [core / ('executor/support/' + name + '.java') for name in ['EtlBoundedConnectorTransport', 'EtlManagedSecrets',
                'EtlNativeRowGuard', 'EtlHttpRows', 'EtlWebServiceRows', 'EtlManagedHttp', 'EtlManagedFtp', 'EtlManagedRedis',
                'EtlManagedOdps', 'EtlOdpsTransport', 'EtlExcelCellReader']]
    sources += [core / ('file/' + name + '.java') for name in ['EtlBoundedExcelReader', 'EtlManagedExcelInput', 'EtlManagedLocalInput', 'EtlDelimitedRows']]
    sources += [core / ('script/' + name + '.java') for name in ['EtlScriptAuthenticatedHttp', 'EtlScriptBrokerClient',
                'EtlScriptBrokerTransport', 'EtlScriptBrokerProtocol', 'EtlScriptProtocol', 'EtlScriptTls']]
    sources += [core / 'http/EtlBoundedHttp.java']
    common = root / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-common/src/main/java/cn/iocoder/yudao/framework/dbswitch/common'
    sources += [common / path for path in ['util/BoundedRowCodec.java', 'util/JdbcTypesUtils.java', 'entity/JdbcArrayValue.java', 'entity/NativeCellValue.java', 'entity/MissingCellValue.java']]
    snapshots = [(source, source.read_bytes()) for source in sources]
    source_digest = hashlib.sha256(b''.join(data for source, data in snapshots)).hexdigest()
    output = options.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    bom = ET.parse(root / 'yudao-dependencies/pom.xml').getroot()
    net_version = bom.findtext('m:properties/m:commons-net.version', namespaces=ns)
    hutool_version = bom.findtext('m:properties/m:hutool-5.version', namespaces=ns)
    if not net_version or not hutool_version:
        raise RuntimeError('缺少当前平台的官方依赖版本')
    sdk = json.loads(run('docker', 'exec', options.container, 'python3', '-c',
        "import glob,json; print(json.dumps(glob.glob('/opt/flink/lib/*.jar')))").splitlines()[-1])
    with tempfile.TemporaryDirectory(prefix='datahub-flink-bounded-') as temporary:
        project = Path(temporary)
        java = project / 'src/main/java'
        java.mkdir(parents=True)
        for source, data in snapshots:
            (java / source.name).write_bytes(data)
        services = project / 'src/main/resources/META-INF/services'
        services.mkdir(parents=True)
        (services / 'org.apache.flink.table.factories.Factory').write_text('cn.iocoder.yudao.framework.etl.flink.bounded.ManagedFlinkBoundedFactory\n')
        (services / 'cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport').write_text('cn.iocoder.yudao.framework.etl.flink.bounded.ManagedFlinkBoundedTransport\n')
        dependencies = []
        sdk_bindings = []
        cache = Path(tempfile.gettempdir()) / 'datahub-flink-sdk-cache'
        cache.mkdir(mode=0o700, exist_ok=True)
        for index, remote in enumerate(sorted(sdk)):
            checksum = run('docker', 'exec', options.container, 'sha256sum', remote).split()[0]
            local = cache / (checksum + '-' + Path(remote).name)
            if not local.exists() or hashlib.sha256(local.read_bytes()).hexdigest() != checksum:
                run('docker', 'cp', options.container + ':' + remote, str(local))
            if hashlib.sha256(local.read_bytes()).hexdigest() != checksum:
                raise RuntimeError('目标 Flink SDK 摘要不一致')
            sdk_bindings.append({'name': Path(remote).name, 'sha256': checksum})
            dependencies.append('<dependency><groupId>datahub.provided.flink</groupId><artifactId>api-' + str(index)
                + '</artifactId><version>1</version><scope>system</scope><systemPath>' + escape(str(local)) + '</systemPath></dependency>')
        pinned = [('cn.hutool', 'hutool-all', hutool_version), ('commons-net', 'commons-net', net_version),
                  ('redis.clients', 'jedis', '7.4.1'), ('com.aliyun.odps', 'odps-sdk-core', '0.52.3-public'),
                  ('org.apache.poi', 'poi-ooxml', '4.1.2'), ('org.mongodb', 'mongodb-driver-sync', '5.6.1'),
                  ('org.apache.hadoop', 'hadoop-client-api', '3.3.4'), ('org.apache.hadoop', 'hadoop-client-runtime', '3.3.4')]
        for group, artifact, version in pinned:
            dependencies.append('<dependency><groupId>' + group + '</groupId><artifactId>' + artifact + '</artifactId><version>' + version
                + '</version><exclusions><exclusion><groupId>org.slf4j</groupId><artifactId>*</artifactId></exclusion>'
                + '<exclusion><groupId>org.apache.arrow</groupId><artifactId>*</artifactId></exclusion>'
                + '<exclusion><groupId>io.netty</groupId><artifactId>netty-all</artifactId></exclusion></exclusions></dependency>')
        relocations = ''.join('<relocation><pattern>' + old + '</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.flink.bounded.vendor.' + new + '</shadedPattern></relocation>'
            for old, new in [('com.aliyun.odps', 'odps'), ('com.aliyun.credentials', 'credentials'), ('com.aliyun.tea', 'tea'),
                             ('com.fasterxml.jackson', 'jackson'), ('org.codehaus.jackson', 'legacyjackson'), ('com.google.protobuf', 'protobuf'),
                             ('com.google.common', 'guava'), ('com.google.gson', 'gson'), ('redis.clients', 'redis'),
                             ('org.apache.commons.pool2', 'pool'), ('org.apache.commons.net', 'ftp'), ('com.mongodb', 'mongodb'), ('org.bson', 'bson')])
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>cn.iocoder.datahub.runtime</groupId><artifactId>managed-flink-bounded</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>''' + ''.join(dependencies) + '''</dependencies><build><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><proc>none</proc></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration>
<createDependencyReducedPom>false</createDependencyReducedPom><artifactSet><excludes><exclude>datahub.provided.flink:*</exclude></excludes></artifactSet>
<filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude></excludes></filter></filters><relocations>''' + relocations + '''</relocations>
<transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/></transformers></configuration></execution></executions></plugin>
</plugins></build></project>'''
        (project / 'pom.xml').write_text(pom)
        build = subprocess.run(['mvn', '-q', '-f', str(project / 'pom.xml'), 'package', '-DskipTests', '-Dstyle.color=never'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output.parent / (output.stem + '-build.log')).write_text(build.stdout)
        build.check_returncode()
        contents = (project / 'target/managed-flink-bounded-1.0.0.jar').read_bytes()
        with zipfile.ZipFile(io.BytesIO(contents)) as archive:
            if 'cn/iocoder/yudao/framework/etl/core/executor/support/EtlBoundedConnectorTransport.class' not in archive.namelist():
                raise RuntimeError('有界连接器缺少公共 SPI')
        checksum = hashlib.sha256(contents).hexdigest()
        artifact = output.parent / ('datahub-flink-bounded-' + checksum[:16] + '.jar')
        artifact.write_bytes(contents)
    worker = '/opt/flink/usrlib/datahub-flink-bounded-' + checksum[:16] + '.jar'
    if options.install:
        if hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest() != source_digest:
            raise RuntimeError('构建期间源码发生变化，请重新构建后部署')
        run('docker', 'exec', options.container, 'mkdir', '-p', '/opt/flink/usrlib')
        install_immutable(options.container, artifact, worker, checksum)
    result = {'sourceSha256': source_digest, 'jarSha256': checksum, 'artifact': str(artifact), 'workerJarPath': worker,
              'installed': options.install, 'javaRelease': 17, 'targetSdk': sdk_bindings,
              'plugins': ['flink-bounded-' + kind for kind in ['file', 'http', 'webservice', 'ftp', 'hdfs', 'mongodb', 'redis', 'odps']],
              'initializationSql': "ADD JAR 'file://" + worker + "';", 'dependencies': [':'.join(item) for item in pinned]}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
