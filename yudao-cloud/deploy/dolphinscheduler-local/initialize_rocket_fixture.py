#!/usr/bin/env python3
"""通过官方管理工具建立固定 fixture 主题和消费组；管理员秘密不进入命令参数。"""
import json
import subprocess
from prepare_rocket_fixture import TOPICS, GROUPS


def main():
    prefix = ['docker', 'exec', 'datahub-ds-local-rocket-native-broker-1', 'java', '-Xms32m', '-Xmx128m', '-cp', '../lib/*',
              'org.apache.rocketmq.tools.command.MQAdminStartup']
    for command, flag, names in [('updateTopic', '-t', TOPICS), ('updateSubGroup', '-g', GROUPS)]:
        for name in names:
            args = prefix + [command, '-n', 'rocket-native-namesrv:9876', '-b', 'rocket-native-broker:10911', flag, name]
            if command == 'updateTopic':
                args += ['-r', '2', '-w', '2']
            result = subprocess.run(args, text=True, capture_output=True, timeout=30)
            if result.returncode or 'success' not in result.stdout.lower() or 'Exception' in result.stderr:
                raise RuntimeError('隔离 RocketMQ 资源初始化失败：' + name + '\n' + result.stdout[-1500:] + result.stderr[-1500:])
    print(json.dumps({'topics': TOPICS, 'groups': GROUPS, 'adminCredentialFromMountedFile': True}))


if __name__ == '__main__':
    main()
