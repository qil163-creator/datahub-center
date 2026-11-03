#!/usr/bin/env python3
"""从已授权本地实例的私密classpath配置创建不可变验收副本，只替换本脚本记录的独立进程。"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=50026)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    base = root / 'deploy/dolphinscheduler-local/runtime'
    config = base / 'components-application-private.yaml'
    source = json.loads((base / 'components-launch.json').read_text())
    record = base / 'components-server.json'
    if config.stat().st_mode & 0o077:
        raise RuntimeError('独立验收配置必须仅当前用户可读写')
    if record.exists():
        previous = json.loads(record.read_text())
        process = subprocess.run(['ps', '-p', str(previous['pid']), '-o', 'command='], text=True, capture_output=True)
        if process.stdout.strip():
            if source['main'] not in process.stdout or '--server.port=' + str(args.port) not in process.stdout or str(config) not in process.stdout:
                raise RuntimeError('PID已不属于本验收进程，不发送终止信号')
            os.kill(previous['pid'], signal.SIGTERM)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                with socket.socket() as probe:
                    if probe.connect_ex(('127.0.0.1', args.port)) != 0: break
                time.sleep(.1)
            else: raise RuntimeError('旧验收实例未退出，不强制终止')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    snapshot = Path('/tmp/datahub-components-classpath-' + uuid.uuid4().hex)
    snapshot.mkdir(mode=0o700)
    entries = []; count = 0
    with Path('/tmp/datahub-components-maven.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for entry in source['classpath'].split(':'):
            path = Path(entry)
            if path.is_dir() and path.is_relative_to(root) and str(path).endswith('/target/classes'):
                destination = snapshot / ('classes-' + str(count)); shutil.copytree(path, destination)
                entries.append(str(destination)); count += 1
            else: entries.append(entry)
    log = base / 'components-server.log'
    command = [source['java'], '-Xms256m', '-Xmx768m', '--enable-preview', '-Dfile.encoding=UTF-8', '-classpath', ':'.join(entries), source['main'],
               '--server.port=' + str(args.port), '--spring.config.additional-location=file:' + str(config)]
    with log.open('w') as stream:
        process = subprocess.Popen(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
    log.chmod(0o600)
    record.write_text(json.dumps({'pid': process.pid, 'port': args.port, 'sourcePid': source['sourcePid'], 'classpathSnapshot': str(snapshot),
                                 'snapshotModuleCount': count, 'logPath': str(log)}))
    record.chmod(0o600)
    print(json.dumps({'pid': process.pid, 'port': args.port, 'snapshotModules': count}))


if __name__ == '__main__':
    main()
