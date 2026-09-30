#!/bin/bash
set -euo pipefail
export PATH=/usr/bin:$PATH
cd "$(dirname "$0")/.."
test -f /.dockerenv || { echo "Map-card fixed-path fixtures require Docker" >&2; exit 1; }
mkdir -p build/map-card-tests /ramdisk /var/app/icab/tmp/37
python3 - <<'PY'
from pathlib import Path
from PIL import Image, ImageDraw
image = Image.new("RGBA", (64, 64), "#385c78")
draw = ImageDraw.Draw(image)
draw.ellipse((12, 12, 52, 52), fill="#e5be64")
draw.ellipse((26, 26, 38, 38), fill="#385c78")
image.save("/var/app/icab/tmp/37/coverart.png")
Image.new("RGB", (513, 1)).save("build/map-card-tests/oversized.png")
Path("build/map-card-tests/huge.png").write_bytes(b"x" * (1024 * 1024 + 1))
PY
cc -D_GNU_SOURCE -std=gnu99 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
    map_cards/test.c map_cards/protocol.c map_cards/paint.c map_cards/artwork.c map_cards/runtime.c \
    mascot/draw.c mascot/assets.c \
    -Wl,--wrap=glTexImage2D -Wl,--wrap=glGetIntegerv -Wl,--wrap=clock_gettime \
    -Wl,--wrap=gettimeofday -Wl,--wrap=read \
    -lEGL -lGLESv2 -lm -pthread -o build/map-card-tests/test
ASAN_OPTIONS=detect_leaks=0 EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    build/map-card-tests/test
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror -shared -fPIC -fvisibility=hidden \
    mascot/assets.c mascot/draw.c mascot/hook.c vc_menu/panel.c vc_menu/protocol.c vc_menu/runtime.c \
    map_cards/protocol.c map_cards/paint.c map_cards/artwork.c map_cards/runtime.c \
    -lEGL -lGLESv2 -lm -ldl -pthread -o build/map-card-tests/libcarplay_mascot.so
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror -shared -fPIC \
    tests/mascot_swap_observer.c -lEGL -ldl -o build/map-card-tests/libobserver.so
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror map_cards/interpose_test.c \
    map_cards/paint.c map_cards/protocol.c \
    -lEGL -lGLESv2 -ldl -lm -o build/map-card-tests/interpose
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    LD_PRELOAD="$PWD/build/map-card-tests/libcarplay_mascot.so:$PWD/build/map-card-tests/libobserver.so" \
    build/map-card-tests/interpose
python3 - <<'PY'
from pathlib import Path
from PIL import Image
for path in Path("build/map-card-tests").glob("*.ppm"):
    Image.open(path).save(path.with_suffix(".png"))
PY
