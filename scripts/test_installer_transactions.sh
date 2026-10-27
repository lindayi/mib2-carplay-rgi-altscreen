#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALLER_PACKAGE_MODE="${INSTALLER_PACKAGE_MODE:-source-overlay}"
case "$INSTALLER_PACKAGE_MODE" in
    source-overlay) ;;
    staged) : "${SD_DIR:?staged mode requires an explicit SD_DIR}" ;;
    *) echo "ERROR: INSTALLER_PACKAGE_MODE must be source-overlay or staged"; exit 2 ;;
esac
SD_DIR="${SD_DIR:-$PROJECT/build/upstream-sd}"
FIXTURE="${FIXTURE:?set FIXTURE to the private exact-stock backup directory}"
TEST_IMAGE="${TEST_IMAGE:-mib2-installer-test:local}"
test -f "$SD_DIR/Toolbox/carplay_alt_screen/hmi/carplay_hook-basevideo3.jar"
test -d "$FIXTURE/ORIGINAL/files"
PERFORMANCE_FIXTURE="${PERFORMANCE_FIXTURE:?set PERFORMANCE_FIXTURE to the private exported mcd/config directory}"
test -f "$PERFORMANCE_FIXTURE/mcd" && test -f "$PERFORMANCE_FIXTURE/mcd.conf"
docker run --rm -e INSTALLER_CASES="${INSTALLER_CASES:-all}" \
    -e INSTALLER_PACKAGE_MODE="$INSTALLER_PACKAGE_MODE" \
    -v "$PROJECT":/work -v "$SD_DIR":/sd:ro -v "$FIXTURE":/fixture:ro -v "$PERFORMANCE_FIXTURE":/performance:ro \
    --entrypoint bash "$TEST_IMAGE" -c '
set -e
# Require the nearest available ksh-family interpreter; never silently test Bash.
command -v mksh >/dev/null || { echo "ERROR: mksh required; build tests/installer/Dockerfile"; exit 1; }
ln -sf "$(command -v mksh)" /bin/sh
echo TEST_SHELL=mksh
case "$INSTALLER_CASES" in
    all)
        bash /work/tests/installer_transaction_test.sh
        bash /work/tests/mirror_health_test.sh /sd/Toolbox/carplay_alt_screen/mirror_display/release ;;
    rescue|performance) bash /work/tests/installer_transaction_test.sh ;;
    mirror)
        if [ "$INSTALLER_PACKAGE_MODE" = staged ]; then
            (cd /sd && sha256sum --check --quiet SHA256SUMS-SD.txt)
        fi
        bash /work/tests/mirror_health_test.sh /sd/Toolbox/carplay_alt_screen/mirror_display/release ;;
    *) echo "ERROR: INSTALLER_CASES must be all, rescue, performance or mirror"; exit 2 ;;
esac
'
