#!/bin/bash
# Atomic publication fixtures need a native Linux filesystem, not a Windows bind mount.
set -euo pipefail
[ "$#" -eq 0 ] || { echo "usage: altscreen_native/tests/run_docker.sh" >&2; exit 2; }
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HOST_ROOT="$ROOT"
case "$(uname -s)" in MINGW*|MSYS*) HOST_ROOT="$(cygpath -w "$ROOT")";; esac
IMAGE="${ALTSCREEN_TEST_IMAGE:-mib2-mascot-video-test}"
command docker run --rm \
    --mount "type=bind,source=$HOST_ROOT,target=/src,readonly" \
    --mount type=volume,target=/src/build/altscreen-host-tests \
    -w /src "$IMAGE" bash /src/altscreen_native/tests/run.sh
