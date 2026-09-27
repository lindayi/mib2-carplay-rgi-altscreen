#!/usr/bin/env python3
"""Build bounded RGBA animation assets from the owner's locally supplied GIFs."""
import argparse
import hashlib
import json
import struct
from collections import deque
from pathlib import Path
from PIL import Image, ImageSequence


def transparent_background(frame):
    frame = frame.convert("RGBA")
    pixels = frame.load()
    width, height = frame.size
    seen = set()
    pending = deque([(x, 0) for x in range(width)] + [(x, height - 1) for x in range(width)]
                    + [(0, y) for y in range(height)] + [(width - 1, y) for y in range(height)])
    while pending:
        x, y = pending.popleft()
        if (x, y) in seen or not (0 <= x < width and 0 <= y < height):
            continue
        seen.add((x, y))
        r, g, b, a = pixels[x, y]
        if a == 0 or min(r, g, b) >= 245:
            pixels[x, y] = (0, 0, 0, 0)
            pending.extend(((x-1, y), (x+1, y), (x, y-1), (x, y+1)))
    return frame


def animation(path, remove_background):
    frames, delays = [], []
    with Image.open(path) as image:
        if image.n_frames < 2 or image.n_frames > 32:
            raise ValueError("Expected 2..32 animation frames")
        for frame in ImageSequence.Iterator(image):
            rgba = transparent_background(frame) if remove_background else frame.convert("RGBA")
            frames.append(rgba.copy())
            delay = frame.info.get("duration", 100)
            if not 20 <= delay <= 2000:
                raise ValueError(f"Unsupported GIF frame delay: {delay}")
            delays.append(delay)
    boxes = [frame.getbbox() for frame in frames]
    if any(box is None for box in boxes):
        raise ValueError("Empty mascot frame")
    box = (min(b[0] for b in boxes), min(b[1] for b in boxes),
           max(b[2] for b in boxes), max(b[3] for b in boxes))
    height = 40
    width = max(1, round((box[2]-box[0])*height/(box[3]-box[1])))
    if width > 128:
        raise ValueError("Mascot aspect ratio is too wide")
    resampling = getattr(Image, "Resampling", Image)
    frames = [frame.crop(box).resize((width, height), resampling.LANCZOS) for frame in frames]
    return frames, delays


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raccoon", type=Path, required=True)
    parser.add_argument("--nian", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = bytearray(b"MASCOT01" + struct.pack("<I", 2))
    provenance = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for name, path, remove in (("raccoon", args.raccoon, True), ("nian", args.nian, False)):
        frames, delays = animation(path, remove)
        width, height = frames[0].size
        output += struct.pack("<III", width, height, len(frames))
        output += struct.pack("<" + "I"*len(delays), *delays)
        for index, frame in enumerate(frames):
            output += frame.tobytes()
            frame.save(args.output.parent / f"{name}-{index}.png")
        provenance.append(dict(name=name, source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                               width=width, height=height, frames=len(frames), delays_ms=delays))
    args.output.write_bytes(output)
    args.output.with_suffix(".json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"Mascot atlas: {len(output)} bytes; " + ", ".join(f"{p['name']}={p['frames']} frames" for p in provenance))


if __name__ == "__main__":
    main()
