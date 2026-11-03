#!/usr/bin/env python3
"""验证准备脚本的幂等边界、配置契约及秘密文件保护。"""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_dev_native_runtime as prepare


class FakeClient:
    def __init__(self):
        self.projects = []
        self.groups = ["datahub-integration"]
        self.calls = []

    def request(self, method, path, params=None):
        self.calls.append((method, path))
        if method == "GET":
            return {
                "/environment/query-environment-list": [{"code": 11}, {"code": 12}],
                "/tenants/list": [{"tenantCode": "datahub"}],
                "/projects/list": self.projects,
                "/worker-groups/all": self.groups,
                "/worker-groups/worker-address-list": ["127.0.0.1:1234"],
            }[path]
        if path == "/projects":
            value = {"name": params["projectName"], "code": 9007199254740993}
            self.projects.append(value)
            return value
        if path == "/worker-groups":
            self.groups.append(params["name"])
            return None
        raise AssertionError("准备过程不得修改其他 DS 对象或提交任务")


class PreparationTest(unittest.TestCase):
    def test_preparation_reuses_objects_and_never_starts_jobs(self):
        client = FakeClient()
        codes = {"FLINK": "11", "SPARK": "12"}
        first = prepare.prepare_objects(client, codes, "datahub")
        second = prepare.prepare_objects(client, codes, "datahub")
        self.assertEqual("9007199254740993", first)
        self.assertEqual(first, second)
        self.assertEqual([("POST", "/projects"), ("POST", "/worker-groups")],
                         [call for call in client.calls if call[0] == "POST"])

    def test_stale_environment_fails_before_any_mutation(self):
        client = FakeClient()
        with self.assertRaises(ValueError):
            prepare.prepare_objects(client, {"FLINK": "13", "SPARK": "12"}, "datahub")
        self.assertFalse(any(method == "POST" for method, _ in client.calls))

    def test_profile_contract_preserves_validated_runtime_identity(self):
        value = prepare.configuration({"server": "http://127.0.0.1:12345/dolphinscheduler", "token": "test-only-token"},
                                      "9007199254740993", {"FLINK": "11", "SPARK": "12"}, "datahub",
                                      "https://127.0.0.1:19101", {"clientCertificate": "/client.pem",
                                      "clientKey": "/client-key.pem", "caCertificate": "/ca.pem"})
        dev = value["dataforge"]["dev"]
        self.assertEqual("9007199254740993", dev["ds-project-code"])
        for profile, engine, version in zip(dev["native"]["profiles"], ["FLINK", "SPARK"], ["1.20.3", "3.5.6"]):
            self.assertEqual(engine.lower() + "-local-sql", profile["code"])
            self.assertEqual("local-sql-v1", profile["revision"])
            self.assertEqual("default", profile["queue"])
            self.assertEqual("LOCAL", profile["deploy-mode"])
            self.assertEqual(version, profile["engine-version"])
            self.assertEqual([engine + "_SQL"], profile["allowed-task-types"])
            self.assertEqual("dev-local-observer", profile["local-observer-ssl-bundle"])

    def test_private_input_and_output_permissions_and_symlink_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            source.write_text('{"token":"test-only-token"}')
            source.chmod(0o600)
            self.assertIn("token", prepare.read_json(source, private=True))
            source.chmod(0o644)
            with self.assertRaises(ValueError):
                prepare.read_json(source, private=True)
            output = Path(directory) / "application-native.yaml"
            prepare.write_private_configuration(output, {"token": "test-only-token"})
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            self.assertEqual({"token": "test-only-token"}, json.loads(output.read_text()))
            link = Path(directory) / "link.yaml"
            link.symlink_to(output)
            with self.assertRaises(ValueError):
                prepare.write_private_configuration(link, {})

    def test_configuration_and_errors_do_not_print_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory) / "ds-private.json"
            private.write_text(json.dumps({"server": "http://127.0.0.1:12345/dolphinscheduler",
                                          "token": "test-secret-must-not-print", "environmentCodes": {"FLINK": 11, "SPARK": 12}}))
            private.chmod(0o600)
            connection = Path(directory) / "connection.json"
            connection.write_text('{}')
            output = Path(directory) / "application-native.yaml"
            arguments = ["prepare", "--ds-private", str(private), "--tls-connection", str(connection), "--output", str(output)]
            material = {"clientCertificate": "/client.pem", "clientKey": "/key.pem", "caCertificate": "/ca.pem"}
            stdout = io.StringIO()
            with patch("sys.argv", arguments), patch.object(prepare, "tls_material", return_value=("https://127.0.0.1:19101", material)), patch.object(prepare, "Client", return_value=FakeClient()), contextlib.redirect_stdout(stdout):
                prepare.main()
            result = json.loads(stdout.getvalue())
            self.assertEqual({"configurationPath", "profileCodes"}, set(result))
            self.assertNotIn("test-secret-must-not-print", stdout.getvalue())
            stderr = io.StringIO()
            with patch("sys.argv", arguments), patch.object(prepare, "tls_material", side_effect=RuntimeError("test-secret-must-not-print")), contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
                prepare.main()
            self.assertNotIn("test-secret-must-not-print", stderr.getvalue())

    def test_nonlocal_or_authenticated_endpoint_is_rejected(self):
        for value in ("https://example.test:19101", "https://user:secret@localhost:19101", "http://localhost:19101", "https://localhost:19101/?token=secret"):
            with self.assertRaises(ValueError):
                prepare.local_endpoint(value, secure=True)


if __name__ == "__main__":
    unittest.main()
