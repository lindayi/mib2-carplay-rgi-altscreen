#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT"
test -s build/mascot-assets/mascots.rgba || {
    echo "ERROR: generate build/mascot-assets/mascots.rgba with tools/build_mascot_assets.py first" >&2
    exit 1
}
mkdir -p build/mascot-tests
python3 tests/mascot_assets_test.py
cc -std=gnu99 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
    tests/mascot_test.c mascot/assets.c mascot/draw.c \
    -Wl,--wrap=glCreateShader -Wl,--wrap=glTexImage2D \
    -lEGL -lGLESv2 -lm -o build/mascot-tests/test
ASAN_OPTIONS=detect_leaks=0 EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    build/mascot-tests/test build/mascot-assets/mascots.rgba build/mascot-tests
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror -shared -fPIC -fvisibility=hidden \
    mascot/assets.c mascot/draw.c mascot/hook.c -lEGL -lGLESv2 -lm -ldl -pthread \
    -o build/mascot-tests/libcarplay_mascot.so
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror -shared -fPIC \
    tests/mascot_swap_observer.c -lEGL -ldl -o build/mascot-tests/libobserver.so
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror \
    tests/mascot_interpose_test.c -lEGL -lGLESv2 -ldl -o build/mascot-tests/interpose
for scenario in normal missing; do
    EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
        LD_PRELOAD="$PROJECT/build/mascot-tests/libcarplay_mascot.so:$PROJECT/build/mascot-tests/libobserver.so" \
        build/mascot-tests/interpose "$PROJECT/build/mascot-assets/mascots.rgba" "$scenario"
done
python3 - <<'PY'
from pathlib import Path
from PIL import Image
for path in Path("build/mascot-tests").glob("*.ppm"):
    Image.open(path).save(path.with_suffix(".png"))
PY
