#!/usr/bin/env python3
"""以已解析的官方 POI 依赖构建 Java17 Excel UDTF，并部署管理员明确授权的工作节点根目录。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*arguments):
    return subprocess.check_output(arguments, text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--root', action='append', default=[])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--javac', default='javac')
    options = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    module = repository / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    sources = [module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlExcelCellReader.java',
               module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/file/EtlBoundedExcelReader.java',
               module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/file/EtlManagedExcelInput.java',
               module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/file/EtlManagedLocalInput.java',
               module / 'runtime/spark/EtlReadExcelFunction.java']
    allowed_prefixes = ('poi-', 'xmlbeans-', 'commons-collections4-', 'commons-compress-', 'commons-math3-',
                        'curvesapi-', 'commons-codec-', 'SparseBitSet-', 'commons-io-', 'commons-lang3-')
    dependencies = [Path(value).resolve() for value in options.classpath_file.read_text().strip().split(os.pathsep)
                    if Path(value).name.startswith(allowed_prefixes)]
    if not any(path.name.startswith('poi-ooxml-') for path in dependencies) or any(not path.is_file() for path in dependencies):
        parser.error('classpath 必须包含 Maven 已解析的官方 POI/OOXML 依赖')
    authorized_roots = []
    for value in options.root:
        if not value.startswith('/') or value == '/' or any(part in ('.', '..') for part in value.split('/')):
            parser.error('授权根目录必须是明确的绝对普通目录，不能是根文件系统或包含跳转')
        actual = run('docker', 'exec', options.container, 'readlink', '-f', value)
        if actual != value.rstrip('/'):
            parser.error('授权根目录必须已经存在且不能包含符号链接')
        run('docker', 'exec', options.container, 'test', '-d', actual)
        authorized_roots.append(actual)
    bindings = []
    with tempfile.TemporaryDirectory(prefix='datahub-excel-udtf-') as temporary:
        local = Path(temporary)
        api = local / 'api'; api.mkdir()
        for pattern in ('hive-exec-*.jar', 'hive-serde-*.jar', 'hadoop-client-api-*.jar'):
            matches = run('docker', 'exec', options.container, 'sh', '-c', 'ls /opt/spark/jars/' + pattern).splitlines()
            if len(matches) != 1:
                raise RuntimeError('目标 Spark 官方 API JAR 必须唯一：' + pattern)
            run('docker', 'cp', options.container + ':' + matches[0], str(api / Path(matches[0]).name))
        classes = local / 'classes'; classes.mkdir()
        classpath = [str(path) for path in api.glob('*.jar')] + [str(path) for path in dependencies]
        run(options.javac, '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join(classpath),
            '-d', str(classes), *[str(source) for source in sources])
        artifact = local / 'excel.jar'
        run('jar', '--create', '--date=2026-01-01T00:00:00Z', '--file', str(artifact), '-C', str(classes), '.')
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        destination = '/opt/datahub-etl-excel/excel-' + digest[:16] + '.jar'
        run('docker', 'exec', options.container, 'mkdir', '-p', '/opt/datahub-etl-excel')
        for path in dependencies:
            checksum = hashlib.sha256(path.read_bytes()).hexdigest()
            target = '/opt/datahub-etl-excel/dependency-' + checksum + '.jar'
            run('docker', 'cp', str(path), options.container + ':' + target)
            bindings.append({'name': path.name, 'sha256': checksum, 'path': target})
        run('docker', 'cp', str(artifact), options.container + ':' + destination)
        roots_file = local / 'roots.txt'; roots_file.write_text('\n'.join(sorted(set(authorized_roots))) + '\n')
        run('docker', 'cp', str(roots_file), options.container + ':/opt/datahub-etl-excel/roots.txt')
        run('docker', 'exec', options.container, 'chmod', '0644', '/opt/datahub-etl-excel/roots.txt')
    resources = [binding['path'] for binding in bindings] + [destination]
    sql = "CREATE TEMPORARY FUNCTION DH_READ_EXCEL AS 'cn.iocoder.yudao.framework.etl.spark.EtlReadExcelFunction' USING "
    sql += ', '.join("JAR 'file://" + resource + "'" for resource in resources) + ';\n'
    result = {'sourceSha256': hashlib.sha256(b''.join(source.read_bytes() for source in sources)).hexdigest(),
              'jarSha256': digest, 'jarPath': destination, 'javaRelease': 17, 'managedFunctions': ['DH_READ_EXCEL'],
              'dependencies': bindings, 'authorizedRoots': sorted(set(authorized_roots)), 'sparkInitializationSql': sql}
    if options.output:
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'jarPath': destination, 'managedFunctions': result['managedFunctions'], 'authorizedRoots': result['authorizedRoots']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
