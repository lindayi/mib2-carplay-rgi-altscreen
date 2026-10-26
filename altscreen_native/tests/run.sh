#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "$ROOT/.." && pwd)/build/altscreen-host-tests"
mkdir -p "$OUT/scratch"
export TMPDIR="$OUT/scratch"
cd "$OUT"
export PATH=/usr/bin:/bin:$PATH
command -v g++ >/dev/null || {
    echo "Host C++/GLES test image required; use altscreen_native/tests/run_docker.sh" >&2
    exit 1
}
cc -D_GNU_SOURCE -O1 -g -fsanitize=address,undefined -ffunction-sections -fdata-sections "$ROOT/tests/ownership_test.c" \
    "$ROOT/hook/ui_session.c" -Wl,--gc-sections -ldl -pthread -o ownership_test
./ownership_test
cc -D_GNU_SOURCE -O1 -g -fsanitize=address,undefined "$ROOT/tests/hmi_contract_test.c" \
    -o hmi_contract_test
./hmi_contract_test
cc -D_GNU_SOURCE -O1 -g -Wall -Wextra -Werror "$ROOT/tests/firewall_runtime_test.c" \
    "$ROOT/hook/p1404_firewall.c" -ldl -pthread -o pfctl
./pfctl
cc -D_GNU_SOURCE -O1 -g -fsanitize=address,undefined -ffunction-sections -fdata-sections "$ROOT/tests/recovery_test.c" \
    -Wl,--gc-sections -ldl -pthread -o recovery_test
./recovery_test
cc -std=gnu99 -O1 -Wall -Wextra -Werror -shared -fPIC \
    "$ROOT/../tests/alt_cluster_fake_airplay.c" -o libfakeairplay.so
cc -D_GNU_SOURCE -std=gnu99 -O1 -Wall -Wextra -Werror -DENABLE_LOGGING=0 -I"$ROOT/../Toolbox/carplay_alt_screen/rgi_native/src" \
    "$ROOT/../tests/alt_cluster_test.c" "$ROOT/../Toolbox/carplay_alt_screen/rgi_native/src/altcluster/alt_cluster.c" \
    -L. -lfakeairplay -Wl,-rpath,'$ORIGIN' -lpthread -ldl -o alt_cluster_test
./alt_cluster_test
cc -std=gnu99 -O1 -Wall -Wextra -Werror -shared -fPIC \
    "$ROOT/../tests/alt_viewarea_fake_airplay.c" -o libfakeairplay_va.so
cc -std=gnu99 -O1 -Wall -Wextra -Werror -DENABLE_LOGGING=0 -I"$ROOT/../Toolbox/carplay_alt_screen/rgi_native/src" \
    "$ROOT/../tests/alt_viewarea_test.c" "$ROOT/../Toolbox/carplay_alt_screen/rgi_native/src/altcluster/alt_viewarea.c" \
    -L. -lfakeairplay_va -Wl,-rpath,'$ORIGIN' -ldl -o alt_viewarea_test
./alt_viewarea_test ./libfakeairplay.so
cc -D_GNU_SOURCE -O2 -Wall -Wextra "$ROOT/tests/installed_paths_test.c" -o installed_paths_test
./installed_paths_test
cc -D_GNU_SOURCE -O2 -Wall -Wextra "$ROOT/tests/ui_session_test.c" \
    "$ROOT/hook/ui_session.c" -o ui_session_test
./ui_session_test
python3 "$ROOT/tests/main110_lifecycle_test.py" "$OUT"
g++ -O2 -Wall -Wextra "$ROOT/tests/frame_source_test.cpp" -I"$ROOT/hook" -o frame_source_test
./frame_source_test
g++ -O2 -Wall -Wextra "$ROOT/tests/snapshot_test.cpp" -o snapshot_test
./snapshot_test
g++ -O2 -Wall -Wextra -ffunction-sections -fdata-sections -I"$ROOT/hook" \
    "$ROOT/tests/placement_state_test.cpp" "$ROOT/mirror/src/state_snapshot.cpp" \
    -Wl,--gc-sections -o placement_state_test
./placement_state_test
g++ -O2 -Wall -Wextra -ffunction-sections -fdata-sections -I"$ROOT/hook" "$ROOT/tests/renderer_test.cpp" \
    "$ROOT/mirror/src/gl_renderer.cpp" "$ROOT/mirror/src/cluster_video_display.cpp" \
    "$ROOT/mirror/src/state_snapshot.cpp" -Wl,--gc-sections -lEGL -lGLESv2 -o renderer_test
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 ./renderer_test
echo ALTSCREEN_HOST_TESTS=PASS
