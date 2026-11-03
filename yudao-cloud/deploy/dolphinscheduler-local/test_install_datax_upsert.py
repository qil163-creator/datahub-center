"""停滞实例维护入口必须拒绝执行中任务及存活引擎；不连接真实服务。"""
import unittest
from unittest.mock import patch

import install_datax_upsert as installer


class Client:
    def __init__(self, workflow="RUNNING_EXECUTION", tasks=None):
        self.workflow = workflow
        self.tasks = [{"state": "SUBMITTED_SUCCESS"}] if tasks is None else tasks

    def request(self, method, path, params=None):
        if path == "/projects/list":
            return [{"code": 1}]
        if path.endswith("/workflow-instances"):
            return {"totalList": [{"id": 4, "state": self.workflow}]}
        if path.endswith("/4/tasks"):
            return {"taskList": self.tasks}
        raise AssertionError(path)


class MaintenanceGuardTest(unittest.TestCase):
    def test_default_rejects_nonterminal(self):
        with self.assertRaises(ValueError):
            installer.check_instances(Client())

    def test_explicit_recovery_preserves_only_submitted(self):
        with patch.object(installer, "run", return_value="0\n"):
            self.assertEqual([4], installer.check_instances(Client(), True))

    def test_recovery_rejects_running_task(self):
        with self.assertRaises(ValueError):
            installer.check_instances(Client(tasks=[{"state": "RUNNING_EXECUTION"}]), True)

    def test_recovery_rejects_unknown_empty_task_list(self):
        with self.assertRaises(ValueError):
            installer.check_instances(Client(tasks=[]), True)

    def test_recovery_rejects_live_engine(self):
        with patch.object(installer, "run", return_value="1\n"), self.assertRaises(ValueError):
            installer.check_instances(Client(), True)

    def test_kill_is_terminal(self):
        self.assertEqual([], installer.check_instances(Client(workflow="KILL")))

    def test_serial_wait_with_no_task_preserves_native_queue(self):
        with patch.object(installer, "run", return_value="0\n"):
            self.assertEqual([4], installer.check_instances(Client(workflow="SERIAL_WAIT", tasks=[]), True))

    def test_serial_wait_with_previous_success_preserves_native_queue(self):
        with patch.object(installer, "run", return_value="0\n"):
            self.assertEqual([4], installer.check_instances(Client(workflow="SERIAL_WAIT", tasks=[{"state": "SUCCESS"}]), True))

    def test_serial_wait_rejects_running_task(self):
        with self.assertRaises(ValueError):
            installer.check_instances(Client(workflow="SERIAL_WAIT", tasks=[{"state": "RUNNING_EXECUTION"}]), True)

    def test_fresh_submitted_workflow_without_tasks_is_not_running(self):
        with patch.object(installer, "run", return_value="0\n"):
            self.assertEqual([4], installer.check_instances(Client(workflow="SUBMITTED_SUCCESS", tasks=[]), True))


if __name__ == "__main__":
    unittest.main()
