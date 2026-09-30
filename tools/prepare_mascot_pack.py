#!/usr/bin/env python3
"""Build an explicitly selected local mascot pack; no third-party artwork downloads."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

MAX_MASCOTS = 16
MAX_BYTES = 16 * 1024 * 1024


def java_source(entries):
    choices = ", ".join(json.dumps(s, ensure_ascii=True) for s in ["Off"] + [e["name"] for e in entries])
    return ('package com.luka.carplay.settings;\n'
            'public final class MascotCatalog {\n'
            '    public static final String[] CHOICES={' + choices + '};\n'
            '    private MascotCatalog() {}\n}\n')


def build(config, output):
    data = json.loads(config.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data) != {"format", "mascots"} or type(data["format"]) is not int or data["format"] != 1 or not isinstance(data["mascots"], list):
        raise ValueError("Expected format=1 and a mascots array")
    if len(data["mascots"]) > MAX_MASCOTS:
        raise ValueError("At most 16 mascots are supported")
    atlas = bytearray(b"MASCOT02" + struct.pack("<I", len(data["mascots"])))
    entries, names = [], {"off"}
    for item in data["mascots"]:
        if not isinstance(item, dict) or set(item) != {"name", "file", "facing", "background"}:
            raise ValueError("Each mascot needs name, file, facing and background")
        name = item["name"]
        if (not isinstance(name, str) or not 1 <= len(name) <= 24 or name != name.strip()
                or any(ord(c) < 32 or ord(c) > 126 for c in name) or name.lower() in names):
            raise ValueError("Names must be unique printable ASCII, 1..24 characters, excluding Off")
        if item["facing"] not in ("left", "right") or item["background"] not in ("transparent", "exterior-white"):
            raise ValueError("Invalid facing or background treatment")
        if not isinstance(item["file"], str) or not item["file"]:
            raise ValueError("Missing local GIF path")
        path = (config.parent / item["file"]).resolve()
        from build_mascot_assets import animation
        frames, delays = animation(path, item["background"] == "exterior-white")
        width, height = frames[0].size
        atlas += struct.pack("<IIII", width, height, len(frames), item["facing"] == "left")
        atlas += struct.pack("<" + "I" * len(delays), *delays)
        for frame in frames:
            atlas += frame.tobytes()
        if len(atlas) > MAX_BYTES:
            raise ValueError("Atlas exceeds 16 MiB")
        entries.append({"name": name, "facing": item["facing"], "background": item["background"],
                        "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        names.add(name.lower())
    # Validate all input before replacing generated outputs.
    output.mkdir(parents=True, exist_ok=True)
    catalog = {"format": 2, "entries": entries, "atlas_sha256": hashlib.sha256(atlas).hexdigest()}
    (output / "mascots.rgba").write_bytes(atlas)
    (output / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="ascii")
    (output / "MascotCatalog.java").write_text(java_source(entries), encoding="ascii")
    print("Mascot pack: %d entries, %d bytes" % (len(entries), len(atlas)))


def validate(pack):
    catalog = json.loads((pack / "catalog.json").read_text(encoding="ascii"))
    entries = catalog["entries"]
    if set(catalog) != {"format", "entries", "atlas_sha256"} or catalog["format"] != 2 or not isinstance(entries, list) or len(entries) > MAX_MASCOTS:
        raise ValueError("Invalid catalog")
    atlas = (pack / "mascots.rgba").read_bytes()
    if len(atlas) > MAX_BYTES or hashlib.sha256(atlas).hexdigest() != catalog["atlas_sha256"]:
        raise ValueError("Atlas does not match catalog; regenerate the pack")
    if atlas[:12] != b"MASCOT02" + struct.pack("<I", len(entries)):
        raise ValueError("Atlas count/version differs from catalog")
    if (pack / "MascotCatalog.java").read_text(encoding="ascii") != java_source(entries):
        raise ValueError("Java choices do not match catalog; regenerate the pack")
    offset, names = 12, {"off"}
    for entry in entries:
        name = entry["name"]
        if (not isinstance(name, str) or not 1 <= len(name) <= 24 or name != name.strip()
                or any(ord(c) < 32 or ord(c) > 126 for c in name) or name.lower() in names):
            raise ValueError("Invalid catalog label")
        names.add(name.lower())
        if entry["facing"] not in ("left", "right") or entry["background"] not in ("transparent", "exterior-white"):
            raise ValueError("Invalid catalog animation metadata")
        if offset + 16 > len(atlas):
            raise ValueError("Truncated animation header")
        width, height, count, left = struct.unpack_from("<IIII", atlas, offset)
        offset += 16
        if not (1 <= width <= 128 and height == 40 and 2 <= count <= 32) or left != (entry["facing"] == "left"):
            raise ValueError("Invalid animation dimensions, count or facing")
        if offset + count * 4 > len(atlas):
            raise ValueError("Truncated frame delays")
        if any(not 20 <= delay <= 2000 for delay in struct.unpack_from("<" + "I" * count, atlas, offset)):
            raise ValueError("Invalid frame delay")
        offset += count * 4 + width * height * count * 4
    if offset != len(atlas):
        raise ValueError("Invalid animation byte length")
    return catalog


def stage(pack, jar, shell_source, shell_output, atlas_output):
    import zipfile
    if pack:
        catalog = validate(pack)
        atlas = (pack / "mascots.rgba").read_bytes()
    else:
        catalog = {"format": 2, "entries": []}
        atlas = b"MASCOT02" + struct.pack("<I", 0)
    with zipfile.ZipFile(jar) as archive:
        built = json.loads(archive.read("com/luka/carplay/settings/mascot-catalog.json"))
        source = archive.read("com/luka/carplay/settings/mascot-catalog.java").decode("ascii")
    if source.replace("\r\n", "\n") != java_source(catalog["entries"]):
        raise ValueError("JAR was compiled with different mascot choices; rebuild Java")
    if built != catalog:
        raise ValueError("JAR mascot catalog differs from selected pack; rebuild Java with the same MASCOT_PACK")
    script = shell_source.read_text(encoding="ascii")
    marker = 'max["mascot"]=4'
    if script.count(marker) != 1:
        raise ValueError("Settings validator mascot bound marker changed")
    shell_output.write_text(script.replace(marker, 'max["mascot"]=%d' % len(catalog["entries"])), encoding="ascii")
    atlas_output.write_bytes(atlas)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--validate", type=Path)
    parser.add_argument("--stage", type=Path)
    parser.add_argument("--pack", type=Path)
    parser.add_argument("--shell-source", type=Path)
    parser.add_argument("--shell-output", type=Path)
    parser.add_argument("--atlas-output", type=Path)
    args = parser.parse_args()
    try:
        if args.validate:
            validate(args.validate)
        elif args.stage:
            stage(args.pack, args.stage, args.shell_source, args.shell_output, args.atlas_output)
        elif args.config and args.output:
            build(args.config, args.output)
        else:
            parser.error("Use --config/--output, --validate, or --stage")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, "MASCOT_PACK=ERROR %s\n" % error)


if __name__ == "__main__":
    main()
