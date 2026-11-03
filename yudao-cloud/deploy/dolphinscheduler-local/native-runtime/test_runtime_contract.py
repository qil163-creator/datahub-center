#!/usr/bin/env python3
"""验证本地执行入口的标识绑定和参数拒绝，既有集成任务必须保留原入口。"""

import os
import json
from pathlib import Path
import pwd
import tempfile
import unittest

import local_runtime_cli as cli
import local_runtime_observer as observer


class RuntimeContractTest(unittest.TestCase):
    def test_existing_integration_still_uses_original_cli(self):
        self.assertIsNone(cli.execution_name('SPARK', ['--name', 'existing_integration']))

    def test_spark_identity_must_match_all_name_arguments(self):
        self.assertEqual('dh_native_123', cli.execution_name('SPARK', ['--name', 'dh_native_123', '--conf', 'spark.app.name=dh_native_123']))
        with self.assertRaises(ValueError):
            cli.execution_name('SPARK', ['--name', 'dh_native_123', '--conf', 'spark.app.name=another_job'])

    def test_flink_initialization_and_script_bind_one_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'init.sql').write_text("SET 'pipeline.name' = 'dh_native_123';\n")
            (root / 'task.sql').write_text("SET 'pipeline.name' = 'dh_native_123';\nSELECT 1;")
            arguments = ['-i', str(root / 'init.sql'), '-f', str(root / 'task.sql')]
            self.assertEqual('dh_native_123', cli.execution_name('FLINK', arguments))
            (root / 'task.sql').write_text("SET 'pipeline.name' = 'dh_native_456';")
            with self.assertRaises(ValueError):
                cli.execution_name('FLINK', arguments)

    def test_dolphinscheduler_spark_resource_configurations_are_bounded(self):
        for key, values in observer.SPARK_RESOURCE_VALUES.items():
            for value in values:
                observer.validate_spark_configuration(key + '=' + value, 'dh_native_123')
        for entry in ('spark.driver.cores=2', 'spark.executor.cores=2',
                      'spark.executor.instances=2', 'spark.driver.memory=2g',
                      'spark.executor.memory=0', 'spark.driver.memory=512m=2g'):
            with self.assertRaises(observer.RequestRejected) as rejection:
                observer.validate_spark_configuration(entry, 'dh_native_123')
            self.assertEqual('SPARK_RESOURCE_OUT_OF_BOUNDS', rejection.exception.code)
        for entry in ('spark.driver.extraJavaOptions=-javaagent:/tmp/x.jar',
                      'spark.jars=/tmp/custom.jar', 'spark.submit.deployMode=cluster',
                      'spark.app.name=another_job'):
            with self.assertRaises(observer.RequestRejected) as rejection:
                observer.validate_spark_configuration(entry, 'dh_native_123')
            self.assertEqual('SPARK_CONFIG_NOT_ALLOWED', rejection.exception.code)

    def test_launch_rejection_audit_does_not_create_execution_result(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = observer.Runtime(Path(directory))
            request = {'name': 'dh_native_123', 'engine': 'SPARK',
                       'cwd': '/tmp/dolphinscheduler', 'arguments': ['--conf', 'spark.driver.memory=2g']}
            runtime.audit_rejection(request, 10001,
                                    observer.RequestRejected('SPARK_RESOURCE_OUT_OF_BOUNDS', 'not exposed'))
            self.assertEqual([], runtime.query('dh_native_123'))
            path = Path(directory) / 'rejections/dh_native_123.jsonl'
            evidence = json.loads(path.read_text())
            self.assertEqual('LAUNCH_REQUEST_REJECTED', evidence['event'])
            self.assertEqual('SPARK_RESOURCE_OUT_OF_BOUNDS', evidence['reasonCode'])
            self.assertEqual(10001, evidence['peerUid'])
            self.assertFalse(evidence['started'])
            self.assertFalse(evidence['accepted'])
            self.assertNotIn('exitCode', evidence)
            self.assertNotIn('arguments', evidence)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    @unittest.skipUnless(os.geteuid() == 0 and Path('/tmp/dolphinscheduler').exists(), '参数所有权测试在实际 Linux Worker 执行')
    def test_unapproved_jar_and_nonlocal_master_are_rejected(self):
        account = pwd.getpwnam('datahub')
        with tempfile.TemporaryDirectory(dir='/tmp/dolphinscheduler') as directory:
            root = Path(directory)
            script = root / 'task.sql'
            script.write_text('SELECT 1;')
            os.chown(script, account.pw_uid, account.pw_gid)
            request = {'name': 'dh_native_123', 'engine': 'SPARK', 'cwd': directory,
                       'arguments': ['--master', 'local[1]', '--name', 'dh_native_123', '-f', str(script)]}
            self.assertEqual(1, len(observer.validate_request(request)))
            # Exact Spark SQL format emitted by DolphinScheduler 3.4.2.
            ds_arguments = ['--master', 'local[1]', '--conf', 'spark.driver.cores=1',
                            '--conf', 'spark.driver.memory=512m', '--conf', 'spark.executor.instances=1',
                            '--conf', 'spark.executor.cores=1', '--conf', 'spark.executor.memory=512m',
                            '--name', 'dh_native_123', '-f', str(script)]
            self.assertEqual(1, len(observer.validate_request({**request, 'arguments': ds_arguments})))
            for additional in (['--jars', '/tmp/custom.jar'], ['--master', 'yarn'], ['--conf', 'spark.driver.extraJavaOptions=-javaagent:/tmp/x.jar']):
                with self.assertRaises(ValueError):
                    observer.validate_request({**request, 'arguments': request['arguments'] + additional})
            os.chown(script, 0, 0)
            with self.assertRaises(ValueError):
                observer.validate_request(request)


if __name__ == '__main__':
    unittest.main()
