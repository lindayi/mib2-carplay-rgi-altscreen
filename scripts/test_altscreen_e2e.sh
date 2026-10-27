#!/bin/bash
#
# AltScreen + RGI SD package, end to end, in the AltScreen scripts' own testing mode:
# One-step INSTALL -> compatible START -> RESTORE/RESCUE against a fixture.
#
#   FIXTURE=<card>/MMI-Cockpit-Carplay/backup ./scripts/test_altscreen_e2e.sh
#
# FIXTURE is an AltScreen stock backup from a real unit (ORIGINAL/files, firewall-original,
# boot-diagnostics); it holds that unit's dio_manager, libairplay, JSON configs and
# startup.sh, so it stays outside the repo. The package is build/sd (./scripts/build_sd.sh).
#
# INSTALLER_PACKAGE_MODE=staged requires explicit SD_DIR and tests the packaged
# scripts/pins unchanged. The default source-overlay mode is for development.
# The runner requires mksh; host shell coverage does not run ARM/QNX.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [ "${INSTALLER_PACKAGE_MODE:-source-overlay}" = staged ]; then
    : "${SD_DIR:?staged mode requires an explicit SD_DIR}"
fi
export SD_DIR="${SD_DIR:-$PROJECT_DIR/build/sd}"
exec bash "$PROJECT_DIR/scripts/test_installer_transactions.sh"
