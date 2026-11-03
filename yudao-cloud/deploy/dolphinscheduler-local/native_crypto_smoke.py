#!/usr/bin/env python3
"""使用独立临时密钥验证两引擎受管加解密，不读取或保存真实密钥。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid


def invoke(arguments, *, data=None, timeout=120):
    return subprocess.run(arguments, input=data, text=True, capture_output=True, timeout=timeout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    parser.add_argument('--binding', type=Path, default=Path('/tmp/datahub-etl-functions-crypto-binding.json'))
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/components-20261102-crypto.json')
    options = parser.parse_args()
    binding = json.loads(options.binding.read_text())
    stage = '/tmp/datahub-native-crypto-' + uuid.uuid4().hex
    setup = '''from pathlib import Path
import secrets,string,sys
p=Path(sys.argv[1]);p.mkdir(mode=0o700)
key='1'+''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(15))
(p/'testKey').write_text(key)
(p/'testKey').chmod(0o600)
'''
    result = invoke(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', setup, stage])
    if result.returncode:
        raise RuntimeError('临时测试资源创建失败')
    algorithms = ['AES', 'SM4', 'AES128', 'AES256', 'DES', '3DES']
    predicates = ["DH_CRYPTO_DECRYPT('" + algorithm + "', 'testKey', DH_CRYPTO_ENCRYPT('" + algorithm
                  + "', 'testKey', '河道水情😀')) = '河道水情😀'" for algorithm in algorithms]
    predicates.append("DH_CRYPTO_ENCRYPT('AES', 'testKey', CAST(NULL AS STRING)) IS NULL")
    marker = 'DATAHUB_CRYPTO_ROUNDTRIP_OK'
    statement = "SELECT CASE WHEN " + ' AND '.join('(' + item + ')' for item in predicates) + " THEN '" + marker + "' ELSE 'DATAHUB_CRYPTO_FAILED' END"
    report = {'status': 'RUNNING', 'scope': 'direct engine CLI with temporary test key, not management API or DolphinScheduler task acceptance',
              'algorithms': algorithms, 'nullPropagation': True, 'checks': {}}
    try:
        for engine in ['SPARK', 'FLINK']:
            sql = binding['sparkInitializationSql' if engine == 'SPARK' else 'flinkInitializationSql'] + '\n'
            if engine == 'FLINK':
                sql += "SET 'execution.runtime-mode'='BATCH';\nSET 'execution.target'='local';\nSET 'table.dml-sync'='true';\nSET 'parallelism.default'='1';\n"
                sql += "CREATE TEMPORARY TABLE crypto_result(`result_value` STRING) WITH ('connector'='print');\nINSERT INTO crypto_result " + statement + ';\n'
            else:
                sql += statement + ';\n'
            write = 'from pathlib import Path;import sys;Path(sys.argv[1]).write_text(sys.stdin.read())'
            invoke(['docker', 'exec', '-i', '-u', '10001', options.container, 'python3', '-c', write, stage + '/task.sql'], data=sql).check_returncode()
            environment = ['docker', 'exec', '-u', '10001', '-w', stage, '-e', 'JAVA_HOME=/opt/java17',
                           '-e', 'DATAHUB_ETL_SECRET_DIR=' + stage, '-e', 'DATAHUB_ETL_SECRET_REFS=testKey', '-e', 'FLINK_LOG_DIR=' + stage, options.container]
            arguments = ['/opt/spark/bin/spark-sql-managed-original', '--master', 'local[1]', '--driver-memory', '512m', '--conf', 'spark.ui.enabled=false', '-f', stage + '/task.sql'] if engine == 'SPARK' else ['/opt/flink/bin/sql-client-managed-original.sh', '-f', stage + '/task.sql']
            execution = invoke(environment + arguments)
            output = execution.stdout + execution.stderr
            passed = execution.returncode == 0 and marker in output and '[ERROR] Could not execute SQL statement.' not in output
            report['checks'][engine] = {'passed': passed, 'exitCode': execution.returncode, 'markerObserved': marker in output,
                                        'sqlSha256': hashlib.sha256(sql.encode()).hexdigest(), 'outputSha256': hashlib.sha256(output.encode()).hexdigest()}
            if not passed:
                Path('/tmp/datahub-native-crypto-' + engine.lower() + '.log').write_text(output)
                raise RuntimeError(engine + ' 受管加解密验收失败，诊断日志保存在本机 /tmp')
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'
        raise
    finally:
        cleanup = 'import shutil,sys;shutil.rmtree(sys.argv[1])'
        invoke(['docker', 'exec', '-u', '10001', options.container, 'python3', '-c', cleanup, stage]).check_returncode()
        report['temporaryKeysRemoved'] = True
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
