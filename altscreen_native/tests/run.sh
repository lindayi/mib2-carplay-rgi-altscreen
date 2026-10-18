#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "$ROOT/.." && pwd)/build/altscreen-host-tests"
mkdir -p "$OUT/scratch"
export TMPDIR="$OUT/scratch"
cd "$OUT"
export PATH=/usr/bin:/bin:$PATH
cc -D_GNU_SOURCE -O2 -ffunction-sections -fdata-sections "$ROOT/tests/ownership_test.c" \
    -Wl,--gc-sections -ldl -pthread -o ownership_test
./ownership_test
cc -D_GNU_SOURCE -O2 -Wall -Wextra "$ROOT/tests/installed_paths_test.c" -o installed_paths_test
./installed_paths_test
cc -D_GNU_SOURCE -O2 -Wall -Wextra "$ROOT/tests/ui_session_test.c" \
    "$ROOT/hook/ui_session.c" -o ui_session_test
./ui_session_test
g++ -O2 -Wall -Wextra "$ROOT/tests/frame_source_test.cpp" -I"$ROOT/hook" -o frame_source_test
./frame_source_test
g++ -O2 -Wall -Wextra "$ROOT/tests/snapshot_test.cpp" -o snapshot_test
./snapshot_test
g++ -O2 -Wall -Wextra "$ROOT/tests/renderer_test.cpp" \
    "$ROOT/mirror/src/gl_renderer.cpp" "$ROOT/mirror/src/cluster_video_display.cpp" \
    -lEGL -lGLESv2 -o renderer_test
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 ./renderer_test
echo ALTSCREEN_HOST_TESTS=PASS
