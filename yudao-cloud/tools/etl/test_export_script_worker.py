"""Packaging boundary regressions; real release execution is validated separately."""
import importlib.util
import io
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("worker_export", pathlib.Path(__file__).with_name("export_script_worker.py"))
worker_export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker_export)


class ExportWorkerTest(unittest.TestCase):
    def release(self, path, *, omit=None, extra=None, external=None,
                manifest_name="META-INF/MANIFEST.MF", newline="\r\n", duplicate_manifest=False):
        with zipfile.ZipFile(path, "w") as release:
            for artifact, classes in worker_export.LIBRARIES.items():
                if artifact == omit:
                    continue
                payload = io.BytesIO()
                with zipfile.ZipFile(payload, "w") as library:
                    for name in classes:
                        library.writestr(name, b"fixture")
                    if artifact == external:
                        headers = ("Manifest-Version: 1.0", "") if duplicate_manifest else (
                            "Manifest-Version: 1.0", "Class-Path: secrets.jar", "")
                        library.writestr(manifest_name, newline.join(headers))
                        if duplicate_manifest:
                            library.writestr("meta-inf/manifest.mf", "Manifest-Version: 1.0\n")
                release.writestr(f"BOOT-INF/lib/{artifact}-1.0.jar", payload.getvalue())
                if artifact == extra:
                    release.writestr(f"BOOT-INF/lib/{artifact}-2.0.jar", payload.getvalue())
            release.writestr("BOOT-INF/lib/yudao-module-private-server-1.0.jar", b"excluded")
            release.writestr("BOOT-INF/classes/application.yaml", b"excluded")
            release.writestr("../../outside", b"excluded")

    def test_exports_only_fixed_dependencies_and_never_expands_application_or_zip_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            self.release(root / "release.jar")
            result = worker_export.export(root / "release.jar", root / "worker")
            self.assertEqual(9, len(result["artifacts"]))
            self.assertEqual(10, len(list((root / "worker").iterdir())))
            self.assertFalse((root / "outside").exists())
            self.assertTrue(all(pathlib.Path(path).is_file() for path in result["classpathEntries"]))
            with self.assertRaises(ValueError):
                worker_export.export(root / "release.jar", root / "worker")

    def test_rejects_missing_ambiguous_and_external_classpath_before_creating_output(self):
        for option in ({"omit": "asm"}, {"extra": "asm"}, {"external": "asm"}):
            with self.subTest(option=option), tempfile.TemporaryDirectory() as temp:
                root = pathlib.Path(temp)
                self.release(root / "release.jar", **option)
                with self.assertRaises(ValueError):
                    worker_export.export(root / "release.jar", root / "worker")
                self.assertFalse((root / "worker").exists())

    def test_rejects_manifest_classpath_for_all_jdk_newlines_and_case_variants(self):
        for newline in ("\r\n", "\n", "\r"):
            for manifest_name in ("META-INF/MANIFEST.MF", "meta-inf/manifest.mf"):
                with self.subTest(newline=newline, manifest_name=manifest_name), tempfile.TemporaryDirectory() as temp:
                    root = pathlib.Path(temp)
                    self.release(root / "release.jar", external="asm", newline=newline, manifest_name=manifest_name)
                    with self.assertRaises(ValueError):
                        worker_export.export(root / "release.jar", root / "worker")
                    self.assertFalse((root / "worker").exists())
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            self.release(root / "release.jar", external="asm", duplicate_manifest=True)
            with self.assertRaises(ValueError):
                worker_export.export(root / "release.jar", root / "worker")
            self.assertFalse((root / "worker").exists())


if __name__ == "__main__":
    unittest.main()
