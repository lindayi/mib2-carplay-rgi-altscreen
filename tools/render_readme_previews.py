#!/usr/bin/env python3
"""Compose README figures from public host fixtures, never private captures."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets" / "readme"
FONT = Path("/usr/share/fonts/truetype/dejavu")
BACKGROUND = "#101820"
TEXT = "#f2f6fc"
MUTED = "#abbccc"
ACCENT = "#83dbea"
BLUE = (102, 153, 204)


def font(size, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    return ImageFont.truetype(str(FONT / name), size)


def fixture(relative, size):
    path = ROOT / "build" / relative
    with Image.open(path) as source:
        if source.size != size:
            raise ValueError("Fixture geometry changed: " + str(path))
        return source.convert("RGB")


def canvas(title, subtitle, height):
    image = Image.new("RGB", (1120, height), BACKGROUND)
    draw = ImageDraw.Draw(image)
    draw.text((28, 20), title, font=font(27, True), fill=TEXT)
    draw.text((28, 59), subtitle, font=font(15), fill=MUTED)
    draw.rectangle((945, 24, 1092, 51), fill="#203e49")
    draw.text((956, 31), "HOST PREVIEW", font=font(12, True), fill=ACCENT)
    return image, draw


def save(image, name):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    image.save(path, optimize=True)
    print(name, image.size, path.stat().st_size)


def guidance():
    image, draw = canvas("Guidance at a glance", "Synthetic exits and roundabouts, rendered by the project.", 500)
    for x, name in ((20, "exit-lanes"), (572, "roundabout-lanes")):
        source = fixture("route-label-previews/" + name + ".png", (328, 181))
        bounds = source.getbbox()
        if bounds is None or bounds[0] < 32 or bounds[2] > 296:
            raise ValueError("Review the guidance crop after renderer changes")
        draw.rectangle((x - 4, 94, x + 532, 472), fill="#000000", outline="#304354")
        image.paste(source.crop((32, 0, 296, 181)).resize((528, 362), Image.NEAREST), (x, 102))
    draw.text((28, 479), "Host GLES output, enlarged for readability. Not a vehicle photograph.", font=font(12), fill=MUTED)
    save(image, "guidance.png")


def cards():
    image, draw = canvas("Useful information alongside the map", "Optional media / Trip cards and the separate experimental speed badge.", 446)
    for x in (24, 572):
        draw.rectangle((x, 94, x + 524, 416), fill=BLUE)
    source = fixture("map-card-tests/both-art.png", (1440, 455))
    if source.getpixel((0, 0)) != BLUE:
        raise ValueError("Review the card fixture background before composing")
    image.paste(source.crop((113, 64, 355, 332)), (44, 120))
    ink = "#132b46"
    draw.multiline_text((306, 129), "Now Playing\n+ Trip", font=font(24, True), fill=ink, spacing=7)
    draw.multiline_text((306, 219), "Independent switches.\nScrolling media text.\nEstimated trip progress.",
                        font=font(15), fill=ink, spacing=12)
    source = fixture("map-card-tests/speed-above.png", (1440, 455))
    image.paste(source.crop((920, 115, 1058, 187)).resize((414, 216), Image.NEAREST), (627, 112))
    draw.text((602, 347), "Camera-first badge: synthetic values.", font=font(16, True), fill=ink)
    draw.text((602, 377), "Phone sign is a separate setting.", font=font(15), fill=ink)
    draw.text((28, 426), "Public fixture artwork and data on a plain test background; not actual map video.", font=font(12), fill=MUTED)
    save(image, "map-cards.png")


def controls():
    image, draw = canvas("Everyday controls", "Normal MMI for full settings; a guarded VC panel for quick changes.", 460)
    for x in (24, 572):
        draw.rectangle((x, 94, x + 524, 430), fill="#192631", outline="#304354")
    draw.text((46, 112), "Normal MMI: menu path", font=font(18, True), fill=TEXT)
    steps = ("NAV / right drawer", "Navigation settings", "Carplay Altscreen")
    for i, text in enumerate(steps):
        y = 167 + i * 76
        if i < 2:
            draw.line((72, y + 36, 72, y + 85), fill="#466374", width=2)
        draw.ellipse((54, y, 90, y + 36), fill="#2b6376")
        draw.text((66, y + 7), str(i + 1), font=font(16, True), fill=TEXT)
        draw.text((110, y + 5), text, font=font(20), fill=TEXT)
    draw.text((598, 104), "VC panel: host UI fixture", font=font(16, True), fill=TEXT)
    source = fixture("vc-panel-previews/root.png", (960, 400))
    image.paste(source.crop((524, 56, 944, 344)), (624, 131))
    draw.text((28, 440), "Left: navigation diagram. Right: renderer fixture, not a native-MMI screen capture.", font=font(12), fill=MUTED)
    save(image, "controls.png")


if __name__ == "__main__":
    guidance()
    cards()
    controls()
