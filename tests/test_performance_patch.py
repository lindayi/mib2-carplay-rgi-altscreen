"""Verify the native adapter against upstream transforms on private exact-unit inputs."""
import hashlib
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
helper = str(Path(sys.argv[1]).resolve())
inputs = dict(zip(("decoder", "mcd", "priority"), map(Path, sys.argv[2:])))
assert len(inputs) == 3
upstream = runpy.run_path(str(root / "deploy/performance/binpatch.py"))


def run(*args, ok=True):
    p = subprocess.run([helper, *map(str, args)], capture_output=True, text=True)
    assert (p.returncode == 0) == ok, (args, p.stdout, p.stderr)
    return p.stdout.strip()


with tempfile.TemporaryDirectory() as temp:
    temp = Path(temp)
    for role, original in inputs.items():
        before = original.read_bytes()
        assert run("check", role, original) == "stock"
        if role == "priority":
            expected, count = re.subn(rb"(?m)^(Priority[ \t]*=[ \t]*11),10$", rb"\1,3", before)
            assert count == 13
        else:
            edits, code = upstream["patch"](before, upstream["PATCHES"]["airplay_decoder" if role == "decoder" else role])
            assert code == 0
            expected = bytearray(before)
            for at, value in edits:
                expected[at:at + len(value)] = value
            expected = bytes(expected)
        candidate = temp / role
        run("apply", role, original, candidate)
        assert candidate.read_bytes() == expected
        assert run("check", role, candidate) == "optimized"
        repeat = temp / (role + "-repeat")
        run("apply", role, candidate, repeat)
        assert repeat.read_bytes() == expected
        run("apply", role, original, candidate, ok=False)
        assert candidate.read_bytes() == expected
        for label, modified in (("corrupt", bytes([before[0] ^ 1]) + before[1:]),
                                ("truncated", before[:-1]),
                                ("partial", expected[:len(expected)//2] + before[len(expected)//2:])):
            if modified in (before, expected):
                offset = next(i for i, (a, b) in enumerate(zip(before, expected)) if a != b)
                partial = bytearray(before)
                partial[offset] ^= 0x80
                modified = bytes(partial)
            bad = temp / (role + label)
            bad.write_bytes(modified)
            output = temp / (role + label + "-out")
            run("check", role, bad, ok=False)
            run("apply", role, bad, output, ok=False)
            assert not output.exists()
        assert original.read_bytes() == before
        print(role, "native/upstream byte equality, refusal, idempotence PASS",
              hashlib.sha256(expected).hexdigest())
