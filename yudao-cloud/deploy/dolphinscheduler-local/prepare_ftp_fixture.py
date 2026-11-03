#!/usr/bin/env python3
"""安装已固定校验和的官方PyPI FTP测试服务到现有Worker，仅绑定回环地址时由smoke脚本启动。"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request
import zipfile


ARTIFACTS = [
    ('pyftpdlib', '2.2.0', 'sdist', '4ba0642078792df63dd3b2e9c8f838f2a3ecf428c7518d5921c0530d53512acf'),
    ('pyasynchat', '1.0.5', 'bdist_wheel', '35b7859515693e479e8d95ebe9f32cbf4d6312ab7599ced39fc24699e51de46f'),
    ('pyasyncore', '1.0.5', 'bdist_wheel', '269bbc5252671827387636822841a1fb721ec6e858b23a3e12cf92eb1f97da2a'),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='datahub-ds-local-standalone-1')
    options = parser.parse_args()
    directory = Path(__file__).resolve().parent / 'runtime/ftp-fixture-v2'
    directory.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, version, kind, expected in ARTIFACTS:
        metadata = json.load(urllib.request.urlopen('https://pypi.org/pypi/' + name + '/' + version + '/json', timeout=30))
        artifact = next(entry for entry in metadata['urls'] if entry['packagetype'] == kind and entry['digests']['sha256'] == expected)
        content = urllib.request.urlopen(artifact['url'], timeout=30).read()
        if hashlib.sha256(content).hexdigest() != expected: raise RuntimeError('FTP测试依赖校验和不匹配')
        manifest.append({'name': name, 'version': version, 'url': artifact['url'], 'sha256': expected})
        if kind == 'sdist':
            with tarfile.open(fileobj=io.BytesIO(content), mode='r:gz') as archive:
                for member in archive.getmembers():
                    parts = Path(member.name).parts
                    if not member.isfile() or len(parts) < 3 or parts[1] != 'pyftpdlib' or '..' in parts: continue
                    output = directory.joinpath(*parts[1:]); output.parent.mkdir(parents=True, exist_ok=True); output.write_bytes(archive.extractfile(member).read())
        else:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                for item in archive.infolist():
                    path = Path(item.filename)
                    if item.is_dir() or path.is_absolute() or '..' in path.parts or path.suffix != '.py' or any(part.endswith('.dist-info') for part in path.parts): continue
                    output = directory / path; output.parent.mkdir(parents=True, exist_ok=True); output.write_bytes(archive.read(item))
    (directory / 'source-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    destination = '/opt/datahub-fixtures/ftp-pyftpdlib-2.2.0'
    subprocess.run(['docker', 'exec', options.container, 'mkdir', '-p', destination], check=True)
    subprocess.run(['docker', 'cp', str(directory) + '/.', options.container + ':' + destination], check=True)
    print(json.dumps({'directory': destination, 'dependencies': manifest}, ensure_ascii=False))


if __name__ == '__main__':
    main()
