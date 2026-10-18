#!/bin/bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
S="$ROOT/altscreen/Toolbox/scripts"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
export ALTSCREEN_CHAIN_TESTING=1 ALTSCREEN_CHAIN_ROOT="$T"
H="$T/mnt/app/root/hooks"
P="$T/mnt/persist/var/app/carplay_altscreen"
mkdir -p "$H" "$P"
printf 'small_dx=-476\n' > "$H/cluster_shift.cfg"
printf 'view 0 0 1140 542\n' > "$H/cluster_viewarea.cfg"
printf 'maps:/car/instrumentcluster/map\n' > "$H/cluster_ui.url"
cp "$ROOT/tests/fixtures/carplay-preferences.txt" "$P/preferences"
before=$(find "$T" -type f -exec sha256sum {} \; | sort)
for entry in cluster_area_w1200 cluster_area_w1140 cluster_area_w1080 cluster_area_full \
    cluster_shift_sporttest cluster_shift_off cluster_layout_top cluster_layout_right \
    cluster_layout_noeta cluster_layout_default; do
    if output=$(sh "$S/$entry.sh" 2>&1); then
        echo "FAIL: obsolete entry reported success: $entry" >&2; exit 1
    fi
    case "$output" in *"FAIL: legacy GEM"*"were not changed"*) ;; *)
        echo "FAIL: missing explicit migration guidance: $entry" >&2; exit 1 ;;
    esac
done
after=$(find "$T" -type f -exec sha256sum {} \; | sort)
[ "$before" = "$after" ] || { echo "FAIL: obsolete controls mutated files" >&2; exit 1; }
GEM="$ROOT/altscreen/Toolbox/GEM/mqb-carplayAltScreen.esd"
! grep -Eq 'cluster_(layout|shift|area)|START \(alias\)' "$GEM" \
    || { echo "FAIL: obsolete GEM action remains" >&2; exit 1; }
[ "$(grep -c 'label   "START"' "$GEM")" = 1 ]
echo "cluster_shift_test: obsolete GEM controls fail explicitly; files remain unchanged; one START entry PASS"
