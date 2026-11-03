#!/usr/bin/env python3
"""隔离Flink原生WindowAggregate与正式SQL验收，不连接平台、DS或业务数据源。"""
import argparse
from decimal import Decimal
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl'


def run(arguments, data=None, timeout=120, check=True):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError((result.stdout + result.stderr).decode(errors='replace')[-6000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/streaming-window-runtime-20261103.json')
    options = parser.parse_args()
    name = 'datahub-stream-window-' + uuid.uuid4().hex[:10]
    image = run(['docker', 'inspect', '--format', '{{.Config.Image}}', options.source_worker]).stdout.decode().strip()
    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'scope': 'ISOLATED_FLINK_1_20_3_NO_PLATFORM_OR_DS',
              'workerImage': image, 'cases': [], 'limits': ['处理时间滚动窗口，按到达分窗；不声明事件时间迟到补发或业务目标端到端执行。']}
    options.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        run(['docker', 'run', '-d', '--name', name, '--network', 'none', '--hostname', 'localhost', '--memory', '2g', '--read-only',
             '--tmpfs', '/tmp:rw,nosuid,size=1g,mode=1777', '--entrypoint', '/bin/bash', image, '-c', 'exec tail -f /dev/null'])
        run(['docker', 'exec', name, 'mkdir', '-p', '/tmp/window-state'])
        with tempfile.TemporaryDirectory(prefix='datahub-stream-window-') as directory:
            local = Path(directory); classes = local / 'classes'; classes.mkdir()
            run(['docker', 'cp', name + ':/opt/flink/lib', str(local / 'lib')])
            sources = [MODULE / 'runtime/flink/EtlWindowPickFunction.java', *sorted((MODULE / 'runtime/flink/window-smoke').glob('*.java'))]
            run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', str(local / 'lib/*'), '-d', str(classes), *map(str, sources)])
            archive = io.BytesIO()
            with tarfile.open(fileobj=archive, mode='w') as tar:
                tar.add(classes, arcname='classes')
                for case in ['dedup', 'pivot']:
                    file = MODULE / ('target/native-sql/streaming-' + case + '.sql')
                    sql = file.read_text(); tar.add(file, arcname=case + '.sql')
                    artifact = options.output.parent / (options.output.stem + '-' + case + '.sql'); artifact.write_text(sql)
                    report[case + 'CompilerSqlSha256'] = hashlib.sha256(sql.encode()).hexdigest()
            run(['docker', 'exec', '-i', name, 'tar', '-xf', '-', '-C', '/tmp/window-state'], archive.getvalue())
        java = ['docker', 'exec', name, '/opt/java17/bin/java', '-Xmx768m', '-Dlog.file=/tmp/window-state/runtime.log', '-Dlog4j.configurationFile=file:/opt/flink/conf/log4j-console.properties',
                '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED', '-cp', '/tmp/window-state/classes:/opt/flink/lib/*']
        for case in ['checkpoint-restore', 'dedup', 'pivot']:
            if case == 'checkpoint-restore':
                arguments = ['cn.iocoder.yudao.framework.etl.flink.smoke.WindowStateProbe']; marker = 'WINDOW_STATE_CHECKPOINT_RESTORE_PASS'
            else:
                arguments = ['cn.iocoder.yudao.framework.etl.flink.smoke.FormalWindowSqlProbe', case, '/tmp/window-state/' + case + '.sql']; marker = 'FORMAL_WINDOW_SQL_PASS ' + case
            result = run(java + arguments, check=False)
            output = (result.stdout + result.stderr).decode(errors='replace')
            log = options.output.parent / (options.output.stem + '-' + case + '.log'); log.write_text(output)
            record = {'case': case, 'exitCode': result.returncode, 'log': str(log.relative_to(ROOT))}; report['cases'].append(record)
            if result.returncode or marker not in output: raise AssertionError('实时窗口失败：' + case)
            if case == 'checkpoint-restore':
                if 'RESTORED_SOURCE_INDEX=3' not in output: raise AssertionError('没有从已完成checkpoint恢复源与窗口')
                record.update(passed=True, appendOnlyRows=2, restoredSourceIndex=3)
            else:
                data = run(['docker', 'exec', name, 'python3', '-c', "from pathlib import Path\nfor p in Path('/tmp/window-state/" + case + "-results').rglob('*'):\n if p.is_file() and not p.name.startswith('.') :print(p.read_text(),end='')"]).stdout.decode()
                rows = [json.loads(line, parse_float=Decimal) for line in data.splitlines() if line.strip()]
                if case == 'dedup':
                    expected = [('A', 9007199254740993, 'first'), ('b', 9007199254740995, 'third'), ('day', 9007199254740996, '2026-11-03'), ('a', 9007199254741100, 'next-window')]
                    if sorted((row['key'], row['seq'], row['text']) for row in rows) != sorted(expected): raise AssertionError('去重实际行不一致：' + str(rows))
                else:
                    rows.sort(key=lambda row: row['total'])
                    if len(rows) != 2 or rows[0]['total'] != 7 or rows[0]['last'] != 'next-window' or rows[0]['day'] is not None: raise AssertionError('后续窗口结果不一致：' + str(rows))
                    first = rows[1]
                    if first['total'] != 20 or any(first[key] != 20 for key in ['average', 'minimum', 'maximum']) or first['last'] != 'third' or first['day'] != '2026-11-03 00:00:00': raise AssertionError('聚合/末值/format/nullIf不一致：' + str(rows))
                explain = run(['docker', 'exec', name, 'cat', '/tmp/window-state/' + case + '-explain.txt']).stdout.decode()
                if 'WindowAggregate' not in explain: raise AssertionError('没有实际原生窗口聚合计划')
                plan = options.output.parent / (options.output.stem + '-' + case + '-explain.txt'); plan.write_text(explain)
                record.update(passed=True, rows=rows, explain=str(plan.relative_to(ROOT)))
            print(case + ': PASS', flush=True)
    finally:
        run(['docker', 'rm', '-f', name], check=False)
        options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str))


if __name__ == '__main__':
    main()
