#!/usr/bin/env python3
"""在隔离 Docker 进程提供官方 SDK Tunnel wire 夹具，明确不模拟云端权限或服务验收。"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import tempfile


def run(*arguments, data=None):
    result = subprocess.run(arguments, input=data, capture_output=True)
    if result.returncode: raise RuntimeError(result.stderr.decode(errors='replace')[-4000:] + result.stdout.decode(errors='replace')[-4000:])
    return result.stdout.decode().strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--isolated-worker', action='store_true', help='复用目标镜像启动仅本验收使用的Spark工作容器，避免共享DS生命周期干扰')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-odps-binding.json'))
    parser.add_argument('--output', type=Path, default=Path('/tmp/datahub-odps-wire-fixture.json'))
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/runtime/odps-smoke/OdpsWireFixture.java'
    binding = json.loads(options.binding.read_text()); artifact = Path(binding['artifact'])
    if options.isolated_worker:
        image = run('docker', 'inspect', '--format', '{{.Config.Image}}', options.worker)
        options.worker = 'datahub-ds-local-odps-spark-worker'
        subprocess.run(['docker', 'rm', '-f', options.worker], capture_output=True)
        run('docker', 'run', '-d', '--name', options.worker, '--network', 'datahub-ds-local_default', '--memory', '1g', '--read-only',
            '--tmpfs', '/tmp:rw,nosuid,size=768m,mode=1777', '--mount', 'type=bind,source=' + str(artifact) + ',target=' + binding['workerJarPath'] + ',readonly',
            '--entrypoint', '/bin/bash', image, '-c', 'exec tail -f /dev/null')
    content = source.read_text().replace('com.aliyun.odps', 'cn.iocoder.yudao.framework.etl.odps.shaded.sdk')
    content = content.replace('com.google.gson', 'cn.iocoder.yudao.framework.etl.odps.shaded.gson').replace('cn.hutool', 'cn.iocoder.yudao.framework.etl.odps.shaded.hutool')
    content = content.replace('cn.iocoder.yudao.framework.etl.core.executor.support', 'cn.iocoder.yudao.framework.etl.odps.shaded.core')
    with tempfile.TemporaryDirectory(prefix='datahub-odps-wire-') as temporary:
        directory = Path(temporary); java = directory / source.name; java.write_text(content); classes = directory / 'classes'; classes.mkdir()
        run('javac', '--release', '17', '--add-modules', 'jdk.httpserver', '-encoding', 'UTF-8', '-cp', str(artifact), '-d', str(classes), str(java))
        jar = directory / 'wire.jar'; run('jar', '--create', '--file', str(jar), '-C', str(classes), '.')
        digest = hashlib.sha256(jar.read_bytes()).hexdigest(); fixture = Path('/tmp/datahub-odps-wire-' + digest[:16] + '.jar'); fixture.write_bytes(jar.read_bytes()); fixture.chmod(0o644)
    image = run('docker', 'inspect', '--format', '{{.Config.Image}}', options.worker)
    name = 'datahub-ds-local-odps-wire-fixture'
    subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
    run('docker', 'run', '-d', '--name', name, '--network', 'datahub-ds-local_default', '--network-alias', 'odps-wire', '--memory', '256m',
        '-u', '10001', '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m,uid=10001,gid=10001',
        '--mount', 'type=bind,source=' + str(artifact) + ',target=/runtime/odps.jar,readonly', '--mount', 'type=bind,source=' + str(fixture) + ',target=/runtime/wire.jar,readonly',
        '--entrypoint', '/opt/java17/bin/java', image, '--add-modules', 'jdk.httpserver', '-Xmx128m', '-cp', '/runtime/odps.jar:/runtime/wire.jar',
        'cn.iocoder.yudao.framework.etl.odps.fixture.OdpsWireFixture', '18088')
    secret = {'serviceEndpoint': 'http://odps-wire:18088/api', 'tunnelEndpoint': 'http://odps-wire:18088', 'project': 'fixture',
              'accessId': 'wire-fixture', 'accessSecret': secrets.token_hex(32), 'securityToken': '', 'allowPlainHttp': True,
              'resources': [{'table': 'source', 'partition': "ds='20261102'", 'source': True, 'sink': False, 'allowOverwrite': False},
                            {'table': 'target', 'partition': "ds='20261102'", 'source': True, 'sink': True, 'allowOverwrite': False}]}
    secret_dir = '/tmp/datahub-odps-wire-secrets'
    run('docker', 'exec', '-i', options.worker, 'python3', '-c', "from pathlib import Path;import os,sys;p=Path('" + secret_dir + "');p.mkdir(mode=0o700,exist_ok=True);os.chown(p,10001,10001);f=p/'odpsAccess';f.write_bytes(sys.stdin.buffer.read());f.chmod(0o600);os.chown(f,10001,10001)", data=json.dumps(secret).encode())
    result = {'container': name, 'workerSecretDirectory': secret_dir, 'secretReferences': ['odpsAccess'], 'fixtureJarSha256': digest,
              'runtimeJarSha256': binding['jarSha256'], 'fixtureKind': 'OFFICIAL_SDK_TUNNEL_WIRE_ONLY', 'cloudAcceptance': 'OPEN', 'hostPorts': [],
              'workerContainer': options.worker, 'isolatedWorker': options.isolated_worker}
    options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
