#!/usr/bin/env python3
"""Check or regenerate the Toolbox final-script checksum in an SD tree."""

import argparse
import hashlib
import re
from pathlib import Path


CHECKSUM = re.compile(r'^(FinalScriptChecksum\s*=\s*")[0-9a-fA-F]{40}("\s*)$', re.MULTILINE)


def process(root, update=False):
    metadata = root / "metainfo2.txt"
    text = metadata.read_text(encoding="utf-8")
    if len(CHECKSUM.findall(text)) != 1:
        raise ValueError("expected exactly one FinalScriptChecksum field")
    if re.findall(r'^FinalScript\s*=\s*"([^"]+)"', text, re.MULTILINE) != [
        "./Toolbox/final/finalScript.sh"
    ]:
        raise ValueError("unexpected FinalScript path")
    digest = hashlib.sha1((root / "Toolbox" / "final" / "finalScript.sh").read_bytes()).hexdigest()
    corrected = CHECKSUM.sub(lambda match: match[1] + digest + match[2], text)
    if corrected != text:
        if not update:
            raise ValueError("FinalScriptChecksum does not match finalScript.sh")
        with metadata.open("w", encoding="utf-8", newline="\n") as output:
            output.write(corrected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()
    try:
        process(args.root, args.update)
    except (OSError, ValueError) as error:
        parser.exit(1, "ERROR: " + str(error) + "\n")
    print("Toolbox final-script checksum: PASS")


if __name__ == "__main__":
    main()
