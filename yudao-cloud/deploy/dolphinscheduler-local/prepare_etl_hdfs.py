#!/usr/bin/env python3
"""以目标 Spark/Hadoop API 构建 Java17 受管 HDFS 数据源，发布内容寻址的工作节点 JAR。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*arguments):
    result = subprocess.run(arguments, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr[-6000:] + result.stdout[-6000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--javac', default='javac')
    options = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    source = repository / 'yudao-framework/yudao-spring-boot-starter-biz-etl/runtime/spark/EtlHdfsTextProvider.java'
    values = source.with_name('EtlSparkRowValues.java')
    with tempfile.TemporaryDirectory(prefix='datahub-hdfs-jar-') as temporary:
        directory = Path(temporary)
        api = directory / 'api'; api.mkdir()
        for pattern in ('spark-sql_*.jar', 'spark-sql-api_*.jar', 'spark-catalyst_*.jar', 'spark-core_*.jar',
                        'spark-common-utils_*.jar', 'spark-unsafe_*.jar', 'scala-library-*.jar', 'hadoop-client-api-*.jar',
                        'slf4j-api-*.jar', 'kryo-shaded-*.jar'):
            paths = run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/spark/jars/' + pattern).splitlines()
            if len(paths) != 1:
                raise RuntimeError('目标 Spark/Hadoop API JAR 必须唯一：' + pattern)
            run('docker', 'cp', options.container + ':' + paths[0], str(api / Path(paths[0]).name))
        classes = directory / 'classes'; classes.mkdir()
        run(options.javac, '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join(str(path) for path in api.glob('*.jar')),
            '-d', str(classes), str(source), str(values))
        artifact = directory / 'hdfs.jar'
        run('jar', '--create', '--date=2026-01-01T00:00:00Z', '--file', str(artifact), '-C', str(classes), '.')
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        local = Path(__file__).resolve().parent / 'runtime/hdfs/datahub-etl-hdfs.jar'
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(artifact.read_bytes())
        run('docker', 'cp', str(local), options.container + ':/opt/spark/jars/datahub-etl-hdfs.jar')
    result = {'sourceSha256': hashlib.sha256(source.read_bytes() + values.read_bytes()).hexdigest(), 'jarSha256': digest,
                  'jarPath': '/opt/spark/jars/datahub-etl-hdfs.jar', 'javaRelease': 17, 'plugin': 'spark-hdfs',
                  'rootEnvironment': 'DATAHUB_ETL_HDFS_ROOTS', 'rootSeparator': ';'}
    if options.output:
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
