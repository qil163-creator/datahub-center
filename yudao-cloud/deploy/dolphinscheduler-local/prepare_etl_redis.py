#!/usr/bin/env python3
"""使用目标 Spark SDK 和官方 Jedis 构建隔离 Redis 原生组件，不打包秘密。"""
import argparse
import hashlib
import json
import io
import zipfile
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
    core = module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support'
    sources = sorted((module / 'runtime/redis').glob('*.java')) + [module / 'runtime/spark/EtlSparkRowValues.java']
    sources += [core / (name + '.java') for name in ['EtlManagedRedis', 'EtlManagedSecrets', 'EtlNativeRowGuard']]
    snapshots = [(source, source.read_bytes()) for source in sources]
    source_digest = hashlib.sha256(b''.join(data for source, data in snapshots)).hexdigest()
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    provided = json.loads(run('docker', 'exec', options.container, 'python3', '-c',
        "import glob,json;print(json.dumps(sum([glob.glob('/opt/spark/jars/'+x) for x in ['spark-sql_*.jar','spark-sql-api_*.jar','spark-catalyst_*.jar','spark-core_*.jar','spark-common-utils_*.jar','spark-unsafe_*.jar','scala-library-*.jar','hive-exec-*.jar','hive-serde-*.jar','hive-common-*.jar','hadoop-client-api-*.jar','slf4j-api-*.jar','kryo-shaded-*.jar']],[])))").splitlines()[-1])
    with tempfile.TemporaryDirectory(prefix='datahub-redis-build-') as temporary:
        project = Path(temporary); (project / 'src/main/java').mkdir(parents=True); (project / 'provided').mkdir()
        for source, data in snapshots: (project / 'src/main/java' / source.name).write_bytes(data)
        dependencies = []
        for index, remote in enumerate(provided):
            checksum = run('docker', 'exec', options.container, 'sha256sum', remote).split()[0]
            cache = Path(tempfile.gettempdir()) / 'datahub-spark-sdk-cache'; cache.mkdir(mode=0o700, exist_ok=True)
            local = cache / (checksum + '-' + Path(remote).name)
            if not local.exists() or hashlib.sha256(local.read_bytes()).hexdigest() != checksum:
                run('docker', 'cp', options.container + ':' + remote, str(local))
            if hashlib.sha256(local.read_bytes()).hexdigest() != checksum: raise RuntimeError('目标SDK缓存校验失败')
            dependencies.append('<dependency><groupId>datahub.provided.spark</groupId><artifactId>api-' + str(index) + '</artifactId><version>1</version><scope>system</scope><systemPath>' + escape(str(local)) + '</systemPath></dependency>')
        relocations = ''.join('<relocation><pattern>' + old + '</pattern><shadedPattern>cn.iocoder.yudao.framework.etl.redis.shaded.' + new + '</shadedPattern></relocation>' for old, new in [('redis.clients','clients'),('org.apache.commons.pool2','pool'),('org.json','json'),('com.google.gson','gson'),('cn.hutool','hutool'),('cn.iocoder.yudao.framework.etl.core.executor.support','core'),('cn.iocoder.yudao.framework.etl.spark.EtlSparkRowValues','SparkRowValues')])
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>cn.iocoder.datahub.runtime</groupId><artifactId>managed-redis-native</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>''' + ''.join(dependencies) + '''
<dependency><groupId>redis.clients</groupId><artifactId>jedis</artifactId><version>7.4.1</version><exclusions><exclusion><groupId>org.slf4j</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>
<dependency><groupId>cn.hutool</groupId><artifactId>hutool-all</artifactId><version>5.8.47</version></dependency></dependencies><build><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><proc>none</proc></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration><createDependencyReducedPom>false</createDependencyReducedPom><artifactSet><excludes><exclude>datahub.provided.spark:*</exclude></excludes></artifactSet>
<filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.RSA</exclude><exclude>META-INF/*.DSA</exclude></excludes></filter></filters><relocations>''' + relocations + '''</relocations>
<transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/></transformers></configuration></execution></executions></plugin></plugins></build></project>'''
        (project / 'pom.xml').write_text(pom)
        build = subprocess.run(['mvn', '-q', '-f', str(project / 'pom.xml'), 'package', '-DskipTests', '-Dstyle.color=never'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output.parent / (output.stem + '-build.log')).write_text(build.stdout); build.check_returncode()
        artifact = project / 'target/managed-redis-native-1.0.0.jar'; contents = artifact.read_bytes()
        with zipfile.ZipFile(io.BytesIO(contents)) as archive:
            names = archive.namelist()
            if any(value.startswith('cn/iocoder/yudao/framework/etl/core/executor/support/') or value == 'cn/iocoder/yudao/framework/etl/spark/EtlSparkRowValues.class' for value in names):
                raise RuntimeError('运行JAR含未隔离的公共helper，会污染其他组件类加载')
            if 'cn/iocoder/yudao/framework/etl/redis/shaded/core/EtlManagedRedis.class' not in names:
                raise RuntimeError('运行JAR缺失隔离后的核心执行组件')
        checksum = hashlib.sha256(contents).hexdigest()
        local = output.parent / ('datahub-etl-redis-' + checksum[:16] + '.jar'); local.write_bytes(contents)
    worker = '/opt/spark/jars/datahub-etl-redis.jar'
    if options.install:
        if hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest() != source_digest:
            raise RuntimeError('构建期间源文件已变化，请重建后再部署；禁止错误的源码与运行包对应关系')
        run('docker', 'cp', str(local), options.container + ':' + worker)
        destination = Path(__file__).resolve().parent / 'runtime/redis/datahub-etl-redis.jar'
        destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(contents)
    result = {'connector': 'redis.clients:jedis:7.4.1', 'javaRelease': 17, 'sparkVersion': '3.5.6',
        'sourceSha256': source_digest, 'jarSha256': checksum,
        'artifact': str(local), 'workerJarPath': worker, 'installed': options.install, 'forbiddenOriginalHelperClassCount': 0,
        'credentialEnvironment': ['DATAHUB_ETL_SECRET_DIR', 'DATAHUB_ETL_SECRET_REFS'],
        'sparkInitializationSql': "CREATE TEMPORARY FUNCTION DH_READ_REDIS AS 'cn.iocoder.yudao.framework.etl.spark.EtlRedisRowsFunction' USING JAR 'file://" + worker + "';"}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
