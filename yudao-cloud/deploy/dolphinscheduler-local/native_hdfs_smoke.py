#!/usr/bin/env python3
"""以真实 HDFS/Spark 验证受管路径、CSV/TEXT、前缀、编码、压缩和新增文件不覆盖。"""
import argparse
import bz2
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / 'yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-hdfs-sql'
FS = 'hdfs://hdfs-native-namenode:8020'
BOUND = '/datahub_components'


def run(arguments, *, data=None, check=True, timeout=60):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace')[-5000:] + result.stdout.decode(errors='replace')[-5000:])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker', default='datahub-ds-local-standalone-1')
    parser.add_argument('--namenode', default='datahub-ds-local-hdfs-native-namenode-1')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'verification/native-hdfs-components-20261102.json')
    options = parser.parse_args()
    prefix = BOUND + '/hdfs_acceptance_' + uuid.uuid4().hex[:12]

    def hdfs(*arguments, data=None, check=True):
        command = ['docker', 'exec'] + (['-i'] if data is not None else []) + [options.namenode, 'hdfs', 'dfs', *arguments]
        return run(command, data=data, check=check)

    hdfs('-mkdir', '-p', prefix)
    hdfs('-chown', 'datahub', prefix)
    body = 'id,amount,day,label,empty,optional\n9007199254740993,12345678901234567890.1234,2026-11-02,"河道,a ""B""\nline","",\n'
    hdfs('-put', '-', prefix + '/input.csv', data=body.encode())
    plain = 'id,amount,day,label,empty,optional\n9007199254740993,12345678901234567890.1234,2026-11-02,河道plain,EMPTY,\n'
    hdfs('-put', '-', prefix + '/plain.csv', data=plain.encode())
    report = {'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'engine': 'Spark 3.5.6 native SQL',
              'hdfs': 'Apache Hadoop 3.4.1', 'root': FS + BOUND, 'fixtureDirectory': prefix, 'cases': []}

    def execute(name, destination, source=prefix + '/input.csv', roots=FS + BOUND):
        canonical = SQL / (name + '.sql')
        if not canonical.exists():
            raise RuntimeError('请先运行 EtlNativeHdfsCompilerTest 生成编译 SQL')
        text = canonical.read_text().replace(BOUND + '/native_hdfs_input.csv', source).replace(BOUND + '/native_hdfs_output', destination)
        local = Path('/tmp') / ('datahub-hdfs-' + uuid.uuid4().hex + '.sql'); local.write_text(text)
        remote = '/tmp/' + local.name
        run(['docker', 'cp', str(local), options.worker + ':' + remote])
        result = run(['docker', 'exec', '-u', '10001', '-e', 'JAVA_HOME=/opt/java17', '-e', 'DATAHUB_ETL_HDFS_ROOTS=' + roots,
                      options.worker, '/opt/spark/bin/spark-sql-managed-original', '--master', 'local[1]', '--driver-memory', '512m',
                      '--conf', 'spark.sql.catalogImplementation=in-memory', '--conf', 'spark.sql.parser.escapedStringLiterals=false', '-f', remote], check=False)
        return result, hashlib.sha256(text.encode()).hexdigest()

    for name, encoding in [('csv-gzip', 'UTF-8'), ('csv-gbk', 'GBK'), ('text-bzip2', 'UTF-8')]:
        destination = prefix + '/' + name
        hdfs('-mkdir', '-p', destination)
        hdfs('-chown', 'datahub', destination)
        hdfs('-put', '-', destination + '/keep.txt', data=b'KEEP-BEFORE-WRITE\n')
        result, digest = execute(name, destination, source=prefix + ('/plain.csv' if name.startswith('text') else '/input.csv'))
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace')[-6000:] + result.stdout.decode(errors='replace')[-6000:])
        listing = hdfs('-ls', destination).stdout.decode()
        files = [line.split()[-1] for line in listing.splitlines() if 'measurements-' in line]
        assert len(files) == 1, listing
        payload = hdfs('-cat', files[0]).stdout
        if name.endswith('gzip'):
            payload = gzip.decompress(payload)
        elif name.endswith('bzip2'):
            payload = bz2.decompress(payload)
        actual = payload.decode(encoding)
        if name.startswith('csv'):
            expected = '9007199254740993,12345678901234567890.1234,2026-11-02,"河道,a ""B""\nline","",\\N\n'
        else:
            expected = '9007199254740993|12345678901234567890.1234|2026-11-02|河道plain|EMPTY|\\N\n'
        assert actual == expected, repr(actual)
        assert hdfs('-cat', destination + '/keep.txt').stdout == b'KEEP-BEFORE-WRITE\n'
        report['cases'].append({'name': name, 'exitCode': result.returncode, 'sqlSha256': digest,
                                'file': files[0], 'decodedTextSha256': hashlib.sha256(actual.encode()).hexdigest(),
                                'exactLargeIntegerAndDecimal': True, 'date': '2026-11-02', 'existingFileUnchanged': True,
                                'fileNamePrefixApplied': True, 'csvEmptyStringAndNullDiffer': name.startswith('csv')})
    hdfs('-put', '-', prefix + '/bad.csv', data=b'id,amount,day,label,empty,optional\nnot-an-integer,1.0000,2026-11-02,bad,"",\n')
    for name, source, roots in [('bad-type', prefix + '/bad.csv', FS + BOUND), ('ungranted-root', prefix + '/input.csv', '')]:
        result, digest = execute('csv-gzip', prefix + '/' + name, source=source, roots=roots)
        assert result.returncode != 0, result.stdout.decode(errors='replace')[-3000:]
        error = (result.stderr + result.stdout).decode(errors='replace')
        assert 'HDFS' in error or 'MALFORMED' in error or 'NumberFormatException' in error, error[-3000:]
        report['cases'].append({'name': name, 'exitCode': result.returncode, 'rejected': True, 'sqlSha256': digest})
    result, digest = execute('text-bzip2', prefix + '/ambiguous-text', source=prefix + '/input.csv')
    assert result.returncode != 0
    assert '不能无损' in (result.stderr + result.stdout).decode(errors='replace')
    report['cases'].append({'name': 'ambiguous-text', 'exitCode': result.returncode, 'rejected': True, 'sqlSha256': digest})
    report['limits'] = ['原生引擎与真实HDFS端到端，不声明本次管理端触发验收',
                        '只创建新数据文件；文件名包含前缀、作业UUID与分片编号；不提供跨作业恰好一次或提交中断全局回滚',
                        '无Kerberos受管测试环境；snappy/lzo需要独立确认的原生编解码器']
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
