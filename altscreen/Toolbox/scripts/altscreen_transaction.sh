#!/bin/sh
# Transaction mechanisms adapted from yuedizhibo V3.8 (141e7ed):
# altscreen_install_transaction.sh and altscreen_restore_transaction.sh.
# Copyright (c) 2026 yuedizhibo. PolyForm Noncommercial 1.0.0 and additional
# upstream terms (see altscreen_native). MU1316/RGI companion adapter.
# PREPARED is durable before APPLY; rollback restores exact pre-operation state,
# not the original-install backup. Rescue also snapshots damaged native bytes.
set -u
umask 077
ACTION=${1:-}
case "$ACTION" in install|restore|recover|rescue|undo-rescue) ;; *) exit 2 ;; esac
TESTING=${ALTSCREEN_CHAIN_TESTING:-0}
ROOT=""; VOLUME=${ALTSCREEN_SD_VOLUME:-}
if [ "$TESTING" = 1 ]; then
    ROOT=${ALTSCREEN_CHAIN_ROOT:-}; VOLUME=${ALTSCREEN_CHAIN_VOLUME:-}
    case "$ROOT:$VOLUME" in *'/../'*|*'/./'*) exit 2 ;; esac
    case "$ROOT" in /work/build/*|/repo/build/*|/tmp/*|/var/tmp/*) ;; *) exit 2 ;; esac
    case "$VOLUME" in /work/build/*|/repo/build/*|/tmp/*|/var/tmp/*) ;; *) exit 2 ;; esac
else
    for d in /net/mmx/fs/sda0 /net/mmx/fs/sda1 /net/mmx/fs/sdb0 /net/mmx/fs/sdb1 /fs/sda0 /fs/sda1 /fs/sdb0 /fs/sdb1; do
        [ -z "$VOLUME" ] || break
        [ ! -f "$d/Toolbox/scripts/altscreen_transaction.sh" ] || VOLUME=$d
    done
fi
[ -n "$VOLUME" ] && [ -d "$VOLUME/Toolbox" ] || { echo "FAIL: matching SD required"; exit 1; }
export ALTSCREEN_SD_VOLUME="$VOLUME"
SCRIPTS="$VOLUME/Toolbox/scripts"
SD="$VOLUME/MMI-Cockpit-Carplay"
RUNTIME="$ROOT/mnt/app/root/carplay-altscreen"
HOOKS="$ROOT/mnt/app/root/hooks"
SI="$ROOT/mnt/system/etc/eso/production/smartphone_integrator.json"
DIO="$ROOT/mnt/system/etc/eso/production/dio_manager.json"
PF="$ROOT/mnt/system/etc/pf.conf"
JAR="$ROOT/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar"
CONTROLLER="$SCRIPTS/altscreen_chain_test.sh"
UNIVERSAL="$SCRIPTS/altscreen_chain_test_universal.sh"
LOCK="$SD/.operation.lock"
BOOT_FILE="$ROOT/ramdisk/MMI-Cockpit-Carplay.transaction.boot"
TXN=""; READY=0; APP_RW=0; SYS_RW=0; LOCK_HELD=0
ensure_dirs(){ for d in "$@"; do [ -d "$d" ] || mkdir -p "$d" || return 1; done; }
log(){
    echo "$*"
    [ ! -d "$SD/logs" ] || printf '%s\n' "$*" >> "$SD/logs/transaction.log"
}
same(){ [ -f "$1" ] && [ -f "$2" ] && cmp -s "$1" "$2"; }
file_mode(){
    ls -ld "$1" | awk '{
      s=substr($1,2,9); extra=0
      if(substr(s,3,1) ~ /[sS]/)extra+=4
      if(substr(s,6,1) ~ /[sS]/)extra+=2
      if(substr(s,9,1) ~ /[tT]/)extra+=1
      printf "%d",extra
      for(g=0;g<3;g++) {
        bits=0
        if(substr(s,g*3+1,1)=="r")bits+=4
        if(substr(s,g*3+2,1)=="w")bits+=2
        if(substr(s,g*3+3,1) ~ /[xst]/)bits+=1
        printf "%d",bits
      }
      print ""
    }'
}
mount_app_rw(){ [ "$TESTING" = 1 ] || mount -uw /mnt/app; }
mount_app_ro(){ [ "$TESTING" = 1 ] || mount -ur /mnt/app; }
mount_system_rw(){ [ "$TESTING" = 1 ] || mount -uw /mnt/system; }
mount_system_ro(){ [ "$TESTING" = 1 ] || mount -ur /mnt/system; }
finish_mounts(){
    finish_rc=0
    sync || finish_rc=1
    [ "$APP_RW" = 0 ] || { mount_app_ro && APP_RW=0 || finish_rc=1; }
    [ "$SYS_RW" = 0 ] || { mount_system_ro && SYS_RW=0 || finish_rc=1; }
    return "$finish_rc"
}
unlock(){
    [ "$LOCK_HELD" = 1 ] || return 0
    [ "$(cat "$LOCK/pid" 2>/dev/null)" = "$$" ] || return 1
    rm -f "$LOCK/pid" "$LOCK/boot" && rmdir "$LOCK" || return 1
    LOCK_HELD=0
}
trap 'rc=$?; if [ "$APP_RW" != 0 ] || [ "$SYS_RW" != 0 ]; then finish_mounts || rc=1; fi; unlock || rc=1; exit "$rc"' 0
trap 'fail "interrupted"' 1 2 15

# Keep the status outside the producer pipeline: a killed producer or missing
# terminal record is failure, irrespective of earlier child PASS messages.
run_detail(){
    (
        "$@" 2>&1
        detail_rc=$?
        printf '\nALTS_DETAIL_STATUS_%s=%s\n' "$$" "$detail_rc"
    ) | (
        detail_rc=1
        while IFS= read -r line || [ -n "$line" ]; do
            case "$line" in
                "ALTS_DETAIL_STATUS_$$="*) detail_rc=${line#*=} ;;
                *) printf '%s\n' "$line"; printf '%s\n' "$line" >> "$SD/logs/transaction.log" || exit 1 ;;
            esac
        done
        case "$detail_rc" in ''|*[!0-9]*) detail_rc=1 ;; esac
        exit "$detail_rc"
    )
}
acquire(){
    ensure_dirs "$SD/logs" || return 1
    if [ ! -s "$BOOT_FILE" ]; then
        (set -C; printf '%s\n' "$(date +%Y%m%d_%H%M%S)_$$" > "$BOOT_FILE") 2>/dev/null || true
    fi
    [ -s "$BOOT_FILE" ] || { log "FAIL: boot identity unavailable"; return 1; }
    boot=$(cat "$BOOT_FILE") || return 1
    if ! mkdir "$LOCK" 2>/dev/null; then
        [ -d "$LOCK" ] && [ ! -L "$LOCK" ] || return 1
        [ -f "$LOCK/pid" ] || sleep 1
        owner=$(cat "$LOCK/pid" 2>/dev/null || true)
        saved_boot=$(cat "$LOCK/boot" 2>/dev/null || true)
        case "$owner" in
            "") ;;
            *[!0-9]*|0|1) log "FAIL: ambiguous operation lock retained"; return 1 ;;
            *)
                if { [ -z "$saved_boot" ] || [ "$saved_boot" = "$boot" ]; } &&
                    kill -0 "$owner" 2>/dev/null; then
                    log "FAIL: operation already active"; return 1;
                fi ;;
        esac
        rm -f "$LOCK/pid" "$LOCK/boot" && rmdir "$LOCK" && mkdir "$LOCK" || return 1
        log "STALE_LOCK=RECOVERED"
    fi
    # Exclusive PID publication also fences a late creator of a reaped empty
    # lock. A failed claimant must never remove somebody else's lock on exit.
    (set -C; printf '%s\n' "$$" > "$LOCK/pid") 2>/dev/null || return 1
    LOCK_HELD=1
    printf '%s\n' "$boot" > "$LOCK/boot" || return 1
    if [ -f "$SD/logs/transaction.log" ] &&
        [ "$(wc -c < "$SD/logs/transaction.log")" -gt 1048576 ]; then
        tail -c 524288 "$SD/logs/transaction.log" > "$SD/logs/transaction.previous.log" &&
            : > "$SD/logs/transaction.log" || return 1
    fi
    return 0
}
identity(){
    /bin/sh "$UNIVERSAL" firmware-train || return 1
    /bin/sh "$UNIVERSAL" vehicle-key || return 1
}
find_startup(){
    for f in "$ROOT/mnt/system/etc/boot/startup.sh" "$ROOT/etc/boot/startup.sh"; do
        [ ! -f "$f" ] || { printf '%s\n' "$f"; return 0; }
    done
    return 1
}
dir_manifest() (
    cd "$1" || exit 1
    # Preserve empty directories; refuse links/special nodes rather than follow
    # them outside the transaction. Modes are separate: an SD FAT filesystem
    # cannot represent the original HU permissions on snapshot copies.
    find . ! -type d ! -type f -print > "$2.nodes" || exit 1
    [ ! -s "$2.nodes" ] || { rm -f "$2.nodes"; exit 1; }
    rm -f "$2.nodes" || exit 1
    find . -type d -print > "$2.dirs" && sort "$2.dirs" > "$2" || exit 1
    find . -type f -print > "$2.unsorted" && sort "$2.unsorted" > "$2.paths" || exit 1
    while IFS= read -r name; do
        printf '%s|' "$name" >> "$2" && cksum < "$name" >> "$2" || exit 1
    done < "$2.paths"
    rm -f "$2.paths" "$2.dirs" "$2.unsorted"
)
dir_modes() (
    cd "$1" || exit 1
    find . -print > "$2.unsorted" && sort "$2.unsorted" > "$2.paths" || exit 1
    : > "$2" || exit 1
    while IFS= read -r name; do
        case "$name" in *'|'*) exit 1 ;; esac
        printf '%s|%s\n' "$(file_mode "$name")" "$name" >> "$2" || exit 1
    done < "$2.paths"
    rm -f "$2.paths" "$2.unsorted"
)
same_dir(){
    dir_manifest "$1" "$TXN/compare.a" &&
        dir_manifest "$2" "$TXN/compare.b" &&
        cmp -s "$TXN/compare.a" "$TXN/compare.b"
}
snapshot_item(){
    kind=$1; path=$2; name=$3
    [ ! -L "$path" ] || return 1
    if [ -e "$path" ]; then
        if [ "$kind" = dir ]; then
            [ -d "$path" ] || return 1
            ensure_dirs "$TXN/data/$name" || return 1
            cp -R "$path/." "$TXN/data/$name/" &&
                same_dir "$path" "$TXN/data/$name" &&
                dir_manifest "$TXN/data/$name" "$TXN/meta/$name.manifest" &&
                dir_modes "$path" "$TXN/meta/$name.modes" &&
                cksum < "$TXN/meta/$name.modes" > "$TXN/meta/$name.modes.cksum" || return 1
        else
            [ -f "$path" ] || return 1
            cp "$path" "$TXN/data/$name" && same "$path" "$TXN/data/$name" &&
                cksum < "$TXN/data/$name" > "$TXN/meta/$name.cksum" &&
                file_mode "$path" > "$TXN/meta/$name.mode" &&
                cksum < "$TXN/meta/$name.mode" > "$TXN/meta/$name.mode.cksum" || return 1
        fi
        touch "$TXN/meta/$name.present" || return 1
    else
        touch "$TXN/meta/$name.absent" || return 1
    fi
    printf '%s|%s|%s\n' "$kind" "$path" "$name" >> "$TXN/items"
}
allowed_item(){
    case "$1:$2" in
        file:"$SI"|file:"$DIO"|file:"$PF"|file:"$JAR"|file:"$ROOT/mnt/system/etc/boot/startup.sh"|file:"$ROOT/etc/boot/startup.sh") return 0 ;;
        file:"$ROOT/mnt/system/etc/"pf.*.conf) case "${2##*/}" in *[!A-Za-z0-9._-]*) return 1 ;; *) return 0 ;; esac ;;
        file:"$ROOT/eso/bin/apps/dio_manager"|file:"$ROOT/mnt/app/eso/bin/apps/dio_manager"|file:"$ROOT/eso/lib/libairplay.so"|file:"$ROOT/armle/usr/lib/libNmeBaseClasses.so"|file:"$ROOT/mnt/app/armle/usr/lib/libNmeBaseClasses.so"|file:"$ROOT/eso/lib/libNmeBaseClasses.so") return 0 ;;
        dir:"$RUNTIME"|dir:"$ROOT/mnt/app/root/.carplay-altscreen.previous"|dir:"$HOOKS"|dir:"$ROOT/mnt/app/root/lib-target"|dir:"$SD/state"|dir:"$SD/backup"|dir:"$SD/staging") return 0 ;;
        dir:"$ROOT/mnt/app/root/.carplay-altscreen.new."*) case "${2##*.}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac ;;
    esac
    return 1
}
validate_item(){
    kind=$1; path=$2; name=$3
    allowed_item "$kind" "$path" || return 1
    case "$name" in ''|*[!A-Za-z0-9._-]*) return 1 ;; esac
    present=0; absent=0
    [ ! -f "$TXN/meta/$name.present" ] || present=1
    [ ! -f "$TXN/meta/$name.absent" ] || absent=1
    [ $((present + absent)) = 1 ] || return 1
    if [ "$present" = 0 ]; then [ ! -e "$TXN/data/$name" ]; return; fi
    if [ "$kind" = dir ]; then
        [ "$(cksum < "$TXN/meta/$name.modes")" = "$(cat "$TXN/meta/$name.modes.cksum")" ] || return 1
        dir_manifest "$TXN/data/$name" "$TXN/validate.manifest" &&
            cmp -s "$TXN/validate.manifest" "$TXN/meta/$name.manifest"
    else
        case "$(cat "$TXN/meta/$name.mode")" in [0-7][0-7][0-7][0-7]) ;; *) return 1 ;; esac
        [ "$(cksum < "$TXN/meta/$name.mode")" = "$(cat "$TXN/meta/$name.mode.cksum")" ] || return 1
        [ "$(cksum < "$TXN/data/$name")" = "$(cat "$TXN/meta/$name.cksum")" ]
    fi
}
validate_snapshot(){
    [ "$(cat "$TXN/FORMAT" 2>/dev/null)" = MU1316-1 ] || return 1
    [ "$(cksum < "$TXN/items")" = "$(cat "$TXN/items.cksum")" ] || return 1
    [ "$(cksum < "$TXN/identity")" = "$(cat "$TXN/identity.cksum")" ] || return 1
    identity > "$TXN/identity.current" && same "$TXN/identity" "$TXN/identity.current" || {
        log "SNAPSHOT=REFUSED firmware_or_unit_mismatch=YES"; return 1;
    }
    while IFS='|' read -r kind path name; do
        validate_item "$kind" "$path" "$name" || return 1
        if [ ! -f "$TXN/RESCUE" ]; then
            case "$name" in native_*)
                same "$TXN/data/$name" "$path" || {
                    log "SNAPSHOT=REFUSED stock_binary_changed=YES rescue_required=YES"; return 1;
                } ;;
            esac
        fi
    done < "$TXN/items"
}
snapshot(){
    [ ! -e "$TXN" ] && ensure_dirs "$TXN/data" "$TXN/meta" || return 1
    echo MU1316-1 > "$TXN/FORMAT" || return 1
    [ "$ACTION" != rescue ] || touch "$TXN/RESCUE" || return 1
    identity > "$TXN/identity" && cksum < "$TXN/identity" > "$TXN/identity.cksum" || return 1
    : > "$TXN/items" || return 1
    startup=$(find_startup) || return 1
    snapshot_item file "$startup" startup || return 1
    snapshot_item file "$SI" si && snapshot_item file "$DIO" dio &&
        snapshot_item file "$PF" pf && snapshot_item file "$JAR" jar || return 1
    for f in "$ROOT/mnt/system/etc"/pf.*.conf; do
        [ ! -f "$f" ] || snapshot_item file "$f" "${f##*/}" || return 1
    done
    /bin/sh "$UNIVERSAL" native-paths > "$TXN/native.paths" || return 1
    while IFS= read -r rel; do
        snapshot_item file "$ROOT$rel" "native_$(echo "$rel" | tr '/' '_')" || return 1
    done < "$TXN/native.paths"
    snapshot_item dir "$RUNTIME" runtime &&
        snapshot_item dir "$ROOT/mnt/app/root/.carplay-altscreen.previous" previous &&
        snapshot_item dir "$HOOKS" hooks &&
        snapshot_item dir "$ROOT/mnt/app/root/lib-target" libtarget &&
        snapshot_item dir "$SD/state" state &&
        snapshot_item dir "$SD/backup" backup &&
        snapshot_item dir "$SD/staging" staging || return 1
    for stage in "$ROOT/mnt/app/root"/.carplay-altscreen.new.*; do
        [ ! -e "$stage" ] || snapshot_item dir "$stage" "stage_${stage##*.}" || return 1
    done
    cksum < "$TXN/items" > "$TXN/items.cksum" && validate_snapshot || return 1
    touch "$TXN/PREPARED" && sync || return 1
    READY=1
    log "TRANSACTION=PREPARED action=$ACTION durable=YES"
}
restore_item(){
    kind=$1; path=$2; name=$3
    # In particular, never rewrite unchanged OEM binaries during normal rollback.
    if [ "$kind" = file ] && [ -f "$TXN/meta/$name.present" ] &&
        same "$TXN/data/$name" "$path" &&
        [ "$(file_mode "$path")" = "$(cat "$TXN/meta/$name.mode")" ]; then return 0; fi
    if [ -f "$TXN/meta/$name.absent" ]; then
        [ "$kind" != dir ] || { rm -rf "$path"; return; }
        rm -f "$path"; return
    fi
    publish="$path.transaction-new"
    [ ! -L "$path" ] && [ ! -L "$publish" ] || return 1
    if [ "$kind" = dir ]; then
        rm -rf "$publish" && ensure_dirs "$publish" &&
            cp -R "$TXN/data/$name/." "$publish/" &&
            same_dir "$TXN/data/$name" "$publish" || return 1
        case "$path" in "$SD"/*) ;; *)
            while IFS='|' read -r mode rel; do
                case "$mode:$rel" in [0-7][0-7][0-7][0-7]:.|[0-7][0-7][0-7][0-7]:./*) ;; *) return 1 ;; esac
                case "$rel" in */../*|*/..) return 1 ;; esac
                chmod "$mode" "$publish/$rel" || return 1
            done < "$TXN/meta/$name.modes" ;;
        esac
        rm -rf "$path" && mv "$publish" "$path"
    else
        ensure_dirs "$(dirname -- "$path")" &&
            cp "$TXN/data/$name" "$publish" &&
            chmod "$(cat "$TXN/meta/$name.mode")" "$publish" &&
            same "$TXN/data/$name" "$publish" && mv "$publish" "$path"
    fi
}
verify_item(){
    if [ -f "$TXN/meta/$3.absent" ]; then [ ! -e "$2" ]; return; fi
    if [ "$1" = dir ]; then
        [ -d "$2" ] && same_dir "$TXN/data/$3" "$2" || return 1
        case "$2" in "$SD"/*) ;; *)
            dir_modes "$2" "$TXN/verify.modes" &&
                same "$TXN/verify.modes" "$TXN/meta/$3.modes" || return 1 ;;
        esac
    else
        same "$TXN/data/$3" "$2" &&
            [ "$(file_mode "$2")" = "$(cat "$TXN/meta/$3.mode")" ]
    fi
}
rollback(){
    [ -f "$TXN/PREPARED" ] && validate_snapshot || return 1
    touch "$TXN/ROLLBACK_INCOMPLETE" && rm -f "$TXN/COMMITTED" "$TXN/ROLLED_BACK" && sync || return 1
    mount_system_rw && SYS_RW=1 || return 1
    mount_app_rw && APP_RW=1 || return 1
    # References first, verified before any referenced runtime can be removed.
    while IFS='|' read -r kind path name; do
        case "$name" in startup|si|dio|pf|pf.*.conf)
            restore_item "$kind" "$path" "$name" &&
                verify_item "$kind" "$path" "$name" || return 1 ;;
        esac
    done < "$TXN/items"
    sync || return 1
    # Interrupted children can leave unpublished runtime directories with their
    # own PID suffixes. Retain these as evidence before restoring the stage set.
    for stage in "$ROOT/mnt/app/root"/.carplay-altscreen.new.*; do
        [ -e "$stage" ] || continue
        allowed_item dir "$stage" || return 1
        [ -d "$stage" ] && [ ! -L "$stage" ] || return 1
        ensure_dirs "$TXN/residue" || return 1
        destination="$TXN/residue/${stage##*/}"
        [ ! -e "$destination" ] || destination="$destination.$$"
        mv "$stage" "$destination" || return 1
    done
    while IFS='|' read -r kind path name; do
        case "$name" in startup|si|dio|pf|pf.*.conf) continue ;; esac
        restore_item "$kind" "$path" "$name" || return 1
    done < "$TXN/items"
    finish_mounts || return 1
    while IFS='|' read -r kind path name; do
        verify_item "$kind" "$path" "$name" || return 1
    done < "$TXN/items"
    touch "$TXN/ROLLED_BACK" && rm -f "$TXN/APPLYING" "$TXN/ROLLBACK_INCOMPLETE" && sync || return 1
    READY=0
    log "ROLLBACK=PASS persistent_state=PRE_OPERATION"
}
archive(){
    history="${TXN%/*}/history"
    ensure_dirs "$history" || return 1
    destination="$history/$(date +%Y%m%d_%H%M%S)_$$"
    [ ! -e "$destination" ] || return 1
    mv "$TXN" "$destination" && sync
}
recover_one(){
    [ -d "$TXN" ] || return 0
    if [ ! -e "$TXN/ROLLBACK_INCOMPLETE" ] &&
        { { [ -f "$TXN/COMMITTED" ] && [ -f "$TXN/ACTIVATED" ]; } || [ -f "$TXN/ROLLED_BACK" ]; }; then
        archive; return
    fi
    if [ -f "$TXN/PREPARED" ]; then
        log "STALE_TRANSACTION=DETECTED action=ROLLBACK"
        READY=1
        rollback || return 1
        log "STALE_TRANSACTION=RECOVERED"
    else
        log "STALE_TRANSACTION=INCOMPLETE_PREPARE production_changed=NO"
    fi
    archive
}
fail(){
    log "FAIL: $1"
    if [ "$READY" = 1 ]; then
        if rollback; then log "TRANSACTION=ABORTED rollback=PASS"
        else
            touch "$TXN/ROLLBACK_INCOMPLETE" 2>/dev/null || true
            log "TRANSACTION=FAILED rollback=INCOMPLETE recovery_required=YES evidence=$TXN"
        fi
    fi
    exit 1
}
verify_installed(){
    payload="$VOLUME/Toolbox/carplay_alt_screen"
    same "$payload/hmi/carplay_hook-basevideo3.jar" "$JAR" &&
        same "$payload/universal/libcarplay_altscreen.so" "$RUNTIME/lib/libcarplay_altscreen.so" || return 1
    for f in carplay-alt111-mirror-display start_vehicle.sh stop_vehicle.sh mirror_health.sh libcarplay_mascot.so mascots.rgba BUILD_INFO.txt; do
        same "$payload/mirror_display/release/$f" "$RUNTIME/bin/mirror/$f" || return 1
    done
    for f in libcarplay_hook.so maneuver_render flag_atlas.rgba carplay_startup.sh carplay_monitor.sh carplay_processes.sh carplay_settings.sh carplay_cleanup.sh; do
        same "$payload/rgi/$f" "$HOOKS/$f" || return 1
    done
    [ -f "$SD/state/INSTALLED" ] && [ -f "$RUNTIME/state/ACTIVE" ] &&
        [ -f "$RUNTIME/state/basevideo3.enabled" ] &&
        [ ! -e "$RUNTIME/state/start.pending" ] && [ -f "$RUNTIME/state/transaction.pending" ] || return 1
    [ -x "$RUNTIME/bin/mirror/start_vehicle.sh" ] && [ -x "$HOOKS/carplay_startup.sh" ] || return 1
    awk -v query=/mnt/app/root/carplay-altscreen/lib/libcarplay_altscreen.so \
        -f "$SCRIPTS/altscreen_preload.awk" "$SI" >/dev/null || return 1
    grep -q '"exec": "carplay_startup.sh"' "$SI" || return 1
    for id in 5200 5201 5202 5203 5204 415B 415C 415D 415F 4160 4170 4171; do
        grep -q "\"0x$id\"" "$DIO" || return 1
    done
    startup=$(find_startup) || return 1
    [ "$(grep -c '^# BEGIN ALT111 BASEVIDEO3 AUTOSTART$' "$startup")" = 1 ] &&
        [ "$(grep -c '^# END ALT111 BASEVIDEO3 AUTOSTART$' "$startup")" = 1 ] &&
        [ "$(grep -c '^# BEGIN ALTSCREEN DIAGNOSTICS$' "$startup")" = 1 ] &&
        sh -n "$startup" || return 1
    /bin/sh "$UNIVERSAL" verify-install || return 1
    verify_startup_unrelated || return 1
    log "INSTALL_VERIFY=PASS autostart=ARMED live_launch=NOT_REQUIRED"
}
verify_restored(){
    [ ! -e "$RUNTIME" ] || return 1
    local_baseline verify || return 1
    /bin/sh "$UNIVERSAL" verify-restored || return 1
    startup=$(find_startup) || return 1
    if grep -Eq '^# (BEGIN|END) (ALT111|ALTSCREEN)' "$startup"; then return 1; fi
    sh -n "$startup"
}
strip_owned_startup(){
    awk '
      /^# BEGIN (ALTSCREEN DIAGNOSTICS|ALT111 MIRROR AUTOSTART|ALT111 BASEVIDEO3 AUTOSTART)$/ {
        if(inside)exit 9
        inside=substr($0,9); next
      }
      /^# END (ALTSCREEN DIAGNOSTICS|ALT111 MIRROR AUTOSTART|ALT111 BASEVIDEO3 AUTOSTART)$/ {
        if(!inside || inside!=substr($0,7))exit 9
        inside=""; next
      }
      !inside {print}
      END {if(inside)exit 9}
    ' "$1"
}
verify_startup_unrelated(){
    startup=$(find_startup) || return 1
    strip_owned_startup "$TXN/data/startup" > "$TXN/startup.before" &&
        strip_owned_startup "$startup" > "$TXN/startup.after" &&
        same "$TXN/startup.before" "$TXN/startup.after"
}
local_baseline() (
    mode=$1; baseline="$SD/backup/local-original"
    files="libcarplay_hook.so maneuver_render flag_atlas.rgba carplay_startup.sh carplay_monitor.sh carplay_processes.sh carplay_settings.sh carplay_cleanup.sh carplay_hook.jar"
    if [ "$mode" = save ]; then
        if [ -d "$baseline" ]; then
            [ -f "$baseline/COMPLETE" ] || return 1
            return 0
        fi
        ensure_dirs "$baseline" || return 1
        for name in $files; do
            path="$HOOKS/$name"; [ "$name" != carplay_hook.jar ] || path=$JAR
            # On a legacy upgrade these names are already our deployed payload,
            # not OEM originals. Preserve all unrelated hooks/preferences in place.
            if [ ! -f "$SD/state/INSTALLED" ] && [ -f "$path" ]; then
                cp "$path" "$baseline/$name" && same "$path" "$baseline/$name" &&
                    cksum < "$baseline/$name" > "$baseline/$name.cksum" &&
                    file_mode "$path" > "$baseline/$name.mode" || return 1
            else touch "$baseline/$name.absent" || return 1; fi
        done
        touch "$baseline/COMPLETE" && sync
        return
    fi
    [ -f "$baseline/COMPLETE" ] || {
        # Old installations have no added local-original set.
        [ "$mode" != verify ] || [ ! -e "$JAR" ]
        return
    }
    for name in $files; do
        path="$HOOKS/$name"; [ "$name" != carplay_hook.jar ] || path=$JAR
        if [ -f "$baseline/$name.absent" ]; then
            [ ! -e "$baseline/$name" ] && [ ! -e "$path" ] || return 1
        else
            [ -f "$baseline/$name" ] && [ -f "$baseline/$name.cksum" ] &&
                [ "$(cksum < "$baseline/$name")" = "$(cat "$baseline/$name.cksum")" ] || return 1
            if [ "$mode" = restore ]; then
                ensure_dirs "$(dirname -- "$path")" &&
                    cp "$baseline/$name" "$path.transaction-new" &&
                    chmod "$(cat "$baseline/$name.mode")" "$path.transaction-new" &&
                    same "$baseline/$name" "$path.transaction-new" &&
                    mv "$path.transaction-new" "$path" || return 1
            fi
            same "$baseline/$name" "$path" || return 1
        fi
    done
)

/bin/sh "$UNIVERSAL" firmware-train >/dev/null || exit 1
/bin/sh "$CONTROLLER" sd-preflight || exit 1
acquire || exit 1
if [ "$ACTION" = undo-rescue ]; then
    TXN="$SD/restore-transaction/rescue"
    [ -f "$TXN/COMMITTED" ] || fail "no committed reversible rescue"
    READY=1
    rollback || fail "rescue reversal incomplete"
    archive || fail "rescue reversal history retention"
    log "RESCUE_UNDO=PASS reboot_required=YES"
    exit 0
fi
for operation in install restore; do
    TXN="$SD/$operation-transaction/active"
    recover_one || fail "stale transaction recovery refused"
done
[ "$ACTION" != recover ] || { log "RECOVERY=PASS"; exit 0; }
if [ "$ACTION" = rescue ]; then
    [ "${2:-}" = --confirm-stock-rescue ] || fail "explicit --confirm-stock-rescue required"
    [ ! -e "$SD/restore-transaction/rescue" ] || fail "previous reversible rescue retained"
    ALTSCREEN_RESCUE=1; export ALTSCREEN_RESCUE
fi
if [ "$ACTION" = install ]; then
    /bin/sh "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh" --preflight || fail "install preflight"
else
    /bin/sh "$UNIVERSAL" restore-preflight || fail "restore preflight"
fi
if [ "$ACTION" = rescue ]; then
    /bin/sh "$UNIVERSAL" rescue-preflight || fail "rescue identity"
fi
if [ "$ACTION" = restore ] && [ ! -e "$RUNTIME" ] && [ ! -e "$SD/state/INSTALLED" ]; then
    verify_restored || fail "already-restored verification"
    log "RESTORE=PASS already_restored=YES"
    exit 0
fi
operation=$ACTION; [ "$operation" != rescue ] || operation=restore
TXN="$SD/$operation-transaction/active"
snapshot || fail "snapshot failed before APPLY"
touch "$TXN/APPLYING" && sync || fail "APPLYING journal"
if [ "$ACTION" = install ]; then
    local_baseline save || fail "local original backup"
    ALTSCREEN_TRANSACTION_ACTIVE=1 run_detail /bin/sh "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh" --apply ||
        fail "install APPLY"
    verify_installed || fail "installed final verification"
else
    ALTSCREEN_TRANSACTION_ACTIVE=1 ALTSCREEN_RESCUE="${ALTSCREEN_RESCUE:-0}" \
        run_detail /bin/sh "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh" --apply || fail "restore APPLY"
    mount_app_rw && APP_RW=1 || fail "local original restore mount"
    local_baseline restore || fail "local original restore"
    finish_mounts || fail "local original restore sync"
    verify_restored || fail "restored final verification"
    verify_startup_unrelated || fail "unrelated startup content changed"
fi
finish_mounts || fail "final sync/remount"
touch "$TXN/COMMITTED" && sync || fail "durable commit"
if [ "$ACTION" = install ]; then
    mount_app_rw && APP_RW=1 || fail "activation mount"
    rm -f "$RUNTIME/state/transaction.pending" || fail "activation journal"
    finish_mounts || fail "activation sync/remount"
fi
touch "$TXN/ACTIVATED" && sync || fail "durable activation"
ensure_dirs "$SD/logs/operations" || fail "operation result directory"
printf '%s action=%s state=COMMITTED\n' "$(date +%Y%m%d_%H%M%S)" \
    "$(printf '%s' "$operation" | tr 'a-z' 'A-Z')" > "$SD/logs/operations/TRANSACTION.log" &&
    sync || fail "operation result journal"
READY=0
if [ "$ACTION" = rescue ]; then
    [ ! -e "$SD/restore-transaction/rescue" ] || fail "previous rescue retained; refusing overwrite"
    mv "$TXN" "$SD/restore-transaction/rescue" && sync || fail "rescue retention"
    log "RESCUE=PASS reversible=YES reboot_required=YES"
else
    archive || fail "committed transaction evidence archival failed"
    log "TRANSACTION=PASS action=$ACTION reboot_required=YES"
    [ "$ACTION" != install ] || log "INSTALL=PASS integrated=AltScreen+H264Tap+DecoderTap+Displayable3+Java80+RGI reboot_required=YES steps=INSTALL_REBOOT"
    [ "$ACTION" != restore ] || log "RESTORE=PASS transaction=COMMITTED reboot_required=YES"
fi
unlock || { log "FAIL: completed operation lock cleanup"; exit 1; }
exit 0
