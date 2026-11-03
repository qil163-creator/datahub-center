#!/usr/bin/env python3
"""以固定官方 Flink API 构建 Java17 可信 RPC launcher，不包含源码或认证材料。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def run(*arguments):
    result = subprocess.run(arguments, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout[-4000:] + result.stderr[-4000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='components-stable-ds')
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    module = root / 'yudao-framework/yudao-spring-boot-starter-biz-etl'
    common = root / 'yudao-framework/yudao-dbswitch/yudao-dbswitch-common/src/main/java/cn/iocoder/yudao/framework/dbswitch/common'
    names = ['EtlNativeScriptProgram', 'EtlNativeScriptValues', 'EtlNativeScriptState', 'EtlScriptBrokerClient', 'EtlScriptBrokerTransport',
             'EtlScriptBrokerProtocol', 'EtlScriptProtocol', 'EtlScriptTls', 'EtlScriptAuthenticatedHttp']
    sources = [module / ('src/main/java/cn/iocoder/yudao/framework/etl/core/script/' + name + '.java') for name in names]
    sources += [module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/executor/support/EtlManagedSecrets.java',
                module / 'src/main/java/cn/iocoder/yudao/framework/etl/core/http/EtlBoundedHttp.java', module / 'runtime/script-flink/EtlNativeScriptMain.java']
    sources += [common / (name + '.java') for name in ['util/BoundedRowCodec', 'util/JdbcTypesUtils', 'entity/JdbcArrayValue', 'entity/NativeCellValue', 'entity/MissingCellValue']]
    options.directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='datahub-native-script-flink-') as temporary:
        temporary = Path(temporary); api = temporary / 'api'; api.mkdir(); output = temporary / 'classes'; output.mkdir()
        paths = json.loads(run('docker', 'exec', options.container, 'python3', '-c', "import glob,json;print(json.dumps(glob.glob('/opt/flink/lib/flink-*.jar')))").splitlines()[-1])
        for path in paths:
            run('docker', 'cp', options.container + ':' + path, str(api / Path(path).name))
        run('javac', '--release', '17', '-encoding', 'UTF-8', '-cp', str(api / '*'), '-d', str(output), *map(str, sources))
        jar = temporary / 'launcher.jar'
        run('jar', '--create', '--date=2026-01-01T00:00:00Z', '--file', str(jar), '-C', str(output), '.')
        data = jar.read_bytes(); checksum = hashlib.sha256(data).hexdigest(); artifact = options.directory.resolve() / ('flink-launcher-' + checksum[:16] + '.jar')
        try:
            with artifact.open('xb') as stream:
                stream.write(data)
        except FileExistsError:
            if artifact.read_bytes() != data:
                raise RuntimeError('Flink launcher 不可变摘要冲突')
        artifact.chmod(0o444)
    manifest = {'javaRelease': 17, 'flinkVersion': '1.20.3', 'flinkMainClass': 'cn.iocoder.yudao.framework.etl.script.flink.EtlNativeScriptMain',
                'flinkLauncher': {'path': str(artifact), 'sha256': checksum}, 'sourceSha256': hashlib.sha256(b''.join(path.read_bytes() for path in sources)).hexdigest(),
                'serializedMaterial': '已发布程序和受管引用；不包含源码、原始认证令牌、私钥或SSLContext'}
    options.output.parent.mkdir(parents=True, exist_ok=True); options.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
