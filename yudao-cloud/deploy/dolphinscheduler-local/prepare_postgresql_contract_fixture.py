#!/usr/bin/env python3
"""创建仅本任务使用的PG16契约库，以客户端证书适配硬编码空密码的旧测试，不改测试源码。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, default=Path('/tmp/datahub-postgresql-contract-fixture.json')); options = parser.parse_args()
    directory = Path('/private/tmp/datahub-pg-contract-' + uuid.uuid4().hex[:12]); directory.mkdir(mode=0o700)
    private_values = []

    def run(arguments, data=None, check=True):
        result = subprocess.run(arguments, input=data, capture_output=True)
        if check and result.returncode:
            message = (result.stderr + result.stdout).decode(errors='replace')[-6000:]
            for value in private_values: message = message.replace(value, '[fixture-secret]')
            raise RuntimeError(message)
        return result.stdout.decode().strip()

    def private(path, content):
        path.write_text(content); path.chmod(0o600); return path

    admin = secrets.token_hex(32); role_password = secrets.token_hex(32); private_values += [admin, role_password]
    environment = private(directory / 'postgres.env', 'POSTGRES_USER=postgres\nPOSTGRES_DB=components_test\nPOSTGRES_PASSWORD=' + admin + '\n')
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '2', '-subj', '/CN=DataHubContractFixtureCA', '-keyout', str(directory / 'ca.key'), '-out', str(directory / 'ca.crt')])
    for name, subject, usage in [('server', 'localhost', 'serverAuth'), ('client', 'dataforge_test', 'clientAuth')]:
        run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=' + subject, '-keyout', str(directory / (name + '.key')), '-out', str(directory / (name + '.csr'))])
        extension = private(directory / (name + '.ext'), 'keyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=' + usage + '\n' + ('subjectAltName=IP:127.0.0.1,DNS:localhost\n' if name == 'server' else ''))
        run(['openssl', 'x509', '-req', '-in', str(directory / (name + '.csr')), '-CA', str(directory / 'ca.crt'), '-CAkey', str(directory / 'ca.key'), '-CAcreateserial', '-days', '2', '-sha256', '-extfile', str(extension), '-out', str(directory / (name + '.crt'))])
    run(['openssl', 'pkcs8', '-topk8', '-inform', 'PEM', '-outform', 'DER', '-in', str(directory / 'client.key'), '-nocrypt', '-out', str(directory / 'client.pk8')])
    for path in directory.iterdir(): path.chmod(0o600)
    name = 'datahub-components-contract-postgres'; volume = 'datahub-components-contract-pg-' + directory.name.rsplit('-', 1)[-1]
    run(['docker', 'rm', '-f', name], check=False)
    run(['docker', 'run', '-d', '--name', name, '--memory', '128m', '--env-file', str(environment), '-p', '127.0.0.1::5432', '-v', volume + ':/var/lib/postgresql/data',
         'postgres:16-alpine', '-c', 'shared_buffers=16MB', '-c', 'max_connections=20', '-c', 'work_mem=1MB', '-c', 'maintenance_work_mem=8MB'])

    def wait_ready():
        for attempt in range(60):
            if subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'postgres', '-d', 'components_test'], capture_output=True).returncode == 0: return
            time.sleep(0.25)
        raise RuntimeError('隔离PostgreSQL未就绪')

    wait_ready()
    run(['docker', 'exec', '-u', 'root', name, 'mkdir', '-p', '/var/lib/postgresql/cert'])
    for filename in ['server.key', 'server.crt', 'ca.crt']:
        run(['docker', 'cp', str(directory / filename), name + ':/var/lib/postgresql/cert/' + filename])
    run(['docker', 'exec', '-u', 'root', name, 'sh', '-c', 'chown -R postgres:postgres /var/lib/postgresql/cert && chmod 700 /var/lib/postgresql/cert && chmod 600 /var/lib/postgresql/cert/*'])
    conf = "\nssl=on\nssl_cert_file='/var/lib/postgresql/cert/server.crt'\nssl_key_file='/var/lib/postgresql/cert/server.key'\nssl_ca_file='/var/lib/postgresql/cert/ca.crt'\n"
    run(['docker', 'exec', '-i', '-u', 'postgres', name, 'sh', '-c', 'cat >> "$PGDATA/postgresql.conf"'], data=conf.encode())
    hba = 'local all postgres trust\nlocal all all reject\nhostssl components_test dataforge_test 0.0.0.0/0 cert\nhost all all 0.0.0.0/0 reject\nhost all all ::/0 reject\n'
    run(['docker', 'exec', '-i', '-u', 'postgres', name, 'sh', '-c', 'cat > "$PGDATA/pg_hba.conf"'], data=hba.encode())
    run(['docker', 'restart', '-t', '1', name]); wait_ready()
    baseline = (ROOT / 'sql/postgresql/datahub-center.sql').read_text(); migration = (ROOT / 'sql/postgresql/migrations/0215_dataforge_integration-workbench.sql').read_text()
    pipeline = re.search(r'CREATE TABLE "datahub-center"\."dh_dataforge_etl_pipeline" \([\s\S]*?\n\)\s*;', baseline).group(0)
    workbench = migration[migration.index('CREATE SEQUENCE IF NOT EXISTS dh_dataforge_integration_category_seq'):migration.index('DO $comments$')]
    sql = "CREATE ROLE dataforge_test LOGIN PASSWORD '" + role_password + "';\nALTER DATABASE components_test OWNER TO dataforge_test;\nSET ROLE dataforge_test;\nCREATE SCHEMA \"datahub-center\" AUTHORIZATION dataforge_test;\nSET search_path TO \"datahub-center\",public;\nCREATE SEQUENCE dh_dataforge_etl_pipeline_seq;\n" + pipeline + '\nALTER TABLE dh_dataforge_etl_pipeline ADD PRIMARY KEY(id);\n' + workbench
    sql += (ROOT / 'sql/postgresql/migrations/0252_dataforge_integration-ds-runtime.sql').read_text()
    sql += "\nINSERT INTO dh_dataforge_integration_category(id,parent_id,name) VALUES(1,0,'隔离回归');\nINSERT INTO dh_dataforge_integration_task(id,mode,category_id,name,engine,flow_json) VALUES(1,'OFFLINE',1,'隔离JSONB与修订号回归','SPARK','{\"nodes\":[],\"edges\":[]}'::jsonb);\n"
    run(['docker', 'exec', '-i', '-u', 'postgres', name, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'components_test'], data=sql.encode())
    port = json.loads(run(['docker', 'inspect', name]))[0]['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
    url = 'jdbc:postgresql://127.0.0.1:' + port + '/components_test?sslmode=verify-full&sslrootcert=' + str(directory / 'ca.crt') + '&sslcert=' + str(directory / 'client.crt') + '&sslkey=' + str(directory / 'client.pk8')
    argfile = private(directory / 'surefire.args', '-Djava.io.tmpdir=/private/tmp/datahub-etl-test\n-Ddatahub.test.postgres.url=' + url + '\n-Ddatahub.test.postgres.user=dataforge_test\n')
    properties = private(directory / 'connection.json', json.dumps({'url': url, 'user': 'dataforge_test', 'password': ''}))
    result = {'container': name, 'volume': volume, 'argfile': str(argfile), 'connectionFile': str(properties), 'authentication': 'TLS_CLIENT_CERTIFICATE', 'database': 'components_test', 'hostBinding': '127.0.0.1', 'privateDirectory': str(directory),
              'baselineDdlSha256': hashlib.sha256(pipeline.encode()).hexdigest(), 'workbenchDdlSha256': hashlib.sha256(workbench.encode()).hexdigest(), 'serverImageId': run(['docker', 'inspect', '--format', '{{.Image}}', name])}
    options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n'); options.output.chmod(0o600)
    print('Ready isolated PostgreSQL 16 contract fixture; TLS client certificate only; private manifest mode 0600')


if __name__ == '__main__': main()
