#!/usr/bin/env python3
"""新建独立XXL协调器任务并验证真实回调，不修改既有任务。"""

import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request

from prepare_fixture import secure_write


class XxlAdmin:
    def __init__(self, base, username, password):
        target = urllib.parse.urlsplit(base)
        if target.hostname != '127.0.0.1' or target.port != 18989:
            raise RuntimeError('仅允许已授权本地XXL管理端')
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if self.post('/login', {'userName': username, 'password': password, 'ifRemember': 'off'}).get('code') != 200:
            raise RuntimeError('真实XXL管理登录失败，未输出凭据')

    def post(self, path, body):
        req = urllib.request.Request(self.base + path, data=urllib.parse.urlencode(body).encode(), method='POST',
                                     headers={'Content-Type': 'application/x-www-form-urlencoded'})
        with self.opener.open(req, timeout=20) as response:
            return json.load(response)

    def checked(self, path, body):
        result = self.post(path, body)
        if result.get('code') != 200:
            raise RuntimeError('XXL管理操作未通过：' + path + ' code=' + str(result.get('code')))
        return result.get('content')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--admin-user', required=True)
    parser.add_argument('--password-env', default='XXL_POLICY_ADMIN_PASSWORD')
    parser.add_argument('--enable', action='store_true')
    parser.add_argument('--main', action='store_true')
    args = parser.parse_args()
    if args.config.stat().st_mode & 0o777 != 0o600:
        raise RuntimeError('私密配置必须为0600')
    cfg = json.loads(args.config.read_text())
    admin = XxlAdmin(cfg['xxl']['adminAddress'], args.admin_user, os.environ[args.password_env])
    group = cfg['xxl']['mainGroupId'] if args.main else cfg['xxl']['groupId']
    app = cfg['xxl']['mainAppName'] if args.main else cfg['xxl']['appName']
    port = 10026 if args.main else 10027
    field = 'mainCoordinatorJobId' if args.main else 'coordinatorJobId'
    expected = 'dataforge-job-local-50026' if args.main else 'dataforge-job-e2e-50027'
    if app != expected or group not in [cfg['xxl']['groupId'], cfg['xxl']['mainGroupId']]:
        raise RuntimeError('仅允许已授权的新独立executor组，拒绝旧采集组')
    job = cfg['xxl'].get(field)
    if not job:
        job = int(admin.checked('/jobinfo/add', {
            'jobGroup': group, 'jobDesc': '作业策略验收协调器', 'author': 'codex', 'alarmEmail': '',
            'scheduleType': 'CRON', 'scheduleConf': '*/5 * * * * ?', 'misfireStrategy': 'DO_NOTHING',
            'executorRouteStrategy': 'FIRST', 'executorHandler': 'jobManagementCoordinatorJob', 'executorParam': '',
            'executorBlockStrategy': 'SERIAL_EXECUTION', 'executorTimeout': 60, 'executorFailRetryCount': 0,
            'glueType': 'BEAN', 'glueSource': '', 'glueRemark': '独立真实回调验收', 'childJobId': ''}))
        cfg['xxl'][field] = job
        secure_write(args.config, cfg)
    for _ in range(20):
        page = admin.post('/jobgroup/pageList', {'start': 0, 'length': 100, 'appname': app, 'title': ''})
        registered = next((row for row in page['data'] if row['id'] == group), None)
        if registered and str(port) in (registered.get('addressList') or ''):
            break
        time.sleep(2)
    else:
        raise RuntimeError('新executor10027尚未真实注册，未启用周期')
    old = admin.post('/joblog/pageList', {'jobGroup': group, 'jobId': job, 'logStatus': 0, 'start': 0, 'length': 20})
    prior = {row['id'] for row in old.get('data', [])}
    # 这里只手动回调空队列协调器，尚未开启任何业务Cron；不能当成业务周期证据。
    admin.checked('/jobinfo/trigger', {'id': job, 'executorParam': '', 'addressList': ''})
    actual = None
    for _ in range(20):
        page = admin.post('/joblog/pageList', {'jobGroup': group, 'jobId': job, 'logStatus': 0, 'start': 0, 'length': 20})
        actual = next((row for row in page.get('data', []) if row['id'] not in prior), None)
        if actual and actual.get('handleCode') == 200 and actual.get('triggerCode') == 200:
            break
        if actual and actual.get('handleCode', 0) not in [0, 200]:
            raise RuntimeError('真实协调器回调失败，保留日志，不自动重投业务')
        time.sleep(1)
    else:
        raise RuntimeError('真实协调器回调未确认，未启用周期')
    if args.enable:
        admin.checked('/jobinfo/start', {'id': job})
    proof = {'groupId': group, 'jobId': job, 'appName': app, 'executorPort': port,
             'handler': 'jobManagementCoordinatorJob', 'actualCallbackLogId': actual['id'],
             'triggerCode': actual['triggerCode'], 'handleCode': actual['handleCode'],
             'coordinatorCron': '*/5 * * * * ?', 'coordinatorEnabled': args.enable,
             'businessCronTriggeredByThisProbe': False, 'existingJobsModified': False}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    secure_write(args.output_dir / ('main-coordinator-proof.json' if args.main else 'coordinator-proof.json'), proof)
    print(json.dumps(proof, ensure_ascii=False))


if __name__ == '__main__':
    main()
