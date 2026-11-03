#!/usr/bin/env python3
"""使用目标 Flink/JDBC 官方 API 编译主键校验适配器，凭据与原 Catalog 定义均不进入构建包。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from prepare_etl_functions import install_immutable


def run(*arguments):
    return subprocess.check_output(arguments, text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='components-stable-ds')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--install', action='store_true')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/runtime/flink/jdbc/ManagedVerifiedJdbcSinkFactory.java'
    snapshot = source.read_bytes()
    source_hash = hashlib.sha256(snapshot).hexdigest()
    output = options.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    cache = Path(tempfile.gettempdir()) / 'datahub-flink-sdk-cache'; cache.mkdir(mode=0o700, exist_ok=True)
    dependencies = []; classpath = []
    jars = json.loads(run('docker', 'exec', options.container, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/*.jar')))"))
    for remote in sorted(jars):
        digest = run('docker', 'exec', options.container, 'sha256sum', remote).split()[0]
        path = cache / (digest + '-' + Path(remote).name)
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            run('docker', 'cp', options.container + ':' + remote, str(path))
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest: raise RuntimeError('官方 SDK 缓存摘要不一致')
        dependencies.append({'name': Path(remote).name, 'sha256': digest}); classpath.append(str(path))
    with tempfile.TemporaryDirectory(prefix='datahub-verified-jdbc-') as temporary:
        directory = Path(temporary); local = directory / source.name; local.write_bytes(snapshot)
        classes = directory / 'classes'; classes.mkdir()
        run('javac', '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join(classpath), '-d', str(classes), str(local))
        services = classes / 'META-INF/services'; services.mkdir(parents=True)
        (services / 'org.apache.flink.table.factories.Factory').write_text('cn.iocoder.yudao.framework.etl.flink.jdbc.ManagedVerifiedJdbcSinkFactory\n')
        jar = directory / 'jdbc.jar'
        run('jar', '--create', '--date=2026-01-01T00:00:00Z', '--file', str(jar), '-C', str(classes), '.')
        content = jar.read_bytes(); digest = hashlib.sha256(content).hexdigest()
        artifact = output.parent / ('datahub-flink-verified-jdbc-' + digest[:16] + '.jar'); artifact.write_bytes(content)
    worker = '/opt/flink/usrlib/' + artifact.name
    if options.install:
        if source.read_bytes() != snapshot: raise RuntimeError('构建期间源码已变化，请重建后部署')
        run('docker', 'exec', options.container, 'mkdir', '-p', '/opt/flink/usrlib')
        install_immutable(options.container, artifact, worker, digest)
    result = {'sourceSha256': source_hash, 'jarSha256': digest, 'artifact': str(artifact), 'workerJarPath': worker, 'javaRelease': 17,
              'targetSdk': dependencies, 'installed': options.install, 'initializationSql': "ADD JAR 'file://" + worker + "';"}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
