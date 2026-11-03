#!/usr/bin/env python3
"""创建隔离 Redis ACL 夹具与执行节点秘密文件，不将密码写入版本控制或输出。"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path, default=Path('/tmp/datahub-redis-fixture.json'))
    options = parser.parse_args()
    root = Path(__file__).resolve().parent
    directory = root / 'runtime/redis-fixture'; directory.mkdir(parents=True, exist_ok=True); directory.chmod(0o700)
    password = secrets.token_hex(32)
    (directory / 'users.acl').write_text('user default off\nuser datahub on >' + password + ' ~input:* ~result:* +@read +@write +eval_ro +ping +scan\n')
    (directory / 'users.acl').chmod(0o644)
    subprocess.run(['docker', 'compose', '-p', 'datahub-ds-local', '-f', str(root / 'compose.yaml'), '-f', str(root / 'compose.redis-native.yaml'), 'up', '-d', '--no-deps', 'redis-native'], check=True, capture_output=True)
    secret = 'host=redis-native\nport=6379\ndatabase=0\nuseSsl=false\nsourcePatterns=input:string:*,input:hash:*,input:list:*\nsinkPrefixes=result:\nusername=datahub\npassword=' + password + '\n'
    secret_dir = '/tmp/datahub-redis-native-secrets'
    subprocess.run(['docker', 'exec', '-i', options.worker, 'python3', '-c', "from pathlib import Path;import sys,os; p=Path('" + secret_dir + "');p.mkdir(mode=0o700,exist_ok=True);os.chown(p,10001,10001);f=p/'redisAccess';f.write_bytes(sys.stdin.buffer.read());f.chmod(0o600);os.chown(f,10001,10001)"], input=secret.encode(), check=True, capture_output=True)
    result = {'container': 'datahub-ds-local-redis-native-1', 'workerSecretDirectory': secret_dir, 'secretReferences': ['redisAccess'], 'host': 'redis-native', 'port': 6379,
              'database': 0, 'sourcePatterns': ['input:string:*', 'input:hash:*', 'input:list:*'], 'sinkPrefixes': ['result:'], 'credentialPlaintextRecorded': False}
    options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); options.output.chmod(0o600)
    print(json.dumps({'output': str(options.output), 'credentialPlaintextRecorded': False}))


if __name__ == '__main__': main()
