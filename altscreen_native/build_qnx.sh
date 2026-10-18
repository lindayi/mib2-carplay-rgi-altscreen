#!/bin/bash
set -euo pipefail
export LC_ALL=C
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$ROOT/.." && pwd)/build/altscreen"
mkdir -p "$OUT/objects" "$OUT/stubs" "$OUT/scratch"
(
    cd "$ROOT/.."
    { find altscreen_native -type f -print0; printf '%s\0' scripts/build_altscreen.sh; } |
        sort -z | xargs -0 sha256sum
) > "$OUT/SOURCES.sha256"
export TMPDIR="$OUT/scratch"
export QNX_HOST=/opt/qnx650/host/linux/x86
export QNX_TARGET=/opt/qnx650/target/qnx6
export PATH="$QNX_HOST/usr/bin:$PATH"
CC=arm-unknown-nto-qnx6.5.0eabi-gcc
CXX=arm-unknown-nto-qnx6.5.0eabi-g++
# Host binutils can inspect ARM and avoid QNX binutils' 32-bit stat limitation.
RE=/usr/bin/readelf
NM=/usr/bin/nm
command -v clang >/dev/null
command -v ld.lld >/dev/null
TARGET=armv7-linux-gnueabi
CF=(-target "$TARGET" -march=armv7-a -marm -mfloat-abi=softfp -fPIC -O2
    -ffreestanding -fno-stack-protector -fno-builtin -nostdinc -Wall -Wextra -Werror
    -isystem "$(clang -print-file-name=include)" -I"$ROOT/hook" -I"$ROOT/hook/qnxshim")
echo 'int altscreen_import_anchor(void) { return 0; }' > "$OUT/stubs/empty.c"
clang "${CF[@]}" -c "$OUT/stubs/empty.c" -o "$OUT/stubs/empty.o"
for lib in libc.so.3 libm.so.2; do
    clang --target="$TARGET" -fuse-ld=lld -nostdlib -shared \
        -Wl,--build-id=none,-soname,"$lib",-z,max-page-size=4096 \
        "$OUT/stubs/empty.o" -o "$OUT/stubs/$lib"
done
OBJECTS=()
for source in altscreen_core altscreen_paths altscreen_profile altscreen_state \
    altscreen_state_private private111_direct_tap p1404_private111 \
    p1404_private111_backend p1404_firewall p1404_cockpit_native \
    p1404_setup_merge p1404_resolve p1404_iap2 p1404_observe \
    p1404_airplay_fullchain aug22_dynamic_reloc aug22_dynamic_diag altscreen_hook ui_session; do
    clang "${CF[@]}" -c "$ROOT/hook/$source.c" -o "$OUT/objects/$source.o"
    OBJECTS+=("$OUT/objects/$source.o")
done
clang -target "$TARGET" -march=armv7-a -marm -c "$ROOT/hook/p1404_trampoline.s" \
    -o "$OUT/objects/p1404_trampoline.o"
clang --target="$TARGET" -fuse-ld=lld -nostdlib -shared \
    -Wl,--build-id=none,--no-rosegment,-z,norelro,-z,max-page-size=4096 \
    -Wl,--hash-style=sysv,--no-as-needed,--allow-shlib-undefined \
    -L"$OUT/stubs" -Wl,-soname,libcarplay_altscreen.so \
    -Wl,--version-script,"$ROOT/hook/libcarplay_altscreen.map" \
    -Wl,-l:libc.so.3 -Wl,-l:libm.so.2 \
    "${OBJECTS[@]}" "$OUT/objects/p1404_trampoline.o" \
    -o "$OUT/libcarplay_altscreen.so"
# Upstream QNX loader contract: ARM EABI5 plus historical QNX e_flags bit 1.
printf '\002\000\000\005' | dd of="$OUT/libcarplay_altscreen.so" bs=1 seek=36 conv=notrunc status=none
python3 "$ROOT/hook/tools/validate_qnx_load_layout.py" \
    "$OUT/libcarplay_altscreen.so" > "$OUT/QNX_LOAD_LAYOUT.json"

cd "$ROOT/mirror"
SOURCES=(src/main.cpp src/private111_direct_source.cpp src/cluster_video_display.cpp
    src/mhi2q_backend.cpp src/gl_renderer.cpp src/state_snapshot.cpp src/startup_logo.cpp)
for spec in 'libEGL.so.1 egl[A-Z][A-Za-z0-9]+' 'libGLESv2.so.1 gl[A-Z][A-Za-z0-9]+'; do
    read -r lib pattern <<< "$spec"
    grep -hoE "\b$pattern" "${SOURCES[@]}" | sort -u |
        sed 's/.*/int &(){return 0;}/' > "$OUT/stubs/$lib.c"
    $CC -shared -fPIC -Wl,-soname,"$lib" "$OUT/stubs/$lib.c" -o "$OUT/stubs/$lib"
done
# No C++ standard library is needed: imported classes use only C allocation/APIs.
$CC -O2 -Wall -Wextra -march=armv7-a -mfloat-abi=softfp \
    -fno-exceptions -fno-rtti -fno-threadsafe-statics -Isrc -I../hook \
    "${SOURCES[@]}" -Wl,--allow-shlib-undefined \
    -L"$OUT/stubs" -l:libEGL.so.1 -l:libGLESv2.so.1 -lm -lsocket \
    -o "$OUT/carplay-alt111-mirror-display"
python3 "$ROOT/tests/check_elf.py" "$OUT" "$ROOT/hook/libcarplay_altscreen.map" \
    > "$OUT/ELF_CHECKS.txt"
cat "$OUT/ELF_CHECKS.txt"
cd "$OUT"
sha256sum libcarplay_altscreen.so carplay-alt111-mirror-display > SHA256SUMS
