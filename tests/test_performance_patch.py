"""Verify the native adapter against upstream transforms on private exact-unit inputs."""
import hashlib
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile
import os

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
    assert run("--self-test") == "PERFORMANCE_HELPER=READY"
    original = inputs["decoder"]
    alias = temp / "alias"
    alias.symlink_to(original)
    run("same-file", original, alias)
    separate = temp / "separate"
    separate.write_bytes(original.read_bytes())
    run("same-file", original, separate, ok=False)
    hardlink = temp / "hardlink"
    os.link(separate, hardlink)
    run("same-file", separate, hardlink, ok=False)
    run("lock-held", ok=False)
    run("lock-exec", "/bin/sh", "-c", 'exec "$1" lock-held', "_", helper)
    run("lock-exec", sys.executable, "-c",
        'import os, subprocess, sys; os.close(int(os.environ["CARPLAY_PERFORMANCE_LOCK_FD"])); '
        'sys.exit(subprocess.call([sys.argv[1], "lock-held"], close_fds=False))', helper, ok=False)
    holder = subprocess.Popen([helper, "lock-exec", "/bin/sh", "-c",
                               'echo ready; sleep 6'], stdout=subprocess.PIPE, text=True)
    assert holder.stdout.readline().strip() == "ready"
    run("lock-exec", "/bin/sh", "-c", "exit 0", ok=False)
    assert holder.wait(timeout=10) == 0
    run("lock-exec", "/bin/sh", "-c", "exit 0")
    holder = subprocess.Popen([helper, "lock-exec", "/bin/sh", "-c",
                               'sleep 5 & echo ready; wait'], stdout=subprocess.PIPE, text=True)
    assert holder.stdout.readline().strip() == "ready"
    holder.kill()
    holder.wait(timeout=5)
    run("lock-exec", "/bin/sh", "-c", "exit 0", ok=False)
    run("lock-exec", "/bin/sh", "-c", "exit 0")
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
