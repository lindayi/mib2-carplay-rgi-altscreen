"""Run the actual exporter with only its four fixed paths mapped to host fixtures."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
EXPORTER = ROOT / "altscreen/Toolbox/scripts/export_mcd_originals.sh"


class McdExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.card = self.root / "sd"
        (self.card / "Toolbox").mkdir(parents=True)
        self.original = {}
        text = EXPORTER.read_text()
        paths = {
            "SD": ("/net/mmx/fs/sda0", self.card),
            "BIN": ("/net/mmx/mnt/app/armle/usr/sbin/mcd", self.root / "mcd"),
            "CONF": ("/net/mmx/etc/mcd.conf", self.root / "active.conf"),
            "SYSTEM_CONF": ("/net/mmx/mnt/system/etc/mcd.conf", self.root / "system.conf"),
        }
        for name, (production, fixture) in paths.items():
            declaration = name + "=" + production + "\n"
            self.assertEqual(text.count(declaration), 1)
            text = text.replace(declaration, name + "='" + str(fixture) + "'\n")
            if name != "SD":
                fixture.write_bytes((name + "\n").encode())
                self.original[fixture] = fixture.read_bytes()
        self.script = self.root / "export.sh"
        self.script.write_text(text)
        self.commands = self.root / "commands"
        self.commands.mkdir()
        self.env = dict(os.environ, PATH=str(self.commands) + os.pathsep + os.environ["PATH"])
        self.stub("mount", "echo 'Unexpected remount' >&2; exit 1")
        self.stub("sync", "exit 0")

    def stub(self, name, body):
        path = self.commands / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def run_export(self):
        return subprocess.run(["sh", str(self.script)], env=self.env, text=True, capture_output=True)

    def unchanged(self):
        for path, data in self.original.items():
            self.assertEqual(path.read_bytes(), data)

    def test_exports_exact_files_without_overwriting_prior_exports(self):
        for _ in range(2):
            result = self.run_export()
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("MCD_EXPORT=PASS", result.stdout)
        exports = list((self.card / "Dump").iterdir())
        self.assertEqual(len(exports), 2)
        for export in exports:
            self.assertEqual((export / "mcd").read_bytes(), self.original[self.root / "mcd"])
            self.assertEqual((export / "mcd.conf").read_bytes(), self.original[self.root / "active.conf"])
            self.assertEqual((export / "mcd.system.conf").read_bytes(), self.original[self.root / "system.conf"])
            self.assertEqual(len((export / "CKSUMS.txt").read_text().splitlines()), 3)
        self.unchanged()

    def test_missing_source_fails_before_creating_export(self):
        (self.root / "mcd").unlink()
        result = self.run_export()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MCD_EXPORT=FAIL", result.stderr)
        self.assertNotIn("MCD_EXPORT=PASS", result.stdout)
        self.assertFalse((self.card / "Dump").exists())

    def test_corrupt_copy_fails_and_leaves_sources_unchanged(self):
        self.stub("cp", 'printf corrupt > "$2"')
        result = self.run_export()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Copy differs", result.stderr)
        self.assertNotIn("MCD_EXPORT=PASS", result.stdout)
        self.unchanged()

    def test_failed_flush_cannot_report_success(self):
        self.stub("sync", "exit 1")
        result = self.run_export()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot flush", result.stderr)
        self.assertNotIn("MCD_EXPORT=PASS", result.stdout)
        self.unchanged()

    def test_dump_link_cannot_redirect_export_outside_card(self):
        outside = self.root / "outside"
        outside.mkdir()
        (self.card / "Dump").symlink_to(outside, target_is_directory=True)
        result = self.run_export()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("symbolic link", result.stderr)
        self.assertEqual(list(outside.iterdir()), [])
        self.unchanged()


if __name__ == "__main__":
    unittest.main()
