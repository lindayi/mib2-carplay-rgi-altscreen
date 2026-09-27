#!/bin/sh
# Read-only head-unit snapshot. Writes only a new directory on the selected SD.
set -u
PATH=${PATH:-/bin:/usr/bin}:/proc/boot:/armle/bin:/bin:/usr/bin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
umask 077
[ "$#" -eq 0 ] || { echo "Usage: export_mmi_cockpit_diagnostics.sh"; exit 2; }
ROOT=""; VOLUME=""
if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
    ROOT=${ALTSCREEN_CHAIN_ROOT:-}
    VOLUME=${ALTSCREEN_CHAIN_VOLUME:-}
    case "$ROOT" in /tmp/*|/var/tmp/*) ;; *) echo "FAIL: invalid test root"; exit 2 ;; esac
    case "$VOLUME" in /tmp/*|/var/tmp/*) ;; *) echo "FAIL: invalid test SD"; exit 2 ;; esac
else
    for candidate in /net/mmx/fs/sda0 /net/mmx/fs/sda1 /net/mmx/fs/sdb0 /net/mmx/fs/sdb1 /fs/sda0 /fs/sda1 /fs/sdb0 /fs/sdb1; do
        if [ -f "$candidate/Toolbox/scripts/export_mmi_cockpit_diagnostics.sh" ]; then
            VOLUME=$candidate; break
        fi
    done
fi
[ -n "$VOLUME" ] && [ -d "$VOLUME" ] || { echo "FAIL: matching SD card not found"; exit 1; }
BASE="$VOLUME/MMI-Cockpit-Carplay/logs/exports"
[ -d "$BASE" ] || mkdir -p "$BASE" || { echo "FAIL: cannot create export directory (SD may be read-only)"; exit 1; }
STAMP=$(date +%Y%m%d_%H%M%S) || { echo "FAIL: cannot read clock"; exit 1; }
OUT="$BASE/export_${STAMP}_$$"
mkdir "$OUT" || { echo "FAIL: export destination exists or is not writable"; exit 1; }
mkdir "$OUT/private" || { echo "FAIL: cannot create private-log directory"; exit 1; }
ERRORS=0; COPIED=0; MISSING=0
STATE="$ROOT/mnt/app/root/carplay-altscreen/state"
JAR="$ROOT/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar"
SD_JAR="$VOLUME/Toolbox/carplay_alt_screen/hmi/carplay_hook-basevideo3.jar"

marker(){
    if [ -e "$2" ]; then echo "$1=PRESENT"; else echo "$1=ABSENT"; fi
}
pid_state(){
    label=$1; file=$2; pid=""
    [ ! -r "$file" ] || read -r pid < "$file"
    case "$pid" in ''|*[!0-9]*|0|1) echo "$label=UNKNOWN"; return ;; esac
    if kill -0 "$pid" 2>/dev/null; then
        echo "$label=PID_EXISTS_IDENTITY_NOT_PROBED"
    else
        echo "$label=NOT_RUNNING"
    fi
}
summary(){
    echo "MMI-Cockpit-Carplay export-only diagnostic snapshot"
    echo "EXPORT_VERSION=1"
    echo "CAPTURED_AT=$STAMP"
    echo "HEAD_UNIT_MUTATION=NONE"
    echo "PIXEL_OUTPUT=NOT_VERIFIED"
    train=""
    for file in "$ROOT/dev/shmem/version.txt" "$ROOT/net/rcc/dev/shmem/version.txt" "$ROOT/net/mmx/dev/shmem/version.txt"; do
        [ -r "$file" ] || continue
        train=$(sed -n 's/.*\(MHI2Q_[A-Za-z0-9_]*\).*/\1/p' "$file" | head -n 1)
        [ -z "$train" ] || break
    done
    echo "FIRMWARE_TRAIN=${train:-UNKNOWN}"
    marker RUNTIME_TRANSACTION "$STATE/transaction.pending"
    marker START_TRANSACTION "$STATE/start.pending"
    marker MIRROR_ENABLED "$STATE/basevideo3.enabled"
    marker VIDEO_DEMAND "$ROOT/tmp/mmi-mirror-active"
    marker FIRST_PRESENT_MARKER "$ROOT/tmp/mmi-mirror-basevideo.ready"
    marker EXPLICIT_STOP "$ROOT/tmp/MMI-Cockpit-Carplay.mirror.stop.requested"
    marker RGI_HOOK "$ROOT/mnt/app/root/hooks/libcarplay_hook.so"
    pid_state MIRROR "$ROOT/tmp/MMI-Cockpit-Carplay.mirror.pid"
    pid_state MANEUVER_RENDERER "$ROOT/tmp/carplay_maneuver_render.pid"
    if [ -r "$ROOT/tmp/carplay_cluster.ctx" ]; then
        awk '/^(ctx|video|nav|time_ms)=[0-9]+$/' "$ROOT/tmp/carplay_cluster.ctx"
    else echo "CLUSTER_CONTEXT=UNKNOWN"; fi
    if [ -r "$ROOT/tmp/MMI-Cockpit-Carplay.mirror.health" ]; then
        sed -n '/^HEALTH_[A-Z_]*=[A-Za-z0-9_]*$/p' "$ROOT/tmp/MMI-Cockpit-Carplay.mirror.health"
    else echo "HEALTH_STATE=UNKNOWN"; fi
    if [ -r "$ROOT/tmp/MMI-Cockpit-Carplay.mirror.log" ]; then
        tail -c 65536 "$ROOT/tmp/MMI-Cockpit-Carplay.mirror.log" | awk '
          /PHASE=RUN / {
            report=""
            for(i=1;i<=NF;i++) {
              if($i ~ /^(present_fps|presented_frames|decoded_frames|h264_packets)=[0-9]+([.][0-9]+)?$/) {
                split($i,kv,"=")
                report=report "LAST_MIRROR_" toupper(kv[1]) "=" kv[2] "\n"
              }
            }
            if(report!="") last=report
          }
          END { if(last!="") printf "%s",last; else print "LAST_MIRROR_REPORT=UNKNOWN" }
        '
    else echo "LAST_MIRROR_REPORT=UNKNOWN"; fi
    if [ -s "$JAR" ] && [ -s "$SD_JAR" ]; then
        installed=$(cksum < "$JAR") || return 1
        supplied=$(cksum < "$SD_JAR") || return 1
        if [ "$installed" = "$supplied" ]; then echo "JAR_MATCHES_CARD=YES"; else echo "JAR_MATCHES_CARD=NO"; fi
        echo "INSTALLED_JAR_CKSUM_SIZE=$installed"
        echo "CARD_JAR_CKSUM_SIZE=$supplied"
    else echo "JAR_MATCHES_CARD=UNKNOWN"; fi
}
summary > "$OUT/SUMMARY.txt" || { echo "FAIL: cannot write complete summary"; exit 1; }
cat > "$OUT/PRIVACY.txt" <<'PRIVACY'
This export does not stop, restart, restore, uninstall, mount or change the head unit.
SUMMARY.txt contains selected operational fields, not a guarantee of live pixels.
private/ contains bounded raw log tails. They may include road names, destinations,
device identifiers, network details and protocol data. They are NOT anonymized.
Keep this directory local; review and redact it before sharing.
No firmware dumps, configuration files or video payloads are copied.
Missing logs are reported explicitly and may be normal for a quiet or new session.
PRIVACY
[ "$?" -eq 0 ] || { echo "FAIL: cannot write privacy notice"; exit 1; }
: > "$OUT/FILES.txt" || { echo "FAIL: cannot write file inventory"; exit 1; }
for name in carplay_java.log carplay_hook.log maneuver_render.log carplay_wrapper.log \
    MMI-Cockpit-Carplay.altscreen_hook.log altscreen_hook.log CinemoDioManager.log \
    MMI-Cockpit-Carplay.mirror.log MMI-Cockpit-Carplay.mirror.autorestart.log \
    MMI-Cockpit-Carplay.mirror.autostart.log MMI-Cockpit-Carplay.boot_entry.log mmi-mirror-controller.log; do
    source="$ROOT/tmp/$name"
    if [ ! -f "$source" ]; then
        echo "MISSING $name" >> "$OUT/FILES.txt" || exit 1
        MISSING=$((MISSING + 1))
        continue
    fi
    if tail -c 262144 "$source" > "$OUT/private/$name"; then
        echo "COPIED_TAIL_MAX_262144 $name" >> "$OUT/FILES.txt" || exit 1
        COPIED=$((COPIED + 1))
    else
        echo "FAILED $name" >> "$OUT/FILES.txt" || exit 1
        ERRORS=$((ERRORS + 1))
    fi
done
printf 'LOGS_COPIED=%s\nLOGS_MISSING=%s\nLOG_ERRORS=%s\n' "$COPIED" "$MISSING" "$ERRORS" >> "$OUT/SUMMARY.txt" || exit 1
(cd "$OUT" && cksum SUMMARY.txt PRIVACY.txt FILES.txt > CKSUMS.txt &&
    for file in private/*.log; do [ ! -f "$file" ] || cksum "$file" >> CKSUMS.txt || exit 1; done) ||
    { echo "FAIL: cannot checksum export"; exit 1; }
sync || { echo "FAIL: export sync failed; keep the SD card inserted"; exit 1; }
cat "$OUT/SUMMARY.txt"
echo "EXPORT_PATH=$OUT"
if [ "$ERRORS" -ne 0 ]; then echo "EXPORT=PARTIAL log_errors=$ERRORS"; exit 1; fi
echo "EXPORT=PASS no_restore=YES no_restart=YES"
