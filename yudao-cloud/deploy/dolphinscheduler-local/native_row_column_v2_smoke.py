#!/usr/bin/env python3
"""在独立只读容器执行正式编译器的行列V2 SQL，不触碰平台任务、DS或外部业务表。"""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql'
ROWS = [
    (1, 1, 'sale', '10.0000', '0.0000', 'first', '2026-11-01', True),
    (1, 2, 'sale', '20.0000', '0.0000', 'second', 'missing', False),
    (1, 3, 'cost', '0.0000', '2.0000', 'third', 'missing', True),
    (1, 4, 'cost', '0.0000', '4.0000', 'final', 'missing', False),
    (1, 5, 'date', '0.0000', '0.0000', 'ignore', '2026-11-03', True),
    (2, 6, 'sale', '5.0000', '0.0000', 'only', 'missing', True),
    (2, 7, 'date', '0.0000', '0.0000', 'ignored', 'missing', False),
]
COLS = [('group_id', 'BIGINT'), ('seq', 'BIGINT'), ('code', 'STRING'), ('amount', 'DECIMAL(20,4)'),
        ('other', 'DECIMAL(20,4)'), ('raw', 'STRING'), ('day', 'STRING'), ('flag', 'BOOLEAN')]
OUTPUTS = {
    'unpivot-v2': [('group_id', 'BIGINT'), ('seq', 'BIGINT'), ('code', 'STRING'), ('day', 'STRING'), ('flag', 'BOOLEAN'), ('metric', 'STRING'), ('value', 'DECIMAL(20,4)'), ('text', 'STRING')],
    'pivot-aggregate-v2': [('group_id', 'BIGINT'), ('total', 'DECIMAL(20,4)'), ('average', 'DOUBLE'), ('minimum', 'DOUBLE'), ('maximum', 'DOUBLE'), ('date_value', 'TIMESTAMP')],
    'pivot-last-v2': [('group_id', 'BIGINT'), ('last', 'STRING')],
}


def run(args, *, data=None, check=True, timeout=60):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError((result.stderr + result.stdout).decode(errors='replace')[-6000:])
    return result


def literal(value):
    if isinstance(value, bool):
        return 'TRUE' if value else 'FALSE'
    return "'" + str(value).replace("'", "''") + "'"


def source_sql():
    return 'CREATE TEMPORARY VIEW row_column_source AS ' + ' UNION ALL '.join(
        'SELECT ' + ', '.join('CAST(' + literal(value) + ' AS ' + kind + ') AS `' + name + '`'
                              for (name, kind), value in zip(COLS, row)) for row in ROWS) + ';\n'


def expectation(case):
    if case == 'pivot-aggregate-v2':
        return [{'group_id': 1, 'total': Decimal(30), 'average': Decimal(3), 'minimum': Decimal(10), 'maximum': Decimal(20), 'date_value': '2026-11-03'},
                {'group_id': 2, 'total': Decimal(5), 'average': None, 'minimum': Decimal(5), 'maximum': Decimal(5), 'date_value': None}]
    if case == 'pivot-last-v2':
        return [{'group_id': 1, 'last': 'final'}, {'group_id': 2, 'last': 'only'}]
    rows = []
    for group, seq, code, amount, other, raw, day, flag in ROWS:
        for metric, value, text in [("sale's", Decimal(amount), None), ('cost', Decimal(other), None), ('note', None, raw)]:
            rows.append(dict(group_id=group, seq=seq, code=code, day=day, flag=flag, metric=metric, value=value, text=text))
    return rows


def canonical(rows, keys):
    normalized = []
    for row in rows:
        result = {key: row.get(key) for key in keys}
        if result.get('date_value'):
            result['date_value'] = str(result['date_value'])[:10]
        normalized.append(result)
    return sorted(normalized, key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False, default=str))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--engines', nargs='+', default=['spark', 'flink'], choices=['spark', 'flink'])
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/row-column-v2-runtime-20261103.json')
    options = parser.parse_args()
    image = run(['docker', 'inspect', '--format', '{{.Config.Image}}', options.source_worker]).stdout.decode().strip()
    name = 'datahub-row-column-v2-' + uuid.uuid4().hex[:10]
    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'scope': 'ISOLATED_NATIVE_CLI_NOT_PLATFORM_OR_DS', 'workerImage': image,
              'compiler': 'EtlNativeRowColumnV2Test -> EtlNativeSqlCompiler', 'cases': [],
              'limits': ['只声明有界批处理真实SQL及精确结果，不声明实时状态或平台任务执行；参考站没有保存任何节点配置。']}
    options.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for engine in options.engines:
            for case, columns in OUTPUTS.items():
                run(['docker', 'rm', '-f', name], check=False)
                run(['docker', 'run', '-d', '--name', name, '--network', 'none', '--hostname', 'localhost', '--memory', '2g', '--read-only',
                     '--tmpfs', '/tmp:rw,nosuid,size=1g,mode=1777', '--entrypoint', '/bin/bash', image, '-c', 'exec tail -f /dev/null'])
                stage = '/tmp/row-column'; target = stage + '/result'
                compiled = (FIXTURES / (case + '-' + engine + '.sql')).read_text()
                ddl = ', '.join('`' + column + '` ' + kind for column, kind in columns)
                prefix = "SET 'execution.runtime-mode' = 'BATCH';\nSET 'table.dml-sync' = 'true';\n" if engine == 'flink' else "SET spark.sql.session.timeZone=UTC;\n"
                sink = ('CREATE TEMPORARY TABLE row_column_target (' + ddl + ") WITH ('connector'='filesystem','path'='" + target + "','format'='json');\n") if engine == 'flink' else ('CREATE TABLE row_column_target (' + ddl + ") USING JSON LOCATION '" + target + "';\n")
                sql = prefix + source_sql() + sink + compiled + ';\n'
                sql_path = options.output.parent / (options.output.stem + '-' + engine + '-' + case + '.sql'); sql_path.write_text(sql)
                run(['docker', 'exec', '-i', name, 'python3', '-c', "from pathlib import Path;import os,sys;p=Path('/tmp/row-column');p.mkdir();os.chown(p,10001,10001);(p/'flow.sql').write_bytes(sys.stdin.buffer.read());(p/'flink-log').mkdir();os.chown(p/'flink-log',10001,10001)"], data=sql.encode())
                args = ['docker', 'exec', '-w', stage, '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'SPARK_LOCAL_IP=127.0.0.1', '-e', 'FLINK_LOG_DIR=' + stage + '/flink-log', '-e', 'FLINK_ENV_JAVA_OPTS_CLI=-Xmx768m', name]
                if engine == 'spark':
                    args += ['/opt/spark/bin/spark-sql', '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.ui.enabled=false', '--conf', 'spark.sql.parser.escapedStringLiterals=false', '--conf', 'spark.hadoop.fs.defaultFS=file:///', '--conf', 'spark.sql.warehouse.dir=' + stage + '/warehouse', '--conf', 'spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:' + stage + '/metastore_db;create=true', '-f', stage + '/flow.sql']
                else:
                    args += ['/opt/flink/bin/sql-client.sh', '-Dexecution.target=local', '-Dparallelism.default=1', '-Drestart-strategy.type=none', '-Dtaskmanager.numberOfTaskSlots=1', '-Djobmanager.memory.process.size=768m', '-Dtaskmanager.memory.process.size=768m', '-f', stage + '/flow.sql']
                result = run(args, check=False, timeout=180)
                text = (result.stdout + result.stderr).decode(errors='replace')
                log = options.output.parent / (options.output.stem + '-' + engine + '-' + case + '.log'); log.write_text(text)
                record = dict(engine=engine, case=case, exitCode=result.returncode, compilerSqlSha256=hashlib.sha256(compiled.encode()).hexdigest(), sql=str(sql_path.relative_to(ROOT)), log=str(log.relative_to(ROOT)))
                report['cases'].append(record)
                if result.returncode or '[ERROR] Could not execute SQL statement.' in text:
                    raise AssertionError('行列V2原生SQL失败：' + str(log))
                content = run(['docker', 'exec', name, 'python3', '-c', "from pathlib import Path\nfor p in sorted(Path('/tmp/row-column/result').rglob('part*')):\n if p.is_file(): print(p.read_text(),end='')"]).stdout.decode()
                actual = [json.loads(line, parse_float=Decimal) for line in content.splitlines() if line.strip()]
                expected = expectation(case); keys = [name for name, _ in columns]
                record.update(rows=len(actual), expectedRows=len(expected))
                if canonical(actual, keys) != canonical(expected, keys):
                    record['actual'] = actual
                    raise AssertionError('行列V2实际结果不一致：' + engine + '/' + case)
                record['passed'] = True
                options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str))
                print(engine + '/' + case + ': PASS rows=' + str(len(actual)), flush=True)
    finally:
        run(['docker', 'rm', '-f', name], check=False)
        options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str))


if __name__ == '__main__':
    main()
