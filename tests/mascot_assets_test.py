import importlib.util
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
    image.save(path)
    try:
        assets.animation(path, True)
    except ValueError:
        pass
    else:
        raise AssertionError("single-frame input accepted")
print("Mascot GIF preprocessing: exterior transparency, enclosed white, frame timing and bounds PASS")
