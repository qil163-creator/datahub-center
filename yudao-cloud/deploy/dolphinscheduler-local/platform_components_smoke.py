#!/usr/bin/env python3
"""通过正式集成 API 发布并执行独立组件任务，核对 FTP 夹具中的真实完整 CSV。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True, help='只含本地验收授权的0600私密配置')
    parser.add_argument('--cases', default='http-ftp,crypto-ftp')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.config.stat().st_mode & 0o077:
        parser.error('私密验收配置必须仅当前用户可读写')

    def call(path, method='GET', body=None, query=None):
        url = config['apiBase'].rstrip('/') + path + ('?' + urllib.parse.urlencode(query) if query else '')
        request = urllib.request.Request(url, None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                                        headers={'Authorization': 'Bearer ' + config['apiToken'], 'Content-Type': 'application/json'}, method=method)
        with urllib.request.urlopen(request, timeout=45) as response:
            value = json.load(response)
        if value.get('code') != 0:
            raise RuntimeError(json.dumps({'path': path, 'code': value.get('code'), 'message': value.get('msg')}, ensure_ascii=False))
        return value.get('data')

    report = {'status': 'RUNNING', 'scope': 'DataForge API -> DolphinScheduler native task -> real Spark3.5.6 -> loopback FTP fixture', 'checks': {}}
    try:
        for name in args.cases.split(','):
            case = config['cases'][name]
            flow = json.loads(Path(case['flowFile']).read_text())
            task = call('/get', query={'id': flow['id']})
            if task['status'] != 0:
                raise RuntimeError('验收任务必须下线，不能覆盖正在运行或已上线的任务')
            flow['revision'] = task['revision']
            checked = call('/check', 'POST', flow)
            if not checked.get('valid'):
                raise RuntimeError('组件流程检查未通过：' + json.dumps(checked, ensure_ascii=False))
            saved = call('/flow', 'PUT', flow)
            call('/status', 'PUT', {'id': flow['id'], 'revision': saved['revision'], 'status': 1})
            record_id = call('/run', 'POST', query={'id': flow['id']})
            result = {'taskId': flow['id'], 'recordId': record_id, 'definitionCode': saved['dsDefinitionCode'],
                      'flowSha256': hashlib.sha256(flow['flowJson'].encode()).hexdigest(), 'observedRunning': False}
            report['checks'][name] = result
            print(json.dumps({'case': name, 'taskId': flow['id'], 'recordId': record_id, 'submitted': True}), flush=True)
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                records = call('/records', query={'taskId': flow['id'], 'pageNo': 1, 'pageSize': 10})
                record = next((item for item in records['list'] if str(item['id']) == str(record_id)), None)
                if record:
                    result['status'] = record['status']; result['dsInstanceId'] = record.get('dsInstanceId')
                    if record['status'] == 'RUNNING': result['observedRunning'] = True
                    if record['status'] not in ['RUNNING', 'WAITING', 'SUBMITTED', 'STOPPING', 'UNKNOWN']: break
                time.sleep(2)
            if result.get('status') != 'SUCCESS':
                raise RuntimeError('原生任务未确认成功：' + json.dumps(result, ensure_ascii=False))
            directory = case['ftpDirectory']
            if directory not in ['http', 'crypto', 'token']:
                raise ValueError('仅允许读取本验收夹具拥有的固定目录')
            reader = 'import csv,json,pathlib,sys;p=pathlib.Path(sys.argv[1]);files=sorted(p.iterdir());assert files and all(f.is_file() and not f.is_symlink() for f in files);rows=[]\nfor f in files:\n with f.open(newline="") as stream:rows.extend(list(csv.reader(stream)))\nprint(json.dumps({"files":[f.name for f in files],"rows":rows},ensure_ascii=False))'
            output = subprocess.check_output(['docker', 'exec', '-u', '10001', config['container'], 'python3', '-c', reader, config['fixtureRoot'] + '/ftp/' + directory], text=True)
            target = json.loads(output)
            if target['rows'] != case['expectedRows']:
                raise RuntimeError('目标 CSV 与声明的完整精确数据不同')
            result.update(passed=True, actualFiles=target['files'], actualRows=target['rows'])
            print(json.dumps({'case': name, 'passed': True, 'dsInstanceId': result['dsInstanceId']}), flush=True)
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
