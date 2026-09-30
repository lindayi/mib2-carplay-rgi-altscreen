import json
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from create_example_mascot import create
from prepare_mascot_pack import build, validate, stage, java_source

output = ROOT / "build/mascot-pack-tests"
create(output / "art")
config = output / "art/pack.json"
one = json.loads(config.read_text())
for count in (0, 1, 5, 16):
    entries = [dict(one["mascots"][0], name="Robot %d" % (i + 1), facing="right" if i % 2 == 0 else "left")
               for i in range(count)]
    config.write_text(json.dumps({"format": 1, "mascots": entries}))
    pack = output / str(count)
    build(config, pack)
    catalog = validate(pack)
    assert len(catalog["entries"]) == count
    atlas = (pack / "mascots.rgba").read_bytes()
    assert atlas[:12] == b"MASCOT02" + struct.pack("<I", count)
    offset = 12
    for i in range(count):
        w, h, frames, facing = struct.unpack_from("<IIII", atlas, offset)
        assert facing == i % 2 and h == 40 and frames == 4
        offset += 16 + frames * 4 + w * h * frames * 4
    assert offset == len(atlas)
    jar = pack / "fixture.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("com/luka/carplay/settings/mascot-catalog.json", json.dumps(catalog))
        archive.writestr("com/luka/carplay/settings/mascot-catalog.java", java_source(catalog["entries"]))
    stage(pack, jar, ROOT / "deploy/smartphone_integrator/carplay_settings.sh",
          pack / "carplay_settings.sh", pack / "staged.rgba")
    assert (pack / "staged.rgba").read_bytes() == atlas
    assert 'max["mascot"]=%d' % count in (pack / "carplay_settings.sh").read_text()
    script = '. "$1"; CP_SETTINGS_FILE="$2"; cp_setting mascot 0'
    fixture = (ROOT / "tests/fixtures/carplay-preferences.txt").read_text()
    for choice in range(count + 2):
        preferences = pack / "preferences"
        preferences.write_text(fixture.replace("mascot=0", "mascot=%d" % choice))
        result = subprocess.run(["sh", "-c", script, "test", str(pack / "carplay_settings.sh"), str(preferences)],
                                capture_output=True, text=True)
        # The shell reader records invalid data explicitly and takes its disabled path.
        if choice <= count:
            assert result.returncode == 0 and result.stdout.strip() == str(choice), (count, choice, result.stdout, result.stderr)
        else:
            assert result.returncode != 0 and "INVALID" in result.stderr, (result.stdout, result.stderr)
    (pack / "mascots.rgba").write_bytes(atlas + b"x")
    try:
        validate(pack)
    except ValueError:
        pass
    else:
        raise AssertionError("Mixed atlas/catalog accepted")
    (pack / "mascots.rgba").write_bytes(atlas)
for bad in (
    {"format": 1, "mascots": [one["mascots"][0]] * 17},
    {"format": 1, "mascots": [dict(one["mascots"][0], name="Off")]},
    {"format": 1, "mascots": [dict(one["mascots"][0], name="bad\nname")]},
    {"format": 1, "mascots": [dict(one["mascots"][0], facing="up")]},
    {"format": 1, "mascots": [one["mascots"][0]] * 2},
):
    config.write_text(json.dumps(bad))
    try:
        build(config, output / "invalid")
    except ValueError:
        pass
    else:
        raise AssertionError("Invalid pack accepted")
try:
    stage(output / "1", output / "16/fixture.jar", ROOT / "deploy/smartphone_integrator/carplay_settings.sh",
          output / "mixed.sh", output / "mixed.rgba")
except ValueError:
    pass
else:
    raise AssertionError("Mixed JAR/catalog accepted")
config.write_text(json.dumps(one))
subprocess.run([sys.executable, str(ROOT / "tools/build_mascot_assets.py"),
                "--raccoon", str(output / "art/robot.gif"), "--nian", str(output / "art/robot.gif"),
                "--capybara", str(output / "art/robot.gif"), "--lizard", str(output / "art/robot.gif"),
                "--output", str(output / "legacy.rgba")], check=True)
print("Generic packs: 0/1/5/16 entries, directions, generated validators, strict names/counts and mixed-pack rejection PASS")
