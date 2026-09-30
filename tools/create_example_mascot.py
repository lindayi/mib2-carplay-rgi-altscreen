#!/usr/bin/env python3
"""Generate an original geometric robot animation and a local pack configuration.

The generated artwork is dedicated to the public domain under CC0 1.0:
https://creativecommons.org/publicdomain/zero/1.0/
"""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw


def create(output):
    output.mkdir(parents=True, exist_ok=True)
    frames = []
    for step in (0, 1, 2, 1):
        frame = Image.new("P", (32, 40), 0)
        frame.putpalette([0, 0, 0, 44, 190, 190, 242, 196, 65, 30, 40, 60] + [0] * 756)
        draw = ImageDraw.Draw(frame)
        draw.rectangle((8, 7, 23, 18), fill=1)
        draw.rectangle((11, 21, 21, 30), fill=1)
        draw.rectangle((21, 10, 23, 12), fill=2)
        draw.rectangle((13, 3, 15, 6), fill=2)
        draw.rectangle((6, 23 + step, 9, 29 + step), fill=2)
        draw.rectangle((23, 25 - step, 26, 31 - step), fill=2)
        draw.rectangle((11 - step, 31, 14 - step, 36), fill=3)
        draw.rectangle((19 + step, 31, 22 + step, 36), fill=3)
        frames.append(frame)
    frames[0].save(output / "robot.gif", save_all=True, append_images=frames[1:],
                   duration=[100, 140, 100, 140], transparency=0, disposal=2, optimize=False, loop=0)
    config = {"format": 1, "mascots": [
        {"name": "Robot", "file": "robot.gif", "facing": "right", "background": "transparent"}]}
    (output / "pack.json").write_text(json.dumps(config, indent=2) + "\n", encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    create(parser.parse_args().output)
