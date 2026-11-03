#!/usr/bin/env python3
"""用独立 JVM 验证文件 H2 冷启动清理的真实持久性，不启动服务或监听端口。"""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--h2-jar', required=True, type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    jar = args.h2_jar.resolve(strict=True)
    source = here / 'test-fixtures/DatahubRegistryFileContract.java'
    script = (here / 'start-local.sh').read_text().split("<<'SQL'\n", 1)[1].split('\nSQL\n', 1)[0]
    if 'SHUTDOWN;' not in script:
        raise AssertionError('冷启动清理必须显式关闭文件数据库以保证落盘')
    with tempfile.TemporaryDirectory(prefix='datahub-registry-file-contract-') as directory:
        work = Path(directory)
        subprocess.run(['javac', '--release', '8', '-cp', str(jar), '-d', str(work), str(source)], check=True)
        java = ['java', '-cp', str(work) + ':' + str(jar)]
        url = 'jdbc:h2:file:' + str(work / 'metadata') + ';MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_LOWER=true'
        before = work / 'without-shutdown.sql'
        after = work / 'with-shutdown.sql'
        before.write_text(script.replace('SHUTDOWN;', ''))
        after.write_text(script)
        subprocess.run(java + ['DatahubRegistryFileContract', 'seed', url], check=True)
        subprocess.run(java + ['org.h2.tools.RunScript', '-url', url, '-user', 'sa', '-script', str(before)], check=True)
        subprocess.run(java + ['DatahubRegistryFileContract', 'expect-stale', url], check=True)
        subprocess.run(java + ['org.h2.tools.RunScript', '-url', url, '-user', 'sa', '-script', str(after)], check=True)
        subprocess.run(java + ['DatahubRegistryFileContract', 'verify', url], check=True)


if __name__ == '__main__':
    main()
