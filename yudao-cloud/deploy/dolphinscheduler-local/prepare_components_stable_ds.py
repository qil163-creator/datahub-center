#!/usr/bin/env python3
"""创建固定镜像、全新元数据和正常认证的独立 DS，仅准备环境而不执行 Spark。"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
from smoke import Client

BASE = Path(__file__).resolve().parent
STAGE = BASE / 'runtime/components-stable'
PRIVATE = BASE / 'runtime/components-isolated-ds.json'
CONTAINER = 'components-stable-ds'
IMAGE = 'sha256:9ab13e1abd8f42ab5489313c07fb6e3e44bd51b354e104f283c6216b314e6966'
SERVER = 'http://127.0.0.1:12347/dolphinscheduler'


def write_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(value)


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('独立 DS 准备命令失败：' + args[0] + '\n' + result.stderr[-1000:])
    return result.stdout.strip()


def artifacts():
    target = STAGE / 'artifacts/usrlib'; target.mkdir(parents=True, exist_ok=True)
    paths = json.loads(run('docker', 'exec', 'datahub-ds-local-standalone-1', 'python3', '-c',
        "import json,pathlib,re;print(json.dumps([str(p) for p in pathlib.Path('/opt/flink/usrlib').glob('*.jar') if re.fullmatch(r'datahub-etl-(functions|dependency)-[0-9a-f]{16,64}\\.jar',p.name)]))"))
    result = []
    for source in paths:
        destination = target / Path(source).name
        if not destination.exists():
            run('docker', 'cp', 'datahub-ds-local-standalone-1:' + source, str(destination))
        checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
        if not checksum.startswith(destination.stem.rsplit('-', 1)[1]):
            raise RuntimeError('不可变执行依赖名称与摘要不一致')
        destination.chmod(0o444)
        result.append({'path': source, 'sha256': checksum, 'size': destination.stat().st_size})
    if not result:
        raise RuntimeError('当前 Worker 未找到已验证的不可变执行依赖')
    providers = json.loads(run('docker', 'exec', 'datahub-ds-local-standalone-1', 'python3', '-c',
        "import json,pathlib,hashlib;print(json.dumps([{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in pathlib.Path('/opt/spark/jars').glob('datahub-etl-*.jar') if p.name in ['datahub-etl-ftp.jar','datahub-etl-hdfs.jar','datahub-etl-redis.jar','datahub-etl-odps.jar']]))"))
    for provider in providers:
        source, checksum = provider['path'], provider['sha256']
        destination = STAGE / 'artifacts/spark-jars' / checksum / Path(source).name
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            run('docker', 'cp', 'datahub-ds-local-standalone-1:' + source, str(destination))
        if hashlib.sha256(destination.read_bytes()).hexdigest() != checksum:
            raise RuntimeError('Spark SPI 在快照期间发生变化，拒绝安装')
        destination.chmod(0o444)
        provider['installFile'] = str(destination.relative_to(STAGE / 'artifacts'))
        provider['size'] = destination.stat().st_size
        result.append(provider)
    write_private(STAGE / 'artifacts/spark-jars.json', json.dumps(providers) + '\n')
    return result


def main():
    STAGE.mkdir(parents=True, exist_ok=True); STAGE.chmod(0o700)
    credentials = STAGE / 'credentials.json'
    if credentials.exists():
        identity = json.loads(credentials.read_text())
    else:
        identity = {'username': 'admin', 'password': 'A' + secrets.token_urlsafe(12) + '7z', 'databaseUsername': 'components_db',
                    'databasePassword': secrets.token_hex(24), 'token': secrets.token_hex(32), 'passwordRotated': False}
        write_private(credentials, json.dumps(identity) + '\n')
    if not identity['passwordRotated'] and len(identity['password']) > 20:
        identity['password'] = 'A' + secrets.token_urlsafe(12) + '7z'
        write_private(credentials, json.dumps(identity) + '\n')
    write_private(STAGE / 'ds.env', 'SPRING_DATASOURCE_USERNAME=' + identity['databaseUsername'] + '\nSPRING_DATASOURCE_PASSWORD=' + identity['databasePassword'] + '\n')
    owned_artifacts = artifacts()
    run('docker', 'image', 'inspect', IMAGE)
    run('docker', 'image', 'tag', IMAGE, 'datahub/components-stable-ds:20261102')
    run('docker', 'compose', '-f', str(BASE / 'compose.components-stable.yaml'), 'up', '-d')
    client = Client(SERVER)
    deadline = time.monotonic() + 150
    while True:
        try:
            login = client.request('POST', '/login', {'userName': identity['username'], 'userPassword': identity['password'] if identity['passwordRotated'] else 'dolphinscheduler123'})
            client.session = login['sessionId']; break
        except (RuntimeError, urllib.error.URLError, ConnectionError):
            if time.monotonic() > deadline:
                raise RuntimeError('独立 DS 正常认证入口未在预算内就绪') from None
            time.sleep(2)
    run('docker', 'exec', '-u', '10001', CONTAINER, 'python3', '-c',
        "import json,sys,hashlib,pathlib;files=json.loads(sys.argv[1]);assert all(hashlib.sha256(pathlib.Path(item['path']).read_bytes()).hexdigest()==item['sha256'] for item in files)",
        json.dumps(owned_artifacts))
    user = client.request('GET', '/users/get-user-info')
    if not identity['passwordRotated']:
        client.request('POST', '/users/update', {'id': user['id'], 'userName': identity['username'], 'userPassword': identity['password'],
            'email': user.get('email') or 'components@localhost.test', 'tenantId': user.get('tenantId', -1), 'queue': user.get('queue') or '',
            'phone': user.get('phone') or '', 'state': user.get('state', 1), 'timeZone': user.get('timeZone') or 'Asia/Shanghai'})
        identity['passwordRotated'] = True; write_private(credentials, json.dumps(identity) + '\n')
        client.session = client.request('POST', '/login', {'userName': identity['username'], 'userPassword': identity['password']})['sessionId']
    project_name = 'datahub-components-isolated-20261102'
    project = next((item for item in client.request('GET', '/projects/list') if item['name'] == project_name), None)
    if project is None:
        project = client.request('POST', '/projects', {'projectName': project_name, 'description': '本任务独立组件验收，避免共享 Worker 生命周期影响'})
    tenants = client.request('GET', '/tenants/list')
    if not any(item['tenantCode'] == 'datahub' for item in tenants):
        queues = client.request('GET', '/queues/list')
        if not queues:
            client.request('POST', '/queues', {'queue': 'components-stable', 'queueName': 'components-stable'})
            queues = client.request('GET', '/queues/list')
        client.request('POST', '/tenants', {'tenantCode': 'datahub', 'queueId': queues[0]['id'], 'description': '独立组件验收的非 root 执行用户'})
    addresses = client.request('GET', '/worker-groups/worker-address-list')
    if not addresses:
        raise RuntimeError('独立 DS 未注册本实例 Worker')
    groups = client.request('GET', '/worker-groups/all')
    if 'datahub-development' not in [item if isinstance(item, str) else item['name'] for item in groups]:
        client.request('POST', '/worker-groups', {'name': 'datahub-development', 'addrList': ','.join(addresses), 'description': '独立本地 Worker 组'})
    name = 'components-stable-spark'
    environment = next((item for item in client.request('GET', '/environment/query-environment-list') if item['name'] == name), None)
    if environment is None:
        config = '\n'.join(['export JAVA_HOME=/opt/java17', 'export PATH=$JAVA_HOME/bin:$PATH', 'export SPARK_HOME=/opt/spark',
                             'export SPARK_HOME1=/opt/spark', 'export SPARK_HOME2=/opt/spark', 'export SPARK_LOCAL_IP=127.0.0.1']) + '\n'
        client.request('POST', '/environment/create', {'name': name, 'config': config, 'workerGroups': json.dumps(['datahub-development']), 'description': 'Spark3.5.6 / Java17，未执行引擎任务'})
        environment = next(item for item in client.request('GET', '/environment/query-environment-list') if item['name'] == name)
    existing = client.request('GET', '/access-tokens', {'pageNo': 1, 'pageSize': 100, 'userId': user['id']})['totalList']
    if not any(item.get('token') == identity['token'] for item in existing):
        expiry = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(days=2)
        client.request('POST', '/access-tokens', {'userId': user['id'], 'expireTime': expiry.strftime('%Y-%m-%d %H:%M:%S'), 'token': identity['token']})
    client.session = None; client.token = identity['token']; client.request('GET', '/projects/list')
    run('docker', 'exec', '-u', '10001', CONTAINER, 'touch', '/var/lib/components-stable/metadata/initialized')
    report = {'server': SERVER, 'token': identity['token'], 'username': identity['username'], 'passwordFile': str(credentials),
        'projectName': project_name, 'projectCode': str(project['code']), 'tenantCode': 'datahub', 'workerGroup': 'datahub-development',
        'environmentCodes': {'SPARK': str(environment['code'])}, 'sparkMaster': 'local[1]', 'container': CONTAINER, 'imageId': IMAGE,
        'ownedPaths': {'privateDirectory': str(STAGE), 'metadata': '/var/lib/components-stable/metadata', 'data': '/tmp/dolphinscheduler', 'logs': '/opt/dolphinscheduler/logs'},
        'ownedVolumes': ['components-stable-metadata', 'components-stable-data', 'components-stable-logs'], 'ownedNetwork': 'components-stable-network',
        'immutableArtifacts': owned_artifacts, 'metadataCopiedFromLiveDatabase': False, 'sparkExecuted': False}
    write_private(PRIVATE, json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'prepared': True, 'container': CONTAINER, 'server': SERVER, 'privateConfig': str(PRIVATE), 'sparkExecuted': False, 'immutableArtifacts': len(owned_artifacts)}))


if __name__ == '__main__':
    main()
