#!/bin/bash
#
# AltScreen + RGI SD package, end to end, in the AltScreen scripts' own testing mode:
# INSTALL -> START -> STATUS -> RESTORE ORIGINAL against a fake head-unit root.
#
#   FIXTURE=<card>/MMI-Cockpit-Carplay/backup ./scripts/test_altscreen_e2e.sh
#
# FIXTURE is an AltScreen stock backup from a real unit (ORIGINAL/files, firewall-original,
# boot-diagnostics); it holds that unit's dio_manager, libairplay, JSON configs and
# startup.sh, so it stays outside the repo. The package is build/sd (./scripts/build_sd.sh).
#
# Runs in Linux with mksh as /bin/sh: QNX /bin/sh is pdksh (dash rejects the stock
# startup.sh), and on macOS the /tmp symlink breaks AltScreen's runtime-forward check.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SD_DIR="${SD_DIR:-$PROJECT_DIR/build/sd}"
FIXTURE="${FIXTURE:?set FIXTURE=<card>/MMI-Cockpit-Carplay/backup}"
[ -f "$SD_DIR/SHA256SUMS-SD.txt" ] || { echo "ERROR: no package in $SD_DIR; run ./scripts/build_sd.sh"; exit 1; }
[ -d "$FIXTURE/ORIGINAL/files" ] || { echo "ERROR: $FIXTURE has no ORIGINAL/files"; exit 1; }

docker run --rm -v "$SD_DIR":/sd:ro -v "$FIXTURE":/fixture:ro \
    -v "$PROJECT_DIR/tests":/tests:ro eclipse-temurin:8-jdk-jammy bash -c '
set -e
apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq mksh >/dev/null 2>&1 || { echo "ERROR: cannot install mksh"; exit 1; }
ln -sf /bin/mksh /bin/sh
exec bash /tests/altscreen_e2e.sh
'
