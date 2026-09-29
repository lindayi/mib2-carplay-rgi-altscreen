import importlib.util
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location("assets", Path(__file__).parents[1] / "tools/build_mascot_assets.py")
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)
image = Image.new("RGBA", (20, 20), "white")
ImageDraw.Draw(image).rectangle((5, 5, 14, 14), outline="black", width=2)
clean = assets.transparent_background(image)
assert clean.getpixel((0, 0))[3] == 0
assert clean.getpixel((10, 10)) == (255, 255, 255, 255)
assert clean.getpixel((5, 5)) == (0, 0, 0, 255)
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / "synthetic.gif"
    other = image.copy()
    ImageDraw.Draw(other).point((9, 9), fill="red")
    image.save(path, save_all=True, append_images=[other], duration=[50, 120], loop=0)
    frames, delays = assets.animation(path, True)
    assert delays == [50, 120] and len(frames) == 2
    assert frames[0].size == (40, 40)
    assert frames[0].getpixel((20, 20))[3] == 255
    output = Path(directory) / "built" / "mascots.rgba"
    subprocess.run([sys.executable, assets.__file__, "--raccoon", str(path), "--nian", str(path),
                    "--capybara", str(path), "--lizard", str(path), "--output", str(output)], check=True)
    data = output.read_bytes()
    assert data[:12] == b"MASCOT01" + struct.pack("<I", 4)
    provenance = json.loads(output.with_suffix(".json").read_text())
    assert [p["name"] for p in provenance] == ["raccoon", "nian", "capybara", "lizard"]
    offset = 12
    for entry in provenance:
        width, height, count = struct.unpack_from("<III", data, offset)
        offset += 12
        assert count == 2 and height == 40
        assert list(struct.unpack_from("<II", data, offset)) == [50, 120]
        offset += 4 * count + width * height * count * 4
    assert offset == len(data)
    with Image.open(output.parent / "capybara-0.png") as capybara:
        assert capybara.tobytes() == frames[0].tobytes()
        assert capybara.getpixel((20, 20))[3] == 255
    with Image.open(output.parent / "lizard-0.png") as lizard:
        assert lizard.tobytes() == assets.animation(path, False)[0][0].tobytes()
    image.save(path)
    try:
        assets.animation(path, True)
    except ValueError:
        pass
    else:
        raise AssertionError("single-frame input accepted")
    bounce = []
    padded = []
    for top in (12, 4):
        frame = Image.new("RGBA", (32, 32))
        ImageDraw.Draw(frame).rectangle((8, top, 17, top + 9), fill="green")
        bounce.append(frame)
        large = Image.new("RGBA", (64, 64))
        large.paste(frame, (11, 19))
        padded.append(large)
    def save_loop(name, images):
        target = Path(directory) / name
        indexed = []
        for image in images:
            frame = Image.new("P", image.size, 0)
            frame.putpalette([0, 0, 0, 0, 128, 0] + [0] * 762)
            frame.paste(1, mask=image.getchannel("A"))
            indexed.append(frame)
        indexed[0].save(target, save_all=True, append_images=indexed[1:], duration=100,
                        loop=0, disposal=2, transparency=0, optimize=False)
        return assets.animation(target, False)[0]
    normalized = save_loop("bounce.gif", bounce)
    normalized_padded = save_loop("padded.gif", padded)
    assert [f.tobytes() for f in normalized] == [f.tobytes() for f in normalized_padded]
    assert normalized[0].size == normalized[1].size
    assert normalized[0].getchannel("A").getbbox()[3] == 40
    assert normalized[1].getchannel("A").getbbox()[3] < 30
print("Mascot GIF preprocessing: exterior transparency, enclosed white, frame timing and bounds PASS")
