#!/bin/bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
docker build -q -t mib2-route-label-test - < "$PROJECT_DIR/tools/route-label-test.Dockerfile"
docker run --rm -v "$PROJECT_DIR":/src mib2-route-label-test bash /src/tests/route_labels_preview.sh
