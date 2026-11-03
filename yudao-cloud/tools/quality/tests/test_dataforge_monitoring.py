import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("q7_monitoring", ROOT / "tools/quality/verify_dataforge_monitoring.py")
MONITORING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITORING)


class DataForgeMonitoringTest(unittest.TestCase):
    def test_transport_copy_preserves_all_expressions_labels_and_thresholds(self):
        original = (ROOT / "script/monitoring/dataforge-run.rules.yml").read_text(encoding="utf-8")
        actual = MONITORING.transport_rules(original)
        without_timing = lambda text: re.sub(r"^\s+(?:for|interval):.*\n", "", text, flags=re.M)
        self.assertEqual(without_timing(original), without_timing(actual))
        self.assertEqual(11, actual.count("        for: 2s"))
        self.assertEqual(1, actual.count("    interval: 1s"))

    def test_rule_set_drift_requires_transport_scenario_review(self):
        with self.assertRaises(ValueError):
            MONITORING.transport_rules("groups: []")

    def test_same_count_renamed_alert_requires_scenario_review(self):
        original = (ROOT / "script/monitoring/dataforge-run.rules.yml").read_text(encoding="utf-8")
        with self.assertRaises(ValueError):
            MONITORING.transport_rules(original.replace("- alert: DataForgeRunScanFailures",
                                                        "- alert: UncoveredNewAlert"))

    def test_every_production_alert_has_a_delivery_scenario(self):
        original = (ROOT / "script/monitoring/dataforge-run.rules.yml").read_text(encoding="utf-8")
        expected = set(re.findall(r"^      - alert: (\w+)$", original, re.M))
        actual = set().union(*MONITORING.SCENARIOS.values())
        self.assertEqual(expected, actual)

    def test_scan_counter_is_not_reset_when_fault_recovers(self):
        for node_b in (False, True):
            for phase in ("scan", "healthy"):
                lines = MONITORING.metric_lines(phase, node_b, 1)
                for domain in ("ETL", "ACCESS"):
                    self.assertIn(f'dataforge_run_scan_failures_total{{domain="{domain}",phase="DISPATCH"}} 1', lines)

    def test_runtime_images_are_pinned_not_floating_tags(self):
        for image in (MONITORING.PROMETHEUS, MONITORING.ALERTMANAGER, MONITORING.PYTHON):
            self.assertRegex(image, r"@sha256:[a-f0-9]{64}$")


if __name__ == "__main__":
    unittest.main()
