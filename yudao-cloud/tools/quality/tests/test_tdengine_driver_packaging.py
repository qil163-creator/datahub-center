import hashlib
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import prepare_tdengine_driver as packaging


class TdengineDriverPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="q11-driver-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.source = pathlib.Path(self.temp.name) / "vendor.jar"
        self.output = pathlib.Path(self.temp.name) / "standalone.jar"
        self.files = {"META-INF/INDEX.LIST": packaging.EXPECTED_INDEX.encode(),
                      "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
                      "META-INF/LICENSE": b"fixture license",
                      "com/taosdata/jdbc/rs/RestfulDriver.class": b"driver",
                      "io/netty/bootstrap/Bootstrap.class": b"netty",
                      "org/apache/http/impl/client/HttpClientBuilder.class": b"http",
                      "com/fasterxml/jackson/databind/ObjectMapper.class": b"jackson"}

    def fixture(self):
        with zipfile.ZipFile(self.source, "w") as jar:
            for name, data in self.files.items():
                jar.writestr(name, data)
        return patch.object(packaging, "SOURCE_SHA256", hashlib.sha256(self.source.read_bytes()).hexdigest())

    def test_only_obsolete_index_is_removed_original_and_resources_are_preserved(self):
        with self.fixture():
            original = self.source.read_bytes()
            digest = packaging.prepare(self.source, self.output)
            self.assertEqual(original, self.source.read_bytes())
            self.assertEqual(hashlib.sha256(self.output.read_bytes()).hexdigest(), digest)
            with zipfile.ZipFile(self.output) as result:
                self.assertEqual(set(self.files) - {"META-INF/INDEX.LIST"}, set(result.namelist()))
                for name in result.namelist():
                    self.assertEqual(self.files[name], result.read(name))

    def test_unknown_source_hash_is_rejected_before_output_is_created(self):
        self.fixture()
        with self.assertRaises(ValueError):
            packaging.prepare(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_existing_source_or_output_cannot_be_overwritten(self):
        with self.fixture():
            with self.assertRaises(ValueError):
                packaging.prepare(self.source, self.source)
            self.output.write_bytes(b"keep")
            with self.assertRaises(ValueError):
                packaging.prepare(self.source, self.output)
            self.assertEqual(b"keep", self.output.read_bytes())

    def test_changed_index_manifest_or_signed_jar_is_rejected(self):
        for name, data in (("META-INF/INDEX.LIST", b"other index"),
                           ("META-INF/MANIFEST.MF", b"Class-Path: external.jar\n"),
                           ("META-INF/signature.SF", b"signature")):
            original = dict(self.files)
            self.files[name] = data
            with self.subTest(name=name), self.fixture():
                with self.assertRaises(ValueError):
                    packaging.prepare(self.source, self.output)
                self.assertFalse(self.output.exists())
            self.files = original


if __name__ == "__main__":
    unittest.main()
