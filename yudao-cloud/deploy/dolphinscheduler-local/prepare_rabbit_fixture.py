#!/usr/bin/env python3
"""创建隔离 RabbitMQ 验收凭据和批准的两条队列，不接触平台业务消息服务。"""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def main():
    stage = Path(__file__).resolve().parent / 'runtime/rabbit-native'
    stage.mkdir(parents=True, exist_ok=True)
    credential = stage / 'rabbit.fixture'
    if not credential.exists():
        password = secrets.token_urlsafe(32)
        credential.write_text('host=rabbit-native\nport=5672\nvirtualHost=/components\nsourceQueues=components.in\nsinkQueues=components.out\nuseSsl=false\nusername=components\npassword=' + password + '\n')
        credential.chmod(0o600)
    else:
        password = dict(line.split('=', 1) for line in credential.read_text().splitlines())['password']
    salt = os.urandom(4)
    password_hash = base64.b64encode(salt + hashlib.sha256(salt + password.encode()).digest()).decode()
    definitions = {'users': [{'name': 'components', 'password_hash': password_hash, 'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': []}],
        'vhosts': [{'name': '/components'}], 'permissions': [{'user': 'components', 'vhost': '/components', 'configure': '^components\\.(in|out)$', 'write': '^(amq\\.default|components\\.(in|out))$', 'read': '^components\\.(in|out)$'}],
        'queues': [{'name': queue, 'vhost': '/components', 'durable': True, 'auto_delete': False, 'arguments': {}} for queue in ['components.in', 'components.out']]}
    (stage / 'definitions.json').write_text(json.dumps(definitions) + '\n')
    (stage / 'definitions.json').chmod(0o644)
    worker = 'datahub-ds-local-standalone-1'
    subprocess.run(['docker', 'exec', worker, 'mkdir', '-p', '/opt/datahub-rabbit-secrets'], check=True)
    subprocess.run(['docker', 'cp', str(credential), worker + ':/opt/datahub-rabbit-secrets/rabbit.fixture'], check=True)
    subprocess.run(['docker', 'exec', worker, 'chown', '10001:10001', '/opt/datahub-rabbit-secrets/rabbit.fixture'], check=True)
    subprocess.run(['docker', 'exec', worker, 'chmod', '0400', '/opt/datahub-rabbit-secrets/rabbit.fixture'], check=True)
    print(json.dumps({'workerSecretRef': 'rabbit.fixture', 'queues': ['components.in', 'components.out'], 'plaintextPrinted': False}))


if __name__ == '__main__':
    main()
