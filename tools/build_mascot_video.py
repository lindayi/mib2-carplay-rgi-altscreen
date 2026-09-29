#!/usr/bin/env python3
"""Extract a bounded transparent mascot GIF from a locally supplied keyed video."""
import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from PIL import Image


def frame_delays(rate, count):
    delays = [round(Fraction((i + 1) * 100, 1) / rate) * 10
              - round(Fraction(i * 100, 1) / rate) * 10 for i in range(count)]
    if any(delay < 20 or delay > 2000 for delay in delays):
        raise ValueError("Video frame rate cannot fit the mascot's 20..2000 ms frame delays")
    return delays


def convert(source, output, start, count, color):
    if output.suffix.lower() != ".gif" or source.resolve() == output.resolve():
        raise ValueError("Output must be a separate GIF, never the source video")
    if start < 0 or not 2 <= count <= 32:
        raise ValueError("Choose a nonnegative start frame and 2..32 frames")
    if not re.fullmatch(r"[0-9a-fA-F]{6}", color):
        raise ValueError("Key color must be six hexadecimal RGB digits")
    metadata = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=avg_frame_rate,r_frame_rate", "-of", "json", str(source)
    ], timeout=30))
    if len(metadata["streams"]) != 1:
        raise ValueError("Expected one selected video stream")
    stream = metadata["streams"][0]
    rate = Fraction(stream["avg_frame_rate"])
    if rate <= 0 or rate != Fraction(stream["r_frame_rate"]):
        raise ValueError("Use a constant-frame-rate source for frame-accurate extraction")
    delays = frame_delays(rate, count)
    with tempfile.TemporaryDirectory(prefix="mascot-video-") as directory:
        root = Path(directory)
        subprocess.run([
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-i", str(source), "-an", "-vf",
            f"select='between(n,{start},{start + count - 1})',format=rgba,"
            f"colorkey=0x{color}:0.15:0.05",
            "-vsync", "0", "-frames:v", str(count), str(root / "frame-%03d.png")
        ], check=True, timeout=120)
        paths = sorted(root.glob("frame-*.png"))
        if len(paths) != count:
            raise ValueError("Video ends before the requested complete loop")
        frames = []
        for path in paths:
            with Image.open(path) as image:
                frame = image.convert("RGBA")
            alpha = frame.getchannel("A").point(lambda value: 255 if value >= 128 else 0)
            corners = ((0, 0), (frame.width - 1, 0),
                       (0, frame.height - 1), (frame.width - 1, frame.height - 1))
            if any(alpha.getpixel(point) for point in corners):
                raise ValueError("Key color did not remove the frame borders")
            frame.putalpha(alpha)
            frames.append(frame)
        boxes = [frame.getchannel("A").getbbox() for frame in frames]
        if any(box is None for box in boxes):
            raise ValueError("Keying removed the entire subject")
        box = (min(b[0] for b in boxes), min(b[1] for b in boxes),
               max(b[2] for b in boxes), max(b[3] for b in boxes))
        frames = [frame.crop(box) for frame in frames]
        swatches = Image.new("RGB", (frames[0].width, frames[0].height * count))
        for index, frame in enumerate(frames):
            swatches.paste(frame, (0, index * frame.height), frame.getchannel("A"))
        palette = swatches.quantize(colors=255)
        indexed = []
        for frame in frames:
            encoded = frame.convert("RGB").quantize(palette=palette, dither=Image.NONE)
            encoded.paste(255, mask=frame.getchannel("A").point(lambda value: 255 - value))
            indexed.append(encoded)
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(output.suffix + ".new")
        indexed[0].save(temporary, format="GIF", save_all=True, append_images=indexed[1:],
                        duration=delays, loop=0, transparency=255, disposal=2, optimize=False)
        temporary.replace(output)
    report = dict(source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  start_frame=start, frames=count, source_fps=str(rate), delays_ms=delays,
                  crop=list(box), key_rgb=color.lower(),
                  gif_sha256=hashlib.sha256(output.read_bytes()).hexdigest())
    output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--start-frame", type=int, required=True)
    parser.add_argument("--frames", type=int, required=True)
    parser.add_argument("--key-color", required=True)
    args = parser.parse_args()
    report = convert(args.input, args.output, args.start_frame, args.frames, args.key_color)
    print(f"Transparent mascot: {report['frames']} frames, {sum(report['delays_ms'])} ms")


if __name__ == "__main__":
    main()
