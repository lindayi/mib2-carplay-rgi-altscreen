#!/bin/bash
set -euo pipefail
export PATH=/usr/bin:/bin:/usr/sbin:/sbin
cd /src
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p build/route-label-previews
COMMON="-DPLATFORM_QNX -IToolbox/carplay_alt_screen/rgi_renderer/src -IToolbox/carplay_alt_screen/rgi_renderer/common -O1 -g -Wall -Wextra -ffunction-sections -fdata-sections"
cc $COMMON -fsanitize=address,undefined tests/route_labels_test.c Toolbox/carplay_alt_screen/rgi_renderer/src/route_labels.c \
    -Wl,--gc-sections -lGLESv2 -lm -o "$OUT/unit"
"$OUT/unit"
for name in scene geometry layout lane_panel; do
    c++ $COMMON -std=c++11 -fno-rtti -fno-exceptions -c "Toolbox/carplay_alt_screen/rgi_renderer/src/scene/$name.cpp" -o "$OUT/$name.o"
done
cc $COMMON tests/route_labels_preview.c Toolbox/carplay_alt_screen/rgi_renderer/src/render.c Toolbox/carplay_alt_screen/rgi_renderer/src/route_labels.c \
    Toolbox/carplay_alt_screen/rgi_renderer/src/maneuver.c Toolbox/carplay_alt_screen/rgi_renderer/src/route_path.c "$OUT/"*.o \
    -Wl,--gc-sections -lEGL -lGLESv2 -lm -o "$OUT/preview"
"$OUT/preview"
python3 -c 'from pathlib import Path; from PIL import Image; [Image.open(p).save(p.with_suffix(".png")) for p in Path("build/route-label-previews").glob("*.ppm")]'
