#!/usr/bin/env python3
"""Audit the pinned GPL source baseline or prepare a non-destructive three-way update."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile

PREFIX = "Toolbox/carplay_alt_screen/"
TREES = tuple(PREFIX + p for p in (
    "hmi/src", "rgi_native/src", "rgi_renderer/src",
    "rgi_renderer/common", "rgi_renderer/toolchain",
))
LOCK = PREFIX + "UPSTREAM.json"


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def inventory(root, revision):
    return set(git(root, "ls-tree", "-r", "--name-only", revision, "--", *TREES)
               .decode("utf-8").splitlines())


def blob(root, revision, path):
    return git(root, "show", revision + ":" + path)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def normalized(data):
    return data.replace(b"\r\n", b"\n")


def source_path(root, path):
    relative = PurePosixPath(path)
    if relative.is_absolute() or ".." in relative.parts or "\\" in path:
        raise ValueError("Unsafe source path: " + path)
    result = root.joinpath(*relative.parts)
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError("Source path escapes checkout: " + path)
    return result


def check(root, lock):
    revision = lock["commit"]
    if git(root, "rev-parse", "--verify", revision + "^{commit}").decode().strip() != revision:
        raise ValueError("Baseline must use a full commit ID")
    entries = lock["files"]
    if len({e["upstream"] for e in entries}) != len(entries):
        raise ValueError("Duplicate upstream source entry")
    expected = inventory(root, revision)
    accounted = {e["upstream"] for e in entries}
    if expected != accounted:
        raise ValueError("Unaccounted baseline files: " + repr(sorted(expected ^ accounted)))
    same = adapted = replacements = 0
    for entry in entries:
        upstream = blob(root, revision, entry["upstream"])
        if digest(upstream) != entry["sha256"]:
            raise ValueError("Upstream hash mismatch: " + entry["upstream"])
        if not entry.get("reason"):
            raise ValueError("Missing delta rationale: " + entry["upstream"])
        local = entry["local"]
        if local is None:
            for replacement in entry["replacements"]:
                if not source_path(root, replacement).is_file():
                    raise ValueError("Missing adapter: " + replacement)
            if not entry["replacements"]:
                raise ValueError("Unmapped upstream implementation: " + entry["upstream"])
            replacements += 1
            continue
        local_data = source_path(root, local).read_bytes()
        if normalized(local_data) == normalized(upstream):
            same += 1
        else:
            adapted += 1
    return same, adapted, replacements


def merge(ours, base, theirs):
    # Temporary paths never enter the patch markers or the source checkout.
    with tempfile.TemporaryDirectory(prefix="rgi-merge-") as temp:
        paths = [Path(temp) / name for name in ("local", "baseline", "upstream")]
        for path, data in zip(paths, (ours, base, theirs)):
            path.write_bytes(normalized(data))
        result = subprocess.run(
            ["git", "merge-file", "--diff3", "-p", "-L", "local",
             "-L", "baseline", "-L", "upstream", *map(str, paths)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if result.returncode < 0 or result.returncode > 127:
            raise RuntimeError("git merge-file failed: " + result.stderr.decode("utf-8", "replace"))
        return result.stdout, result.returncode != 0


def plan(root, lock, revision, output):
    check(root, lock)
    revision = git(root, "rev-parse", "--verify", revision + "^{commit}").decode().strip()
    # Require an unused build directory. Never overwrite source, a package or an earlier plan.
    output = output.resolve()
    if not output.is_relative_to((root / "build").resolve()) or output.exists():
        raise ValueError("Update output must be a new directory below build/")
    new_paths = inventory(root, revision)
    old_paths = {e["upstream"] for e in lock["files"]}
    review = [{"path": p, "reason": "new upstream file: classify before adoption"}
              for p in sorted(new_paths - old_paths)]
    writes, removals, entries = {}, [], []
    for entry in lock["files"]:
        path, local = entry["upstream"], entry["local"]
        base = blob(root, lock["commit"], path)
        if path not in new_paths:
            if local is not None and normalized(source_path(root, local).read_bytes()) != normalized(base):
                review.append({"path": path, "reason": "upstream removed a locally modified file"})
            else:
                if local is not None:
                    removals.append(local)
                review.append({"path": path, "reason": "upstream deletion: review before removal"})
            continue
        theirs = blob(root, revision, path)
        next_entry = dict(entry, sha256=digest(theirs))
        entries.append(next_entry)
        if normalized(theirs) == normalized(base):
            continue
        if local is None:
            review.append({"path": path, "reason": "upstream changed an explicitly replaced component"})
            continue
        data, conflicted = merge(source_path(root, local).read_bytes(), base, theirs)
        writes[local] = data
        if conflicted:
            review.append({"path": path, "reason": "three-way conflict"})
    candidate = dict(lock, commit=revision, files=entries)
    # A reviewed candidate is not the active baseline until its source changes and tests pass.
    report = {"from": lock["commit"], "to": revision, "review": review,
              "writes": sorted(writes), "removals": removals,
              "applied": False}
    output.mkdir(parents=True)
    for path, data in writes.items():
        target = source_path(output, path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (output / "UPSTREAM.candidate.json").write_text(json.dumps(candidate, indent=2) + "\n", encoding="utf-8")
    (output / "PLAN.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "plan"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--revision", help="Fetched new upstream commit/ref (plan only)")
    parser.add_argument("--output", type=Path, help="Unused directory under build (plan only)")
    args = parser.parse_args()
    root = args.root.resolve()
    lock = json.loads((root / LOCK).read_text(encoding="utf-8"))
    if args.command == "check":
        same, adapted, replacements = check(root, lock)
        print(f"RGI_BASELINE=PASS identical={same} adapted={adapted} explicit_replacements={replacements}")
    else:
        if not args.revision or not args.output:
            parser.error("plan requires --revision and --output")
        report = plan(root, lock, args.revision, args.output)
        print(json.dumps(report, indent=2))
        if report["review"]:
            return 2
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print("RGI_BASELINE=FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
