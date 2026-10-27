#!/bin/sh
# Explicit Green Menu performance-only recovery; no SD or MCD needed.
set -u
umask 077
[ "$#" -eq 0 ] || { echo "FAIL: select the performance recovery action without arguments"; exit 2; }
ROOT=""
if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
    ROOT=${ALTSCREEN_CHAIN_ROOT:-}
    case "$ROOT" in /work/build/*|/repo/build/*|/tmp/*|/var/tmp/*) ;; *) exit 2 ;; esac
fi
KIT="$ROOT/mnt/app/root/carplay-performance-rescue"
for file in carplay_binpatch carplay_performance.sh carplay_performance_recover.sh \
    train unit.key unit.key.cksum payload.cksum; do
    [ -f "$KIT/$file" ] && [ ! -L "$KIT/$file" ] || exit 1
done
payload=$(cd "$KIT" && cksum carplay_binpatch carplay_performance.sh carplay_performance_recover.sh \
    train unit.key unit.key.cksum) || exit 1
[ "$payload" = "$(cat "$KIT/payload.cksum")" ] || { echo "FAIL: internal recovery kit checksum"; exit 1; }
PERF_INTERNAL_RECOVERY=1
. "$KIT/carplay_performance.sh" || exit 1
perf_lock_exec "$KIT/carplay_performance_recover.sh" || { echo "FAIL: performance recovery busy or helper unavailable"; exit 1; }
perf_verify_kit && perf_decoder_alias || { echo "FAIL: internal recovery identity or decoder path"; exit 1; }
APP_RW=0; SYS_RW=0
ensure_dirs(){ for d in "$@"; do [ -d "$d" ] || mkdir -p "$d" || return 1; done; }
finish(){
    result=0
    sync || result=1
    if [ "${ALTSCREEN_CHAIN_TESTING:-0}" != 1 ]; then
        [ "$APP_RW" = 0 ] || mount -ur /mnt/app || result=1
        [ "$SYS_RW" = 0 ] || mount -ur /mnt/system || result=1
    fi
    return "$result"
}
trap 'rc=$?; if [ "$APP_RW" != 0 ] || [ "$SYS_RW" != 0 ]; then finish || rc=1; fi; exit "$rc"' 0
if [ "${ALTSCREEN_CHAIN_TESTING:-0}" != 1 ]; then
    mount -uw /mnt/app || exit 1
    APP_RW=1
    mount -uw /mnt/system || exit 1
    SYS_RW=1
fi
ensure_dirs "$KIT/recovery" || exit 1
TXN=""
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    candidate="$KIT/recovery/run_$$.$attempt"
    if mkdir "$candidate" 2>/dev/null; then TXN=$candidate; break; fi
done
[ -n "$TXN" ] || { echo "FAIL: cannot preserve recovery evidence"; exit 1; }
perf_collect_staging || { echo "FAIL: cannot preserve interrupted performance staging"; exit 1; }
# Verified originals may repair damaged live managed bytes. Keep the kit so an
# interrupted recovery can be run again; never stop the currently mapped daemon.
ALTSCREEN_RESCUE=1
perf_restore || { echo "PERFORMANCE_RECOVERY=FAIL originals_retained=YES retry_required=YES"; exit 1; }
finish || { echo "PERFORMANCE_RECOVERY=FAIL sync_or_remount=YES"; exit 1; }
APP_RW=0; SYS_RW=0
echo "PERFORMANCE_RECOVERY=PASS full_mmi_reboot_required=YES carplay_installation=UNCHANGED"
