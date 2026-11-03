#!/usr/bin/env python3
"""执行正式Compiler/Expander生成的有界组合；隔离CLI验收，不操作平台或原DS容器。"""
import argparse
from decimal import Decimal
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-control'


def run(arguments, *, data=None, check=True, timeout=60):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError((result.stderr + result.stdout).decode(errors='replace')[-7000:])
    return result


def expected():
    marker = "O'Reilly\\$1😀"; trace = "子流程'O\\$😀"
    rows = []
    for identity, amount, name, bucket, copied in [(2, Decimal('0.0100'), "O'Reilly", 'small', "O'Reilly"),
            (9007199254740993, Decimal('12.3400'), '水🌊', 'big', '江河'), (None, None, None, 'missing', None)]:
        for which, value in [('copied', copied), ('mapped', name)]:
            rows.append({'id': identity, 'amount': amount, '名称': name, 'bucket': bucket, 'marker': marker, 'trace': trace, 'which': which, 'value': value})
    return {'a_out': rows,
            'c_out': [{'group': 'MiXeD', 'id': 1, 'payload': 'first'}, {'group': None, 'id': 3, 'payload': 'null-first'}, {'group': 'H', 'id': 5, 'payload': None}],
            'd_out': [{'id': 'Ab', 'n': 1}, {'id': 'Other', 'n': 3}, {'id': None, 'n': 2}]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--engines', nargs='+', choices=['spark', 'flink'], default=['spark', 'flink'])
    parser.add_argument('--cases', nargs='+', choices=['all', 'dedup-duplicate'], default=['all', 'dedup-duplicate'])
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-native-control-transform.json')
    options = parser.parse_args(); cases = json.loads((FIXTURES / 'manifest.json').read_text())
    image = run(['docker', 'inspect', '--format', '{{.Config.Image}}', options.source_worker]).stdout.decode().strip()
    name = 'datahub-ds-local-native-control-worker'; run_id = uuid.uuid4().hex[:12]
    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'submission': 'ISOLATED_NATIVE_CLI_NOT_DS_INSTANCE',
              'javaRelease': 17, 'workerImage': image, 'cases': [], 'source': 'EtlNativeControlTransformFixtureTest -> EtlSubflowExpander -> EtlNativeSqlCompiler',
              'reusedEvidence': [
                  {'path': 'verification/graphs.json', 'components': ['multi-join', 'data-condition'], 'scope': 'graph_smoke.py读取真实Compiler SQL，DS两引擎LEFT/NULL与IF/ELSE精确值'},
                  {'path': 'verification/join-budget.json', 'components': ['multi-join'], 'scope': '两引擎Join maxOutputRows真实失败'},
                  {'path': 'verification/components-20261102-transforms.json', 'components': ['calculator', 'field-derive', 'string-operation', 'string-replace', 'field-split', 'row-to-column', 'id-generator'], 'scope': '既有6个真实有界CLI场景，本轮不重复'}],
              'limits': ['只声明有界Spark/Flink原生CLI与固定子流程解析夹具，不声明本报告通过平台或DS触发', '去重的排序与唯一字段由用户明确指定；完全重复先消除，不同payload的重复排序键明确失败', '按键与忽略大小写保留首条完整原行，NULL分组与ASCII大小写已覆盖；实时状态/窗口不在本次范围']}
    if options.resume and options.output.is_file():
        report = json.loads(options.output.read_text())
    try:
        for engine in options.engines:
            for case in [entry for entry in cases if entry['engine'].lower() == engine and entry['case'] in options.cases]:
                run(['docker', 'rm', '-f', name], check=False)
                run(['docker', 'run', '-d', '--name', name, '--network', 'datahub-ds-local_default', '--memory', '1536m', '--read-only',
                     '--tmpfs', '/tmp:rw,nosuid,size=768m,mode=1777', '--entrypoint', '/bin/bash', image, '-c', 'exec tail -f /dev/null'])
                stage = '/tmp/datahub-control-' + run_id + '-' + engine + '-' + case['case']; data_path = stage + '/data'
                sql = (FIXTURES / case['file']).read_text().replace('__DATAHUB_CONTROL_RESULT__', data_path)
                saved_sql = options.output.parent / (options.output.stem + '-' + engine + '-' + case['case'] + '.sql')
                saved_sql.parent.mkdir(parents=True, exist_ok=True); saved_sql.write_text(sql)
                remote = stage + '/flow.sql'
                run(['docker', 'exec', '-i', name, 'python3', '-c',
                     "from pathlib import Path;import os,sys;p=Path('" + stage + "');p.mkdir();os.chown(p,10001,10001);(p/'flow.sql').write_bytes(sys.stdin.buffer.read());(p/'flow.sql').chmod(0o644);(p/'flink-log').mkdir();os.chown(p/'flink-log',10001,10001)"], data=sql.encode())
                arguments = ['docker', 'exec', '-w', stage, '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'FLINK_LOG_DIR=' + stage + '/flink-log', '-e', 'FLINK_ENV_JAVA_OPTS_CLI=-Xmx768m', name]
                if engine == 'spark':
                    arguments += ['/opt/spark/bin/spark-sql', '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.ui.enabled=false',
                                  '--conf', 'spark.sql.parser.escapedStringLiterals=false', '--conf', 'spark.hadoop.fs.defaultFS=file:///',
                                  '--conf', 'spark.sql.warehouse.dir=' + stage + '/warehouse', '--conf', 'spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:' + stage + '/metastore_db;create=true', '-f', remote]
                else:
                    arguments += ['/opt/flink/bin/sql-client.sh', '-Dexecution.target=local', '-Dparallelism.default=1', '-Drestart-strategy.type=none', '-Dtaskmanager.numberOfTaskSlots=1',
                                  '-Djobmanager.memory.process.size=768m', '-Dtaskmanager.memory.process.size=768m', '-f', remote]
                execution = run(arguments, check=False, timeout=180)
                output = (execution.stderr + execution.stdout).decode(errors='replace')
                service_output = ''
                if engine == 'flink':
                    service_output = run(['docker', 'exec', name, 'python3', '-c', "from pathlib import Path\nfor p in sorted(Path('" + stage + "/flink-log').glob('*')):\n if p.is_file(): print('\\nFILE '+p.name+'\\n'+p.read_bytes()[-2097152:].decode(errors='replace'))"]).stdout.decode(errors='replace')
                    service_log = options.output.parent / (options.output.stem + '-' + engine + '-' + case['case'] + '-service.log')
                    service_log.write_text(service_output)
                log = options.output.parent / (options.output.stem + '-' + engine + '-' + case['case'] + '.log'); log.parent.mkdir(parents=True, exist_ok=True); log.write_text(output)
                record = {'engine': engine, 'case': case['case'], 'exitCode': execution.returncode, 'sqlSha256': hashlib.sha256(sql.encode()).hexdigest(),
                          'compilerSqlSha256': case['compilerSqlSha256'], 'sqlFile': str(saved_sql.relative_to(ROOT)), 'log': str(log.relative_to(ROOT)), 'workerImageId': run(['docker', 'inspect', '--format', '{{.Image}}', name]).stdout.decode().strip()}
                report['cases'] = [old for old in report['cases'] if not (old['engine'] == engine and old['case'] == case['case'])]
                report['cases'].append(record)
                if engine == 'flink': record['serviceLog'] = str(service_log.relative_to(ROOT))
                if case['case'] == 'all':
                    if execution.returncode or '[ERROR] Could not execute SQL statement.' in output:
                        raise AssertionError('组合原生SQL执行失败，见对应日志')
                    actual = {}
                    for table, expectation in expected().items():
                        content = run(['docker', 'exec', name, 'python3', '-c', "from pathlib import Path\nfor p in sorted(Path('" + data_path + '/' + table + "').rglob('part*')):\n if p.is_file(): print(p.read_text(),end='')"]).stdout.decode()
                        rows = [json.loads(line, parse_float=Decimal) for line in content.splitlines() if line.strip()]
                        normalized = [{key: row.get(key) for key in expectation[0]} for row in rows]
                        if normalized != expectation: raise AssertionError(table + '逐值或实际行顺序不一致：' + str(normalized))
                        actual[table] = normalized
                    record.update(passed=True, rows=actual, exactDuplicatesAndNullGroups=True, asciiIgnoreCasePreservesFirstOriginal=True)
                else:
                    signature = case['expectedFailure']; evidence = output + '\n' + service_output
                    runtime_cause = re.search(r'(?:RuntimeException|NumberFormatException|SparkRuntimeException):[^\n]*' + re.escape(signature) + r'[^\n]*', evidence)
                    rejected = execution.returncode != 0 and runtime_cause is not None
                    if not rejected: raise AssertionError('重复排序键未由原生引擎明确拒绝')
                    record.update(passed=True, engineRejected=True, expectedFailure=signature, dedicatedRuntimeCauseVerified=True, runtimeFailureEvidence=runtime_cause.group(0))
                report['updatedAt'] = time.strftime('%Y-%m-%dT%H:%M:%S%z')
                options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + '\n')
                print(json.dumps({key: value for key, value in record.items() if key != 'rows'}, ensure_ascii=False), flush=True)
                run(['docker', 'stop', '-t', '1', name])
    except Exception as failure:
        if report['cases'] and not report['cases'][-1].get('passed'):
            report['cases'][-1].update(passed=False, error=str(failure))
        options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + '\n')
        raise
    finally:
        run(['docker', 'stop', '-t', '1', name], check=False)


if __name__ == '__main__': main()
