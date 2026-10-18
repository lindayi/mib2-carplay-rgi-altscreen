#!/bin/bash
# Independent PolyForm hook/sidecar only: never link RGI or mascot objects here.
# License: altscreen_native/LICENSE.POLYFORM-NONCOMMERCIAL-1.0.0.md plus
# altscreen_native/LICENSE.ADDITIONAL-TERMS.md; see altscreen_native/NOTICE.md.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
[ "$#" -eq 0 ] || { echo "usage: scripts/build_altscreen.sh"; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${ALTSCREEN_BUILD_IMAGE:-mib2-altscreen-native:local}"
command docker image inspect "$IMAGE" >/dev/null 2>&1 || {
    echo "Missing $IMAGE. Build with: docker build -t $IMAGE -f altscreen_native/Dockerfile altscreen_native" >&2
    exit 1
}
# QNX 32-bit binutils cannot stat Windows bind-mount inode numbers.
VOLUME="carplay-altscreen-build-$$-$(date +%s)"
command docker volume create "$VOLUME" >/dev/null
trap 'command docker volume rm "$VOLUME" >/dev/null' EXIT
tar -C "$ROOT" -cf - altscreen_native scripts/build_altscreen.sh |
    command docker run --rm -i -v "$VOLUME":/src "$IMAGE" \
    bash -c 'mkdir -p /src; tar -xf - -C /src'
command docker run --rm -v "$VOLUME":/src "$IMAGE" \
    bash /src/altscreen_native/build_qnx.sh
mkdir -p "$ROOT/build/altscreen"
command docker run --rm -v "$VOLUME":/src "$IMAGE" \
    tar -C /src/build/altscreen -cf - \
    libcarplay_altscreen.so carplay-alt111-mirror-display \
    QNX_LOAD_LAYOUT.json ELF_CHECKS.txt SHA256SUMS SOURCES.sha256 |
    tar -xf - -C "$ROOT/build/altscreen"
echo "Source-built native outputs: build/altscreen (no SD staging)"
