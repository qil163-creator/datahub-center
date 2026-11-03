import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile

root = Path(__file__).resolve().parent
binary = Path('D:/Environment/PostgreSQL/18/bin')
environment = dict(os.environ)
for key in ('PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER', 'PGPASSWORD', 'PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS'):
    environment.pop(key, None)
environment['JAVA_HOME'] = 'D:/Environment/JDK/corretto-25.0.3'
environment['PATH'] = environment['JAVA_HOME'] + '/bin;' + environment['PATH']
environment['PGCLIENTENCODING'] = 'UTF8'
flags = subprocess.CREATE_NO_WINDOW

with tempfile.TemporaryDirectory(prefix='modeling-naming-removal-') as work:
    directory = Path(work)
    assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()
    data = directory / 'data'

    def run(name, *arguments):
        output = directory / (name + '.log')
        with output.open('w', encoding='utf-8') as log:
            result = subprocess.run([str(binary / (name + '.exe')), *map(str, arguments)],
                                    env=environment, stdout=log, stderr=subprocess.STDOUT,
                                    creationflags=flags, timeout=60)
        if result.returncode:
            raise AssertionError(output.read_text(encoding='utf-8', errors='replace'))

    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    run('initdb', '-D', data, '-U', 'modeling_test', '-A', 'trust', '--no-locale', '-E', 'UTF8')
    started = False
    try:
        run('pg_ctl', '-D', data, '-l', directory / 'postgres.log', '-o', f'-h 127.0.0.1 -p {port}', '-w', 'start')
        started = True
        run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'modeling_test', '-d', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE modeling_naming_removal_test')
        url = f'jdbc:postgresql://127.0.0.1:{port}/modeling_naming_removal_test'
        environment['MODELING_TEST_PG_URL'] = url
        command = [shutil.which('mvn', path=environment['PATH']), '-pl', ':yudao-module-modeling-server', '-am', 'test',
                   '-Dtest=ModelingRepairPostgreSqlTest,ModelMaterializedDatabaseTest',
                   '-Dsurefire.failIfNoSpecifiedTests=false', '-Ddatahub.test.postgres.url=' + url,
                   '-Ddatahub.test.postgres.user=modeling_test']
        print('Running two focused tests on an isolated PostgreSQL 18 database.', flush=True)
        log_path = root / '.backend-removal-pg-test.log'
        with log_path.open('w', encoding='utf-8') as log:
            result = subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                    creationflags=flags, timeout=1200)
        print(log_path.read_text(encoding='utf-8', errors='replace')[-14000:], flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)
    finally:
        if started:
            run('pg_ctl', '-D', data, '-m', 'fast', '-w', 'stop')
