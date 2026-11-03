from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_dbswitch_architecture import inspect, OPERATIONS


class DbSwitchArchitectureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.component = self.root / "yudao-framework/yudao-dbswitch"
        self.product = self.component / "yudao-dbswitch-product-fixture"
        self.provider = "example.FixtureProvider"
        self.pom("yudao-dbswitch-common")
        self.pom("yudao-dbswitch-core", ["yudao-dbswitch-common"])
        self.pom(self.product.name, ["yudao-dbswitch-core"])
        self.write(self.product / "src/main/java/example/FixtureProvider.java", "package example; class FixtureProvider {}")
        self.service = self.product / "src/main/resources/META-INF/services/cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductProvider"
        self.write(self.service, self.provider)
        self.manifest = self.product / "src/main/resources/META-INF/dbswitch/products/fixture.json"
        self.data = {"product": "FIXTURE", "provider": self.provider, "cursorTargets": [],
                     "operations": {name: {"status": "UNSUPPORTED"} for name in OPERATIONS}}
        self.write(self.manifest, json.dumps(self.data))

    def write(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def pom(self, name, dependencies=()):
        self.write(self.component / name / "pom.xml",
                   '<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>' + name +
                   '</artifactId><dependencies>' + ''.join('<dependency><artifactId>' + dep +
                   '</artifactId></dependency>' for dep in dependencies) + '</dependencies></project>')

    def test_valid_graph_ignores_compiler_copies(self):
        self.write(self.product / "target/classes/META-INF/services" / self.service.name, self.provider)
        self.assertEqual([], inspect(self.root))

    def test_core_cannot_depend_on_product_or_cycle(self):
        self.pom("yudao-dbswitch-core", [self.product.name])
        findings = inspect(self.root)
        self.assertTrue(any("invalid inward" in finding for finding in findings))
        self.assertTrue(any("dependency cycle" in finding for finding in findings))

    def test_framework_cannot_depend_on_business(self):
        self.pom(self.product.name, ["yudao-module-dataforge-server"])
        self.assertTrue(any("forbidden dependency" in finding for finding in inspect(self.root)))

    def test_incomplete_or_unproved_matrix_is_rejected(self):
        self.data["operations"].pop("SYNC_FULL")
        self.data["operations"]["READ"] = {"status": "VERIFIED"}
        self.write(self.manifest, json.dumps(self.data))
        findings = inspect(self.root)
        self.assertTrue(any("Incomplete operation matrix" in finding for finding in findings))
        self.assertTrue(any("Verification without evidence" in finding for finding in findings))

    def test_missing_provider_source_and_manifest_are_rejected(self):
        self.write(self.service, "example.MissingProvider")
        findings = inspect(self.root)
        self.assertTrue(any("no source" in finding for finding in findings))
        self.assertTrue(any("providers differ" in finding for finding in findings))

    def cursor_direction(self, targets):
        self.data["cursorTargets"] = targets
        self.data["operations"]["SYNC_CURSOR"]["status"] = "DECLARED"
        declaration = ", ".join("ProductTypeEnum." + target for target in targets)
        self.write(self.product / "src/main/java/example/FixtureProvider.java",
                   "class FixtureProvider { Object source = builder.cursorTargets(" + declaration + "); }")
        self.write(self.manifest, json.dumps(self.data))

    def test_cursor_direction_requires_an_installed_declared_upsert_target(self):
        self.cursor_direction(["FIXTURE"])
        self.assertTrue(any("target UPSERT" in finding for finding in inspect(self.root)))
        self.data["operations"]["WRITE_UPSERT"]["status"] = "UNVERIFIED"
        self.write(self.manifest, json.dumps(self.data))
        self.assertTrue(any("target UPSERT" in finding for finding in inspect(self.root)))
        self.data["operations"]["WRITE_UPSERT"]["status"] = "DECLARED"
        self.write(self.manifest, json.dumps(self.data))
        self.assertEqual([], inspect(self.root))

    def test_cursor_direction_cannot_point_to_an_unknown_target(self):
        self.cursor_direction(["MISSING"])
        self.assertTrue(any("target UPSERT" in finding for finding in inspect(self.root)))

    def test_duplicate_and_provider_manifest_drift_are_rejected(self):
        self.cursor_direction(["FIXTURE", "FIXTURE"])
        self.assertTrue(any("Duplicate cursor direction" in finding for finding in inspect(self.root)))
        self.cursor_direction(["FIXTURE"])
        self.write(self.product / "src/main/java/example/FixtureProvider.java", "class FixtureProvider {}")
        self.assertTrue(any("cursor directions differ" in finding for finding in inspect(self.root)))

    def test_missing_direction_list_and_source_implementation_are_rejected(self):
        self.data.pop("cursorTargets")
        self.write(self.manifest, json.dumps(self.data))
        self.assertTrue(any("direction list" in finding for finding in inspect(self.root)))
        self.cursor_direction(["FIXTURE"])
        self.data["operations"]["SYNC_CURSOR"]["status"] = "UNSUPPORTED"
        self.write(self.manifest, json.dumps(self.data))
        self.assertTrue(any("source implementation" in finding for finding in inspect(self.root)))

    def test_business_metadata_must_use_context_runtime_but_test_fixtures_may_construct_it(self):
        source = self.root / "yudao-module-fixture/server/src/main/java/example/Reader.java"
        self.write(source, "class Reader { Object reader = new MetaDataByJdbcServiceImpl(product); }")
        self.assertTrue(any("context-owned" in finding for finding in inspect(self.root)))
        self.write(source, "class Reader { Object reader = runtime.jdbcMetadata(product, profile); }")
        self.write(self.root / "yudao-module-fixture/server/src/test/java/example/ReaderTest.java",
                   "class ReaderTest { Object reader = new MetaDataByJdbcServiceImpl(product); }")
        self.assertEqual([], inspect(self.root))

    def test_verified_requires_actual_matching_successful_non_skipped_report(self):
        self.data.update(serverVersion="16.fixture", driverBundleFingerprint="a" * 64)
        report = "evidence/TEST-example.ReadTest.xml"
        self.data["operations"]["READ"] = {"status": "VERIFIED", "evidence": {
            "testClass": "example.ReadTest", "report": report, "executed": 2, "skipped": 0}}
        self.write(self.manifest, json.dumps(self.data))
        self.assertTrue(any("report missing" in finding for finding in inspect(self.root)))
        self.write(self.root / report, '<testsuite name="example.ReadTest" tests="2" failures="0" errors="0" skipped="1"/>')
        self.assertTrue(any("non-skipped" in finding for finding in inspect(self.root)))
        self.write(self.root / report, '<testsuite name="example.ReadTest" tests="2" failures="0" errors="0" skipped="0"/>')
        self.assertEqual([], inspect(self.root))


if __name__ == "__main__":
    unittest.main()
