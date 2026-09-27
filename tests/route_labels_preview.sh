#!/bin/bash
set -euo pipefail
export PATH=/usr/bin:/bin:/usr/sbin:/sbin
cd /src
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p build/route-label-previews
COMMON="-DPLATFORM_QNX -Imaneuver_render -Icommon -O1 -g -Wall -Wextra -ffunction-sections -fdata-sections"
cc $COMMON -fsanitize=address,undefined tests/route_labels_test.c maneuver_render/route_labels.c \
    -Wl,--gc-sections -lGLESv2 -lm -o "$OUT/unit"
"$OUT/unit"
for name in scene geometry layout lane_panel; do
    c++ $COMMON -std=c++11 -fno-rtti -fno-exceptions -c "maneuver_render/scene/$name.cpp" -o "$OUT/$name.o"
done
cc $COMMON tests/route_labels_preview.c maneuver_render/render.c maneuver_render/route_labels.c \
    maneuver_render/maneuver.c maneuver_render/route_path.c "$OUT/"*.o \
    -Wl,--gc-sections -lEGL -lGLESv2 -lm -o "$OUT/preview"
"$OUT/preview"
python3 -c 'from pathlib import Path; from PIL import Image; [Image.open(p).save(p.with_suffix(".png")) for p in Path("build/route-label-previews").glob("*.ppm")]'
