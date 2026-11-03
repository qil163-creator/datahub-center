#!/usr/bin/env python3
"""使用目标 Flink 自带 API 编译 Java17 受管函数，发布不可变 JAR 并生成初始化绑定。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid


def run(*arguments):
    return subprocess.check_output(arguments, text=True).strip()


def install_immutable(container, source, destination, checksum):
    inspected = subprocess.run(['docker', 'exec', container, 'sha256sum', destination], text=True, capture_output=True)
    if inspected.returncode == 0:
        if inspected.stdout.split()[0] != checksum:
            raise RuntimeError('不可变工作节点依赖摘要冲突')
        return
    temporary = destination + '.part-' + uuid.uuid4().hex
    try:
        run('docker', 'cp', str(source), container + ':' + temporary)
        if run('docker', 'exec', container, 'sha256sum', temporary).split()[0] != checksum:
            raise RuntimeError('工作节点依赖复制摘要不一致')
        run('docker', 'exec', container, 'chmod', '0644', temporary)
        run('docker', 'exec', container, 'mv', '-n', temporary, destination)
        if run('docker', 'exec', container, 'sha256sum', destination).split()[0] != checksum:
            raise RuntimeError('不可变工作节点依赖发布冲突')
    finally:
        run('docker', 'exec', container, 'rm', '-f', temporary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--crypto', action='store_true')
    parser.add_argument('--http', action='store_true')
    parser.add_argument('--streaming-window', action='store_true')
    parser.add_argument('--hutool-jar', type=Path)
    parser.add_argument('--bcprov-jar', type=Path)
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    sources = [module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlRegexReplacement.java',
               module / 'runtime/flink/EtlRegexReplaceFunction.java',
               module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlTokenSplit.java',
               module / 'runtime/flink/EtlSplitTokenFunction.java',
               module / 'runtime/spark/EtlRegexReplaceFunction.java',
               module / 'runtime/spark/EtlSplitTokenFunction.java']
    dependencies = []
    if options.streaming_window:
        sources += [module / 'runtime/flink/EtlWindowPickFunction.java']
    if options.crypto or options.http:
        if not options.hutool_jar or (options.crypto and not options.bcprov_jar):
            parser.error('--crypto/--http 需要明确指定官方 --hutool-jar，加解密还需要 --bcprov-jar')
        dependencies = [options.hutool_jar.resolve()] + ([options.bcprov_jar.resolve()] if options.crypto else [])
        if any(not path.is_file() or path.suffix != '.jar' for path in dependencies):
            parser.error('加解密依赖必须是已解析的官方 JAR 文件')
        sources += [module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedSecrets.java']
    if options.crypto:
        sources += [module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlCryptoUtils.java',
                    module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedCrypto.java',
                    module / 'runtime/flink/EtlCryptoEncryptFunction.java', module / 'runtime/flink/EtlCryptoDecryptFunction.java',
                    module / 'runtime/spark/EtlCryptoEncryptFunction.java', module / 'runtime/spark/EtlCryptoDecryptFunction.java']
    if options.http:
        sources += [module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlHttpRows.java',
                    module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlNativeRowGuard.java',
                    module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedHttp.java',
                    module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlWebServiceRows.java',
                    module / 'runtime/spark/EtlHttpRowsFunction.java', module / 'runtime/spark/EtlWebServiceRowsFunction.java',
                    module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/http/EtlBoundedHttp.java']
        sources += [module / ('src/main/java/cn/iocoder/yudao/framework/etl/core/script/' + name + '.java') for name in
                    ['EtlScriptAuthenticatedHttp', 'EtlScriptBrokerClient', 'EtlScriptBrokerTransport', 'EtlScriptBrokerProtocol', 'EtlScriptProtocol', 'EtlScriptTls']]
        common = root / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-common/src/main/java/cn/iocoder/yudao/framework/dbswitch/common'
        sources += [common / path for path in ['util/BoundedRowCodec.java', 'util/JdbcTypesUtils.java', 'entity/JdbcArrayValue.java', 'entity/NativeCellValue.java', 'entity/MissingCellValue.java']]
    digest = hashlib.sha256(b''.join(path.read_bytes() for path in sources)).hexdigest()
    dependency_bindings = []
    with tempfile.TemporaryDirectory(prefix='datahub-etl-functions-') as local:
        local_path = Path(local)
        source_paths = []
        for source in sources:
            destination_source = local_path / 'src' / source.relative_to(root)
            destination_source.parent.mkdir(parents=True, exist_ok=True)
            destination_source.write_bytes(source.read_bytes())
            source_paths.append(str(destination_source))
        api_jar = run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/flink/lib/flink-table-api-java-uber-*.jar')
        if '\n' in api_jar:
            raise RuntimeError('目标容器必须只有一个官方 Flink API JAR')
        run('docker', 'cp', options.container + ':' + api_jar, str(local_path / 'flink-api.jar'))
        classpath = [str(local_path / 'flink-api.jar')]
        if options.streaming_window:
            distribution = run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/flink/lib/flink-dist-*.jar')
            if '\n' in distribution:
                raise RuntimeError('目标容器必须只有一个官方 Flink distribution JAR')
            run('docker', 'cp', options.container + ':' + distribution, str(local_path / 'flink-dist.jar'))
            classpath.append(str(local_path / 'flink-dist.jar'))
        hive_jar = run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/spark/jars/hive-exec-*.jar')
        if '\n' in hive_jar:
            raise RuntimeError('目标容器必须只有一个官方 Hive UDF API JAR')
        run('docker', 'cp', options.container + ':' + hive_jar, str(local_path / 'hive-api.jar'))
        classpath.append(str(local_path / 'hive-api.jar'))
        if options.http:
            for jar_path in run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/spark/jars/hive-serde-*.jar /opt/spark/jars/hadoop-client-api-*.jar /opt/spark/jars/spark-core_*.jar /opt/spark/jars/scala-library-*.jar').splitlines():
                provided = local_path / Path(jar_path).name
                run('docker', 'cp', options.container + ':' + jar_path, str(provided))
                classpath.append(str(provided))
        for dependency in dependencies:
            dependency_digest = hashlib.sha256(dependency.read_bytes()).hexdigest()
            dependency_path = '/opt/flink/usrlib/datahub-etl-dependency-' + dependency_digest + '.jar'
            run('docker', 'exec', options.container, 'mkdir', '-p', '/opt/flink/usrlib')
            install_immutable(options.container, dependency, dependency_path, dependency_digest)
            classpath.append(str(dependency))
            dependency_bindings.append({'name': dependency.name, 'sha256': dependency_digest, 'path': dependency_path})
        (local_path / 'classes').mkdir()
        run(options.javac, '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join(classpath),
            '-d', str(local_path / 'classes'), *source_paths)
        run('jar', '--create', '--date=2026-01-01T00:00:00Z', '--file', str(local_path / 'functions.jar'), '-C', str(local_path / 'classes'), '.')
        checksum = hashlib.sha256((local_path / 'functions.jar').read_bytes()).hexdigest()
        destination = '/opt/flink/usrlib/datahub-etl-functions-' + checksum[:16] + '.jar'
        run('docker', 'exec', options.container, 'mkdir', '-p', '/opt/flink/usrlib')
        install_immutable(options.container, local_path / 'functions.jar', destination, checksum)
    checksum = run('docker', 'exec', options.container, 'sha256sum', destination).split()[0]
    resources = ' USING ' + ', '.join("JAR 'file://" + path + "'" for path in
                                     [dependency['path'] for dependency in dependency_bindings] + [destination])
    flink_sql = "CREATE TEMPORARY SYSTEM FUNCTION DH_REGEX_REPLACE AS 'cn.iocoder.yudao.framework.etl.flink.EtlRegexReplaceFunction'" + resources + ';'
    flink_sql += "\nCREATE TEMPORARY SYSTEM FUNCTION DH_SPLIT_TOKEN AS 'cn.iocoder.yudao.framework.etl.flink.EtlSplitTokenFunction'" + resources + ';'
    spark_sql = "CREATE TEMPORARY FUNCTION DH_REGEX_REPLACE AS 'cn.iocoder.yudao.framework.etl.spark.EtlRegexReplaceFunction'" + resources + ';'
    spark_sql += "\nCREATE TEMPORARY FUNCTION DH_SPLIT_TOKEN AS 'cn.iocoder.yudao.framework.etl.spark.EtlSplitTokenFunction'" + resources + ';'
    functions = ['DH_REGEX_REPLACE', 'DH_SPLIT_TOKEN']
    if options.streaming_window:
        functions.append('DH_WINDOW_PICK')
        flink_sql += "\nCREATE TEMPORARY SYSTEM FUNCTION DH_WINDOW_PICK AS 'cn.iocoder.yudao.framework.etl.flink.EtlWindowPickFunction'" + resources + ';'
    if options.crypto:
        for action, class_name in [('ENCRYPT', 'EtlCryptoEncryptFunction'), ('DECRYPT', 'EtlCryptoDecryptFunction')]:
            functions.append('DH_CRYPTO_' + action)
            flink_sql += "\nCREATE TEMPORARY SYSTEM FUNCTION DH_CRYPTO_" + action + " AS 'cn.iocoder.yudao.framework.etl.flink." + class_name + "'" + resources + ';'
            spark_sql += "\nCREATE TEMPORARY FUNCTION DH_CRYPTO_" + action + " AS 'cn.iocoder.yudao.framework.etl.spark." + class_name + "'" + resources + ';'
    if options.http:
        for function, class_name in [('DH_READ_API', 'EtlHttpRowsFunction'), ('DH_READ_SOAP', 'EtlWebServiceRowsFunction')]:
            functions.append(function)
            spark_sql += "\nCREATE TEMPORARY FUNCTION " + function + " AS 'cn.iocoder.yudao.framework.etl.spark." + class_name + "'" + resources + ';'
    binding = {'sourceSha256': digest, 'jarSha256': checksum, 'jarPath': destination,
               'javaRelease': 17, 'managedFunctions': functions, 'dependencies': dependency_bindings,
               'initializationSql': flink_sql, 'flinkInitializationSql': flink_sql, 'sparkInitializationSql': spark_sql}
    if options.output:
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(binding, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(binding, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
