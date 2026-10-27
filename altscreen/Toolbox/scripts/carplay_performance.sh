#!/bin/sh
# Sourced only by the unit-bound installer/restore transaction.
# Stock-file changes are installation actions, never normal-MMI preferences.
perf_setup(){
    PERF_ROOT=""
    [ "${ALTSCREEN_CHAIN_TESTING:-0}" != 1 ] || PERF_ROOT=${ALTSCREEN_CHAIN_ROOT:?}
    PERF_VOLUME=${ALTSCREEN_SD_VOLUME:-${ALTSCREEN_CHAIN_VOLUME:-}}
    [ -n "$PERF_VOLUME" ] || return 1
    PERF_ORIGINAL="$PERF_VOLUME/MMI-Cockpit-Carplay/backup/performance-original"
    PERF_HELPER="$PERF_VOLUME/Toolbox/carplay_alt_screen/rgi/carplay_binpatch"
    if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
        # Host fixtures run this same helper source, not a bypass of its checks.
        PERF_HELPER="$PERF_ROOT/performance-test-helper"
    fi
    [ -x "$PERF_HELPER" ]
}
perf_path(){
    case "$1" in
        decoder) printf '%s/eso/lib/libairplay.so\n' "$PERF_ROOT" ;;
        mcd) printf '%s/mnt/app/armle/usr/sbin/mcd\n' "$PERF_ROOT" ;;
        priority) printf '%s/mnt/system/etc/mcd.conf\n' "$PERF_ROOT" ;;
        *) return 1 ;;
    esac
}
perf_check(){ "$PERF_HELPER" check "$1" "$2"; }
perf_originals_valid() (
    [ -d "$PERF_ORIGINAL" ] && [ ! -L "$PERF_ORIGINAL" ] &&
        [ -f "$PERF_ORIGINAL/COMPLETE" ] || exit 1
    for role in decoder mcd priority; do
        [ ! -L "$PERF_ORIGINAL/$role" ] || exit 1
        [ "$(perf_check "$role" "$PERF_ORIGINAL/$role")" = stock ] || exit 1
        case "$(cat "$PERF_ORIGINAL/$role.mode")" in [0-7][0-7][0-7][0-7]) ;; *) exit 1 ;; esac
        [ "$(cksum < "$PERF_ORIGINAL/$role.mode")" = "$(cat "$PERF_ORIGINAL/$role.mode.cksum")" ] || exit 1
    done
)
perf_preflight() (
    perf_setup || exit 1
    if [ -e "$PERF_ORIGINAL" ]; then perf_originals_valid || exit 1; fi
    for role in decoder mcd priority; do
        file=$(perf_path "$role") || exit 1
        [ ! -L "$file" ] && [ ! -e "$file.performance-new" ] && [ ! -L "$file.performance-new" ] || exit 1
        state=$(perf_check "$role" "$file") || exit 1
        [ "$state" != optimized ] || perf_originals_valid || exit 1
    done
    echo "PERFORMANCE_PREFLIGHT=PASS exact_unit_files=YES decoder=2 mcd=200ms detector_priority=3"
)
perf_save_originals() (
    perf_setup || exit 1
    if [ -e "$PERF_ORIGINAL" ]; then perf_originals_valid; exit $?; fi
    temp="$PERF_ORIGINAL.new.$$"
    [ ! -e "$temp" ] && mkdir "$temp" || exit 1
    for role in decoder mcd priority; do
        file=$(perf_path "$role") || exit 1
        [ "$(perf_check "$role" "$file")" = stock ] || exit 1
        cp "$file" "$temp/$role" && cmp -s "$file" "$temp/$role" || exit 1
        file_mode "$file" > "$temp/$role.mode" &&
            cksum < "$temp/$role.mode" > "$temp/$role.mode.cksum" || exit 1
    done
    touch "$temp/COMPLETE" && sync && mv "$temp" "$PERF_ORIGINAL" && sync || exit 1
    perf_originals_valid
)
perf_publish() (
    role=$1; operation=$2
    file=$(perf_path "$role"); temp="$file.performance-new"
    [ ! -L "$file" ] && [ ! -e "$temp" ] && [ ! -L "$temp" ] || exit 1
    if [ "$operation" = apply ]; then
        "$PERF_HELPER" apply "$role" "$file" "$temp" || exit 1
        [ "$(perf_check "$role" "$temp")" = optimized ] || exit 1
    else
        cp "$PERF_ORIGINAL/$role" "$temp" &&
            cmp -s "$PERF_ORIGINAL/$role" "$temp" || exit 1
    fi
    chmod "$(cat "$PERF_ORIGINAL/$role.mode")" "$temp" &&
        mv "$temp" "$file" && sync || exit 1
)
perf_apply() (
    perf_setup && perf_originals_valid || exit 1
    for role in decoder mcd priority; do
        file=$(perf_path "$role")
        state=$(perf_check "$role" "$file") || exit 1
        [ "$state" = optimized ] || perf_publish "$role" apply || exit 1
    done
    perf_verify optimized
)
perf_restore() (
    perf_setup || exit 1
    # Older packages never touched these files and have no performance backup.
    [ -e "$PERF_ORIGINAL" ] || exit 0
    perf_originals_valid || exit 1
    for role in decoder mcd priority; do
        file=$(perf_path "$role")
        if [ "${ALTSCREEN_RESCUE:-0}" != 1 ]; then perf_check "$role" "$file" >/dev/null || exit 1; fi
        cmp -s "$PERF_ORIGINAL/$role" "$file" || perf_publish "$role" restore || exit 1
    done
    perf_verify stock
)
perf_verify() (
    expected=$1
    perf_setup || exit 1
    if [ "$expected" = stock ] && [ ! -e "$PERF_ORIGINAL" ]; then exit 0; fi
    perf_originals_valid || exit 1
    for role in decoder mcd priority; do
        [ "$(perf_check "$role" "$(perf_path "$role")")" = "$expected" ] || exit 1
        [ "$(file_mode "$(perf_path "$role")")" = "$(cat "$PERF_ORIGINAL/$role.mode")" ] || exit 1
    done
    echo "PERFORMANCE_VERIFY=PASS state=$expected activation=NEXT_BOOT"
)
perf_known_native_pair() (
    perf_setup && perf_originals_valid || exit 1
    # No blanket exception for changed binaries: only the pinned decoder pair.
    perf_check decoder "$1" >/dev/null && perf_check decoder "$2" >/dev/null || exit 1
)
perf_collect_staging() (
    perf_setup || exit 1
    for role in decoder mcd priority; do
        file="$(perf_path "$role").performance-new"
        [ -e "$file" ] || [ -L "$file" ] || continue
        [ -f "$file" ] && [ ! -L "$file" ] || exit 1
        ensure_dirs "$TXN/residue" || exit 1
        dst="$TXN/residue/performance-$role"
        [ ! -e "$dst" ] && cp "$file" "$dst" &&
            cmp -s "$file" "$dst" && rm "$file" || exit 1
    done
)
