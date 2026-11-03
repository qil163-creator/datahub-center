#!/usr/bin/env python3
"""用同一 HDFS 物理表验证 Hive4.1 目录和 Spark 原生追加，两个引擎不共享 Metastore。"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid


def run(*arguments, timeout=180):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr[-5000:] + result.stdout[-5000:])
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--jdbc-driver', type=Path, required=True)
    parser.add_argument('--metadata-only', action='store_true')
    parser.add_argument('--hive-container', default='datahub-ds-local-hive-native-server-1')
    parser.add_argument('--namenode-container', default='datahub-ds-local-hdfs-native-namenode-1')
    parser.add_argument('--spark-container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path, default=Path('deploy/dolphinscheduler-local/verification/components-20261102-hive.json'))
    options = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    module = repository / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    stage = repository / 'deploy/dolphinscheduler-local/runtime/hive-native'
    stage.mkdir(parents=True, exist_ok=True)
    schema = 'hive_components_' + uuid.uuid4().hex[:12]
    dfs_root = '/datahub_components/' + schema
    default_fs = 'hdfs://hdfs-native-namenode:8020'
    for name in ('source', 'target'):
        run('docker', 'exec', '-e', 'HADOOP_USER_NAME=datahub', options.namenode_container, 'hdfs', 'dfs', '-mkdir', '-p', dfs_root + '/' + name)
    seed = stage / 'seed.tsv'; seed.write_text('1\tALPHA\t1.100\n2\tBETA\t2.200\n3\tskip\t3.300\n')
    run('docker', 'cp', str(seed), options.namenode_container + ':/tmp/' + schema + '.tsv')
    run('docker', 'exec', '-e', 'HADOOP_USER_NAME=datahub', options.namenode_container, 'hdfs', 'dfs', '-put', '/tmp/' + schema + '.tsv', dfs_root + '/source/input.tsv')
    existing = stage / 'existing.tsv'; existing.write_text('9\tEXISTING\t9.900\n')
    run('docker', 'cp', str(existing), options.namenode_container + ':/tmp/' + schema + '-existing.tsv')
    run('docker', 'exec', '-e', 'HADOOP_USER_NAME=datahub', options.namenode_container, 'hdfs', 'dfs', '-put', '/tmp/' + schema + '-existing.tsv', dfs_root + '/target/existing.tsv')
    ddl = 'CREATE DATABASE ' + schema + ';\n'
    for name in ('source', 'target'):
        ddl += 'CREATE EXTERNAL TABLE ' + schema + '.' + name + '(id BIGINT, name STRING, amount DECIMAL(20,3)) ROW FORMAT DELIMITED FIELDS TERMINATED BY \'\\t\' STORED AS TEXTFILE LOCATION \'' + default_fs + dfs_root + '/' + name + '\';\n'
    ddl += 'CREATE EXTERNAL TABLE ' + schema + '.partitioned(id BIGINT) PARTITIONED BY (p STRING) STORED AS TEXTFILE LOCATION \'' + default_fs + dfs_root + '/partitioned\';\n'
    ddl += 'CREATE VIEW ' + schema + '.source_view AS SELECT id FROM ' + schema + '.source;\n'
    run('docker', 'exec', options.hive_container, 'beeline', '-u', 'jdbc:hive2://localhost:10000/default', '-n', 'hive', '--silent=true', '--showHeader=false', '--outputformat=tsv2', '-e', ddl)
    driver = stage / 'hive-jdbc-standalone.jar'
    shutil.copyfile(options.jdbc_driver.resolve(strict=True), driver)
    dependencies = [Path(value).resolve() for value in options.classpath_file.read_text().strip().split(os.pathsep)]
    classes = [path for path in (repository / 'yudao-framework/yudao-dbswitch').rglob('target/classes') if path.is_dir()]
    classes += [module / 'target/classes']
    compiled = stage / 'probe-classes'; compiled.mkdir(exist_ok=True)
    classpath = os.pathsep.join(str(path) for path in [*classes, *dependencies])
    run('javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(compiled), str(module / 'runtime/hive/HiveNativeMetadataProbe.java'))
    dependency_root = Path(os.path.commonpath([str(path) for path in dependencies]))
    output = stage / 'probe-output'; output.mkdir(exist_ok=True)
    run('docker', 'run', '--rm', '--network', 'datahub-ds-local_default', '-v', str(repository) + ':' + str(repository) + ':ro',
        '-v', str(dependency_root) + ':' + str(dependency_root) + ':ro', '-v', str(output) + ':/output',
        'eclipse-temurin:25-jre', 'java', '-cp', str(compiled) + os.pathsep + classpath, 'cn.iocoder.yudao.framework.etl.hive.HiveNativeMetadataProbe',
        str(driver), 'jdbc:hive2://hive-native-server:10000/default', schema, '/output')
    metadata = json.loads((output / 'metadata.json').read_text())
    if options.metadata_only:
        print(json.dumps({'schema': schema, 'metadata': metadata, 'engineExecutionStarted': False}, ensure_ascii=False)); return
    spark_ddl = 'CREATE DATABASE IF NOT EXISTS `' + schema + '`;\n'
    for name in ('source', 'target'):
        fields = ', '.join('`' + key + '` ' + value for key, value in metadata['metadata'][name].items())
        spark_ddl += 'CREATE EXTERNAL TABLE `' + schema + '`.`' + name + '` (' + fields + ') ROW FORMAT DELIMITED FIELDS TERMINATED BY \'\\t\' STORED AS TEXTFILE LOCATION \'' + default_fs + dfs_root + '/' + name + '\';\n'
    query = (output / 'compiled.sql').read_text()
    spark_sql = stage / 'spark.sql'; spark_sql.write_text(spark_ddl + query + 'SELECT id, name, amount FROM `' + schema + '`.`target`;\n')
    run('docker', 'exec', options.spark_container, 'mkdir', '-p', '/tmp/' + schema)
    run('docker', 'cp', str(spark_sql), options.spark_container + ':/tmp/' + schema + '/spark.sql')
    spark = run('docker', 'exec', '-w', '/tmp/' + schema, '-e', 'JAVA_HOME=/opt/java17', '-e', 'HADOOP_USER_NAME=datahub', options.spark_container,
                '/opt/spark/bin/spark-sql', '--master', 'local[1]', '--driver-memory', '512m', '-S', '-f', '/tmp/' + schema + '/spark.sql')
    expected = '2\tBETA\t2.200'
    if expected not in spark.splitlines():
        raise RuntimeError('Spark 实际输出未匹配受限字段、过滤和映射结果')
    preserved = '9\tEXISTING\t9.900'
    if preserved not in spark.splitlines():
        raise RuntimeError('Spark 追加写入未保留目标已有数据')
    hive = run('docker', 'exec', options.hive_container, 'beeline', '-u', 'jdbc:hive2://localhost:10000/default', '-n', 'hive', '--silent=true', '--showHeader=false', '--outputformat=tsv2',
               '-e', 'SELECT id, name, amount FROM ' + schema + '.target;')
    if expected not in hive.splitlines() or preserved not in hive.splitlines():
        raise RuntimeError('HiveServer2 未读到 Spark 写入同一 HDFS 物理表的精确结果')
    output_paths = run('docker', 'exec', '-e', 'HADOOP_USER_NAME=datahub', options.namenode_container, 'hdfs', 'dfs', '-ls', dfs_root + '/target')
    record = {'verifiedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(), 'schema': schema,
              'execution': 'Official Hive4.1.0 JDBC native catalog profile + Spark3.5.6 Hive external TEXTFILE on identical HDFS storage. Separate metastores, no host ports.',
              'metadata': metadata, 'expectedRow': expected, 'preservedRow': preserved, 'sparkReadAppendMappingPassed': True, 'hiveCrossReadPassed': True,
              'hdfsTargetListing': output_paths.splitlines(), 'driverSha256': hashlib.sha256(driver.read_bytes()).hexdigest(),
              'remainingBoundaries': ['Spark bounded table read/append only; no implicit overwrite, ACID or partition writes.', 'Input SQL hooks, cursor increments, output updates and unsupported batch controls are rejected.', 'Runtime storage guard reuses HiveNativeCatalog.read to reject unverified versions, views, partitions, ACID and non-TEXTFILE formats.']}
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(options.output), 'expectedRow': expected, 'hiveCrossReadPassed': True}, ensure_ascii=False))


if __name__ == '__main__':
    main()
