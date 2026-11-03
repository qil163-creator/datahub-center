#!/usr/bin/env python3
"""生成独立 RocketMQ ACL fixture；管理员凭据只留 Broker，Worker 只拿批准资源的应用引用。"""
import json
from pathlib import Path
import secrets
import subprocess

TOPICS = ['components_input', 'components_input_fraction', 'components_input_secret', 'components_input_redirect', 'components_input_auth', 'components_output']
GROUPS = ['components_read', 'components_fraction', 'components_secret', 'components_redirect', 'components_auth', 'components_verify']


def main():
    stage = Path(__file__).resolve().parent / 'runtime/rocket-native'
    stage.mkdir(parents=True, exist_ok=True)
    stage.chmod(0o700)
    private = stage / 'fixture-private.json'
    if private.exists():
        values = json.loads(private.read_text())
    else:
        values = {'accessKey': 'componentsApp', 'secretKey': secrets.token_urlsafe(32),
                  'adminAccessKey': 'componentsAdmin', 'adminSecretKey': secrets.token_urlsafe(32)}
        private.write_text(json.dumps(values)); private.chmod(0o600)
    account = {'accessKey': values['accessKey'], 'secretKey': values['secretKey'], 'admin': False,
               'defaultTopicPerm': 'DENY', 'defaultGroupPerm': 'DENY',
               'topicPerms': [topic + '=PUB|SUB' for topic in TOPICS], 'groupPerms': [group + '=SUB' for group in GROUPS]}
    admin = {'accessKey': values['adminAccessKey'], 'secretKey': values['adminSecretKey'], 'admin': True,
             'defaultTopicPerm': 'DENY', 'defaultGroupPerm': 'DENY'}
    # JSON 是 YAML 的子集，由官方 ACL 加载器读取，不拼接未转义的秘密文本。
    (stage / 'plain_acl.yml').write_text(json.dumps({'globalWhiteRemoteAddresses': [], 'accounts': [account, admin]}, indent=2) + '\n')
    (stage / 'tools.yml').write_text(json.dumps({'accessKey': values['adminAccessKey'], 'secretKey': values['adminSecretKey']}) + '\n')
    for name in ['plain_acl.yml', 'tools.yml']:
        (stage / name).chmod(0o644)
    credential = stage / 'rocket.fixture'
    credential.write_text('nameservers=rocket-native-namesrv:9876\nsourceTopics=' + ','.join(TOPICS) + '\nsinkTopics=' + ','.join(TOPICS) + '\nconsumerGroups=' + ','.join(GROUPS)
                          + '\nproducerGroups=components_write,components_seed\nuseTls=false\naccessKey=' + values['accessKey'] + '\nsecretKey=' + values['secretKey'] + '\n')
    credential.chmod(0o600)
    worker = 'datahub-ds-local-standalone-1'
    subprocess.run(['docker', 'exec', worker, 'mkdir', '-p', '/opt/datahub-rocket-secrets'], check=True)
    subprocess.run(['docker', 'cp', str(credential), worker + ':/opt/datahub-rocket-secrets/rocket.fixture'], check=True)
    subprocess.run(['docker', 'exec', worker, 'chown', '10001:10001', '/opt/datahub-rocket-secrets/rocket.fixture'], check=True)
    subprocess.run(['docker', 'exec', worker, 'chmod', '0400', '/opt/datahub-rocket-secrets/rocket.fixture'], check=True)
    invalid = stage / 'rocket.invalid'
    invalid.write_text(credential.read_text().replace('secretKey=' + values['secretKey'], 'secretKey=deliberatelyIncorrectFixtureSecret'))
    invalid.chmod(0o600)
    subprocess.run(['docker', 'cp', str(invalid), worker + ':/opt/datahub-rocket-secrets/rocket.invalid'], check=True)
    subprocess.run(['docker', 'exec', worker, 'chown', '10001:10001', '/opt/datahub-rocket-secrets/rocket.invalid'], check=True)
    subprocess.run(['docker', 'exec', worker, 'chmod', '0400', '/opt/datahub-rocket-secrets/rocket.invalid'], check=True)
    print(json.dumps({'workerSecretRef': 'rocket.fixture', 'topics': TOPICS, 'consumerGroups': GROUPS, 'adminSecretCopiedToWorker': False, 'plaintextPrinted': False}))


if __name__ == '__main__':
    main()
