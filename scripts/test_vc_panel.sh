#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT"
mkdir -p build/vc-panel-previews
cc -std=gnu99 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
    tests/vc_panel_test.c vc_menu/panel.c -o build/vc-panel-previews/test
ASAN_OPTIONS=detect_leaks=1 build/vc-panel-previews/test build/vc-panel-previews
python3 - <<'PY'
from pathlib import Path
from PIL import Image
for path in Path("build/vc-panel-previews").glob("*.ppm"):
    Image.open(path).save(path.with_suffix(".png"))
PY
