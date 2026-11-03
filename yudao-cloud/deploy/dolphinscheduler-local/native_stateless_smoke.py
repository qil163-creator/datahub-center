#!/usr/bin/env python3
"""运行编译器生成的有界样例并核对真实引擎 JSON；不会发布或启动平台任务。"""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


def command(*arguments, timeout=300):
    return subprocess.run(arguments, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)


def expected_stateless(row):
    expected = {'r_ADD': '8', 'r_SUB': '4', 'r_MUL': '12', 'r_DIV': '3', 'r_ADD3': '9.25',
                'r_MIN': '2', 'r_MAX': '6', 'r_ABS': '6', 'r_ROUND': '6', 'r_CEIL': '6', 'r_FLOOR': '6',
                'r_MOD': '0', 'r_POWER': '36', 'r_DIFF_DAYS': '0', 'r_YEAR': '2026', 'r_MONTH': '1',
                'r_DAY': '2', 'r_STR_LEN': '8', 'r_UPPER': '  A😀B  ', 'r_LOWER': '  a😀b  ',
                'r_INIT_CAP': '  a😀b  ', 'r_TRIM': 'a😀b', 'd_CONCAT': '[  a😀b  |6.0000]',
                'd_SUBSTRING': 'a', 'd_HASH': hashlib.sha256('  a😀b  '.encode()).hexdigest(),
                'd_REGEX_EXTRACT': 'a', 'd_REPLACE': '  aa😀b  ', 'd_EXPRESSION': '16',
                'd_CONSTANT': "O'Reilly\\$1", 's_LEFT': 'xyxyx  a😀b  ', 's_RIGHT': '  a😀b  xyxyx',
                's_BOTH': 'xy  a😀b  xyx', 's_replacement': '  $1tail😀b  ', 's_word': '  W😀b  ',
                's_word_alt': 'ab W á _a_', 's_named': '  aa😀b  ', 's_null': None,
                'part_int': '12', 'part_decimal': '34.00', 'part_empty': '', 'part_missing': None}
    for key, value in expected.items():
        if row.get(key) != value:
            raise AssertionError(f'{key}: actual={row.get(key)!r}, expected={value!r}')
    if abs(Decimal(row['r_SQRT']) - Decimal(6).sqrt()) > Decimal('0.00000000000001'):
        raise AssertionError('SQRT数值误差超过预算')
    for key, value in [('r_ADD_DAYS', '2026-01-04 03:04:05'), ('c_date', '2026-01-02 03:04:05'), ('part_date', '2026-01-02 03:04:05')]:
        if datetime.fromisoformat(row[key]) != datetime.fromisoformat(value):
            raise AssertionError(f'{key} 日期结果不一致')


def expected_ordered(rows):
    by_group = {row['gid']: row for row in rows}
    if len(rows) != 2 or set(by_group) != {'g1', 'g2'}:
        raise AssertionError('行转列分组结果不一致')
    expected = {'g1': (Decimal('2.2'), Decimal('3.3'), 9007199254740993), 'g2': (None, None, 9007199254741000)}
    for group, values in expected.items():
        row = by_group[group]
        if (row.get('a'), row.get('b'), row['sequence_id']) != values:
            raise AssertionError(f'{group} 末值或无损序列不一致: {row}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--binding', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--engines', nargs='+', choices=['spark', 'flink'], default=['spark', 'flink'])
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    fixtures = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql'
    binding = json.loads(options.binding.read_text())
    run_id = uuid.uuid4().hex[:12]
    container_root = '/tmp/datahub-stateless-smoke-' + run_id
    command('docker', 'exec', options.container, 'mkdir', '-p', container_root).check_returncode()
    result = {'timestamp': datetime.now(timezone.utc).isoformat(), 'runId': run_id,
              'jarSha256': binding['jarSha256'], 'checks': [], 'scope': '真实有界原生SQL，不代表平台任务或持续源验收'}
    for engine in options.engines:
        for kind in ['stateless', 'ordered', 'ordered-duplicate']:
            file = fixtures / (kind.replace('-duplicate', '') + '-' + engine + ('-duplicate' if kind.endswith('-duplicate') else '') + '.sql')
            sql = file.read_text()
            data_path = container_root + '/' + engine + '-' + kind + '-data'
            old_path = '/tmp/datahub-native-' + ('stateless-' if kind == 'stateless' else 'ordered-') + engine + ('-duplicate' if kind.endswith('-duplicate') else '') + '-result'
            sql = sql.replace(old_path, data_path)
            table = 'sink' if kind == 'stateless' else 'ordered_sink'
            sql = re.sub(r'\b' + table + r'\b', table + '_' + run_id + '_' + kind.replace('-', '_'), sql)
            initialization = binding[engine + 'InitializationSql']
            sql = initialization + '\n' + sql
            remote_file = container_root + '/' + engine + '-' + kind + '.sql'
            with tempfile.TemporaryDirectory(prefix='datahub-stateless-') as temporary:
                local = Path(temporary) / 'query.sql'; local.write_text(sql)
                command('docker', 'cp', str(local), options.container + ':' + remote_file).check_returncode()
            arguments = ['docker', 'exec', '-e', 'JAVA_HOME=/opt/java17', options.container]
            if engine == 'spark':
                arguments += ['/opt/spark/bin/spark-sql', '--master', 'local[1]', '--conf', 'spark.ui.enabled=false', '-f', remote_file]
            else:
                arguments += ['/opt/flink/bin/sql-client.sh', '-Dexecution.target=local', '-Dparallelism.default=1', '-f', remote_file]
            execution = command(*arguments)
            log = options.output.parent / (options.output.stem + '-' + engine + '-' + kind + '.log')
            log.parent.mkdir(parents=True, exist_ok=True); log.write_text(execution.stdout)
            check = {'engine': engine, 'case': kind, 'exitCode': execution.returncode, 'sqlSha256': hashlib.sha256(sql.encode()).hexdigest(), 'log': str(log)}
            try:
                if kind.endswith('-duplicate'):
                    if execution.returncode == 0 or '唯一排序字段存在重复值' not in execution.stdout:
                        raise AssertionError('重复唯一键没有触发明确的引擎失败')
                else:
                    if execution.returncode != 0 or '[ERROR] Could not execute SQL statement.' in execution.stdout:
                        raise AssertionError('原生SQL执行失败')
                    listing = command('docker', 'exec', options.container, 'find', data_path, '-type', 'f', '-name', 'part*')
                    listing.check_returncode(); rows = []
                    for data_file in listing.stdout.splitlines():
                        content = command('docker', 'exec', options.container, 'cat', data_file); content.check_returncode()
                        rows += [json.loads(line, parse_float=Decimal) for line in content.stdout.splitlines() if line.strip()]
                    if kind == 'stateless':
                        if len(rows) != 1: raise AssertionError(f'应输出1行，实际{len(rows)}')
                        expected_stateless(rows[0])
                    else:
                        expected_ordered(rows)
                    check['rows'] = rows
                check['passed'] = True
            except (AssertionError, KeyError, ValueError) as failure:
                check['passed'] = False; check['error'] = str(failure)
            result['checks'].append(check)
            options.output.parent.mkdir(parents=True, exist_ok=True)
            options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str) + '\n')
            print(json.dumps({key: value for key, value in check.items() if key != 'rows'}, ensure_ascii=False), flush=True)
            if not check['passed']: raise SystemExit(1)


if __name__ == '__main__':
    main()
