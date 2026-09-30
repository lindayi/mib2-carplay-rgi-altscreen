#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT"
python3 tests/mascot_pack_test.py
ATLAS="${MASCOT_TEST_ATLAS:-$PROJECT/build/mascot-pack-tests/legacy.rgba}"
mkdir -p build/mascot-tests
test -f /.dockerenv || { echo "Mascot interposition fixtures require Docker" >&2; exit 1; }
mkdir -p /ramdisk
cc -shared -fPIC tests/qnx_tmp_contract.c -ldl -o build/mascot-tests/qnx-tmp-contract.so
python3 tests/mascot_assets_test.py
python3 tests/mascot_video_test.py
cc -std=gnu99 -Wall -Wextra -Werror -fsanitize=address,undefined \
    tests/mascot_pack_test.c mascot/assets.c -o build/mascot-tests/pack
for count in 0 1 5 16; do
    build/mascot-tests/pack "build/mascot-pack-tests/$count/mascots.rgba" "$count"
done
cc -std=gnu99 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
    tests/mascot_test.c mascot/assets.c mascot/draw.c \
    -Wl,--wrap=glCreateShader -Wl,--wrap=glTexImage2D \
    -lEGL -lGLESv2 -lm -o build/mascot-tests/test
ASAN_OPTIONS=detect_leaks=0 EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    build/mascot-tests/test "$ATLAS" build/mascot-tests
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror -shared -fPIC -fvisibility=hidden \
    mascot/assets.c mascot/draw.c mascot/hook.c vc_menu/panel.c vc_menu/protocol.c vc_menu/runtime.c -lEGL -lGLESv2 -lm -ldl -pthread \
    -o build/mascot-tests/libcarplay_mascot.so
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror -shared -fPIC \
    tests/mascot_swap_observer.c -lEGL -ldl -o build/mascot-tests/libobserver.so
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror \
    tests/mascot_interpose_test.c -lEGL -lGLESv2 -ldl -o build/mascot-tests/interpose
for scenario in normal missing status-open status-rename; do
    EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
        LD_PRELOAD="$PROJECT/build/mascot-tests/qnx-tmp-contract.so:$PROJECT/build/mascot-tests/libcarplay_mascot.so:$PROJECT/build/mascot-tests/libobserver.so" \
        build/mascot-tests/interpose "$ATLAS" "$scenario" \
        2>"build/mascot-tests/$scenario.log"
    case "$scenario" in
        status-open) grep -q 'STATUS_WRITE_ERROR stage=open errno=2' "build/mascot-tests/$scenario.log";;
        status-rename) grep -q 'STATUS_WRITE_ERROR stage=rename errno=38' "build/mascot-tests/$scenario.log";;
        *) ! grep -q 'STATUS_WRITE_ERROR' "build/mascot-tests/$scenario.log";;
    esac
    case "$scenario" in status-*)
        test "$(grep -c STATUS_WRITE_ERROR "build/mascot-tests/$scenario.log")" -eq 1;;
    esac
done
cc -D_GNU_SOURCE -std=gnu99 -O2 -Wall -Wextra -Werror \
    tests/vc_panel_interpose_test.c -lEGL -lGLESv2 -ldl -o build/mascot-tests/vc-panel-interpose
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 \
    CARPLAY_MASCOT_ATLAS="$PROJECT/build/mascot-pack-tests/16/mascots.rgba" \
    LD_PRELOAD="$PROJECT/build/mascot-tests/qnx-tmp-contract.so:$PROJECT/build/mascot-tests/libcarplay_mascot.so:$PROJECT/build/mascot-tests/libobserver.so" \
    build/mascot-tests/vc-panel-interpose build/mascot-tests/vc-panel-live.ppm build/mascot-tests/vc-panel-preview.ppm
python3 - <<'PY'
from pathlib import Path
from PIL import Image
for path in Path("build/mascot-tests").glob("*.ppm"):
    Image.open(path).save(path.with_suffix(".png"))
PY
