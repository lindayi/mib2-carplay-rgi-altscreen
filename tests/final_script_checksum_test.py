import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "final_script_checksum", Path(__file__).resolve().parents[1] / "tools" / "final_script_checksum.py"
)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class FinalScriptChecksumTest(unittest.TestCase):
    def test_changed_script_requires_new_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "Toolbox" / "final" / "finalScript.sh"
            script.parent.mkdir(parents=True)
            script.write_bytes(b"#!/bin/sh\nexit 0\n")
            metadata = root / "metainfo2.txt"
            metadata.write_text(
                'FinalScript = "./Toolbox/final/finalScript.sh"\n'
                'FinalScriptChecksum = "' + "0" * 40 + '"\nFinalScriptMaxTime = "30"\n'
            )
            with self.assertRaisesRegex(ValueError, "does not match"):
                CHECK.process(root)
            CHECK.process(root, update=True)
            CHECK.process(root)
            self.assertIn('FinalScriptMaxTime = "30"', metadata.read_text())
            script.write_bytes(b"#!/bin/sh\nexit 1\n")
            with self.assertRaisesRegex(ValueError, "does not match"):
                CHECK.process(root)

    def test_ambiguous_metadata_fails_without_rewriting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / "metainfo2.txt"
            text = ('FinalScriptChecksum = "' + "0" * 40 + '"\n') * 2
            metadata.write_text(text)
            with self.assertRaisesRegex(ValueError, "exactly one"):
                CHECK.process(root, update=True)
            self.assertEqual(text, metadata.read_text())


if __name__ == "__main__":
    unittest.main()
