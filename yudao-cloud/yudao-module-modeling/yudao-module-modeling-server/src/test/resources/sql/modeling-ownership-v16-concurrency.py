"""Manual PostgreSQL concurrency regression for the isolated 0016 fixture only."""
import argparse
import os
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--psql', required=True)
    parser.add_argument('--port', required=True, type=int)
    parser.add_argument('--database', required=True)
    args = parser.parse_args()
    if not args.database.startswith('modeling_'):
        raise SystemExit('Only a disposable modeling_* database is accepted')
    command = [args.psql, '-X', '-h', '127.0.0.1', '-p', str(args.port),
               '-U', 'modeling_verify', '-d', args.database, '-v', 'ON_ERROR_STOP=1', '-At', '-c']

    def query(sql):
        return subprocess.run(command + [sql], capture_output=True, text=True, timeout=15)

    def success(sql):
        result = query(sql)
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    assert success('SELECT current_user') == 'modeling_verify'
    success('INSERT INTO "datahub-center".dh_modeling_data_domain(id,tenant_id) '
            'VALUES(90001,90001),(90002,90002)')

    def race(first_sql, second_sql, expected):
        env = dict(os.environ, PGAPPNAME='modeling-v16-race-holder')
        holder = subprocess.Popen(command + ['BEGIN; ' + first_sql + '; SELECT pg_sleep(4); COMMIT'],
                                  env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 8
            while success("SELECT count(*) FROM pg_stat_activity WHERE "
                          "application_name='modeling-v16-race-holder' AND wait_event='PgSleep'") != '1':
                if holder.poll() is not None or time.monotonic() > deadline:
                    raise AssertionError('First transaction did not reach the synchronization point')
                time.sleep(0.05)
            blocked = query(second_sql)
            _, error = holder.communicate(timeout=10)
            assert holder.returncode == 0, error
            assert blocked.returncode != 0, 'Concurrent write incorrectly succeeded'
            assert expected in blocked.stderr, blocked.stderr
        finally:
            if holder.poll() is None:
                holder.terminate()
                holder.communicate(timeout=5)

    race('INSERT INTO "datahub-center".dh_modeling_logic_model(id,tenant_id,data_domain_id) '
         'VALUES(90001,90001,90001)',
         'UPDATE "datahub-center".dh_modeling_data_domain SET deleted=1 WHERE id=90001',
         'Planning object is referenced')
    race('UPDATE "datahub-center".dh_modeling_data_domain SET deleted=1 WHERE id=90002',
         'INSERT INTO "datahub-center".dh_modeling_logic_model(id,tenant_id,data_domain_id) '
         'VALUES(90002,90002,90002)',
         'Model ownership reference missing')
    race('INSERT INTO "datahub-center".dh_modeling_model_check(id,tenant_id,task_status) '
         "VALUES(90001,90001,'QUEUED')",
         'INSERT INTO "datahub-center".dh_modeling_model_check(id,tenant_id,task_status) '
         "VALUES(90002,90001,'QUEUED')",
         'uk_modeling_check_active_tenant')
    print('3 concurrent PostgreSQL regressions passed: insert/delete, delete/insert, double enqueue')


if __name__ == '__main__':
    main()
