#!/usr/bin/env python3
"""Exercise real Git three-way planning without changing the active source or pin."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "baseline", Path(__file__).resolve().parents[1] / "tools/yuedizhibo_baseline.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        baseline.git(self.root, "init", "-q")
        self.path = baseline.PREFIX + "rgi_native/src/example.c"
        self.file = self.root / self.path
        self.file.parent.mkdir(parents=True)
        self.base = b"".join(b"int value%d = 0;\n" % i for i in range(12))
        self.file.write_bytes(self.base)
        self.old = self.commit()
        self.lock = {"commit": self.old, "files": [{
            "upstream": self.path, "local": self.path,
            "sha256": baseline.digest(self.base), "reason": "fixture local extension",
        }]}

    def commit(self):
        baseline.git(self.root, "add", "-A")
        baseline.git(self.root, "-c", "user.name=Fixture",
                     "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")
        return baseline.git(self.root, "rev-parse", "HEAD").decode().strip()

    def update(self, upstream, ours):
        self.file.write_bytes(upstream)
        revision = self.commit()
        self.file.write_bytes(ours)
        return baseline.plan(self.root, self.lock, revision, self.root / "build/plan")

    def test_disjoint_update_keeps_local_addition_and_active_pin(self):
        ours = self.base.replace(b"value0 = 0", b"value0 = 1")
        report = self.update(self.base.replace(b"value11 = 0", b"value11 = 2"), ours)
        merged = (self.root / "build/plan" / self.path).read_bytes()
        self.assertFalse(report["review"])
        self.assertIn(b"value0 = 1", merged)
        self.assertIn(b"value11 = 2", merged)
        self.assertEqual(self.file.read_bytes(), ours)
        self.assertEqual(self.lock["commit"], self.old)
        self.assertFalse(report["applied"])

    def test_overlapping_change_requires_review(self):
        ours = self.base.replace(b"value0 = 0", b"value0 = 1")
        report = self.update(self.base.replace(b"value0 = 0", b"value0 = 2"), ours)
        self.assertEqual(report["review"][0]["reason"], "three-way conflict")
        self.assertIn(b"||||||| baseline", (self.root / "build/plan" / self.path).read_bytes())
        self.assertEqual(self.file.read_bytes(), ours)

    def test_new_file_and_changed_replacement_are_not_silently_adopted(self):
        self.lock["files"][0]["local"] = None
        self.lock["files"][0]["replacements"] = ["adapter.c"]
        (self.root / "adapter.c").write_text("local ABI adapter")
        (self.file.parent / "new.c").write_text("int new_upstream_feature;\n")
        report = self.update(self.base.replace(b"value0 = 0", b"value0 = 2"), self.base)
        self.assertEqual(len(report["review"]), 2)
        self.assertEqual(report["writes"], [])
        self.assertFalse((self.root / "build/plan" / self.path).exists())

    def test_upstream_deletion_keeps_modified_source_for_review(self):
        self.file.unlink()
        revision = self.commit()
        ours = self.base + b"int local_extension;\n"
        self.file.write_bytes(ours)
        report = baseline.plan(self.root, self.lock, revision, self.root / "build/plan")
        self.assertEqual(report["removals"], [])
        self.assertEqual(report["review"][0]["reason"], "upstream removed a locally modified file")
        self.assertEqual(self.file.read_bytes(), ours)

    def test_stale_pin_inventory_and_unsafe_output_fail_before_writes(self):
        self.lock["files"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            baseline.check(self.root, self.lock)
        self.lock["files"][0]["sha256"] = baseline.digest(self.base)
        with self.assertRaisesRegex(ValueError, "new directory below build"):
            baseline.plan(self.root, self.lock, self.old, self.root / "overwrite-source")
        self.lock["files"] = []
        with self.assertRaisesRegex(ValueError, "Unaccounted"):
            baseline.check(self.root, self.lock)


if __name__ == "__main__":
    unittest.main()
