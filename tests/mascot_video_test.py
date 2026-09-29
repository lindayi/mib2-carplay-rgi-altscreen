import importlib.util
from fractions import Fraction
from pathlib import Path
import subprocess
import tempfile
from PIL import Image, ImageDraw, ImageSequence

spec = importlib.util.spec_from_file_location("video", Path(__file__).parents[1] / "tools/build_mascot_video.py")
video = importlib.util.module_from_spec(spec)
spec.loader.exec_module(video)
assert video.frame_delays(Fraction(30), 20) == [30, 40, 30, 30, 40, 30, 30, 40, 30,
                                               30, 40, 30, 30, 40, 30, 30, 40, 30, 30, 40]
try:
    video.frame_delays(Fraction(120), 20)
except ValueError:
    pass
else:
    raise AssertionError("Unrepresentable frame timing accepted")
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    for index in range(9):
        image = Image.new("RGB", (128, 96), "#0044b8")
        draw = ImageDraw.Draw(image)
        draw.ellipse((16 + index * 2, 20, 88 + index * 2, 76), fill="#65bd58")
        draw.rectangle((40 + index * 2, 35, 50 + index * 2, 45), fill="white")
        image.save(root / f"input-{index:02d}.png")
    source, output = root / "video.mp4", root / "lizard.gif"
    subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-framerate", "30", "-i", str(root / "input-%02d.png"),
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", str(source)], check=True)
    report = video.convert(source, output, 2, 6, "0044b8")
    assert report["start_frame"] == 2 and sum(report["delays_ms"]) == 200
    with Image.open(output) as gif:
        assert gif.n_frames == 6
        assert [f.info["duration"] for f in ImageSequence.Iterator(gif)] == report["delays_ms"]
        gif.seek(0)
        first = gif.convert("RGBA")
        assert first.getpixel((0, 0))[3] == 0
        assert first.getpixel((first.width // 2, first.height // 2))[3] == 255
        assert first.width < 128 and first.height < 96
        eye = first.getpixel((49 - report["crop"][0], 40 - report["crop"][1]))
        assert sum(eye[:3]) > 700 and eye[3] == 255
    before = output.read_bytes()
    original = source.read_bytes()
    try:
        video.convert(source, source, 2, 6, "0044b8")
    except ValueError:
        pass
    else:
        raise AssertionError("Source overwrite accepted")
    assert source.read_bytes() == original
    for start, count, color in ((0, 33, "0044b8"), (-1, 2, "0044b8"),
                                (0, 2, "bad"), (8, 6, "0044b8"), (0, 6, "ff0000")):
        try:
            video.convert(source, output, start, count, color)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid loop or key accepted")
        assert output.read_bytes() == before
print("Mascot video: keyed transparency, crop, frame-accurate loop, timing, bounds and failure preservation PASS")
