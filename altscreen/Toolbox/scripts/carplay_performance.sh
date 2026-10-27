#!/bin/sh
# Sourced by the unit-bound transaction and explicit internal recovery action.
# Stock-file changes are installation actions, never normal-MMI preferences.
perf_setup(){
    PERF_ROOT=""
    [ "${ALTSCREEN_CHAIN_TESTING:-0}" != 1 ] || PERF_ROOT=${ALTSCREEN_CHAIN_ROOT:?}
    PERF_KIT="$PERF_ROOT/mnt/app/root/carplay-performance-rescue"
    if [ "${PERF_INTERNAL_RECOVERY:-0}" = 1 ]; then
        PERF_ORIGINAL="$PERF_KIT/original"
        PERF_HELPER_SOURCE="$PERF_KIT/carplay_binpatch"
    else
        PERF_VOLUME=${ALTSCREEN_SD_VOLUME:-${ALTSCREEN_CHAIN_VOLUME:-}}
        [ -n "$PERF_VOLUME" ] || return 1
        PERF_ORIGINAL="$PERF_VOLUME/MMI-Cockpit-Carplay/backup/performance-original"
        PERF_HELPER_SOURCE="$PERF_VOLUME/Toolbox/carplay_alt_screen/rgi/carplay_binpatch"
        if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
            PERF_HELPER_SOURCE="$PERF_ROOT/performance-test-helper"
        fi
    fi
}
perf_prepare_helper(){
    perf_setup || return 1
    [ -s "$PERF_HELPER_SOURCE" ] && [ ! -L "$PERF_HELPER_SOURCE" ] || return 1
    helper_sum=$(cksum < "$PERF_HELPER_SOURCE") || return 1
    set -- $helper_sum
    [ "$#" -ge 2 ] || return 1
    case "$1:$2" in *[!0-9:]*) return 1 ;; esac
    helper_cache="$PERF_ROOT/ramdisk/carplay-performance-$1-$2"
    [ ! -L "$helper_cache" ] || return 1
    [ -d "$helper_cache" ] || mkdir -m 700 "$helper_cache" || return 1
    PERF_HELPER="$helper_cache/carplay_binpatch"
    [ ! -L "$PERF_HELPER" ] || return 1
    if [ ! -f "$PERF_HELPER" ]; then
        helper_temp="$helper_cache/new.$$"
        [ ! -e "$helper_temp" ] && [ ! -L "$helper_temp" ] &&
            cp "$PERF_HELPER_SOURCE" "$helper_temp" &&
            cmp -s "$PERF_HELPER_SOURCE" "$helper_temp" &&
            chmod 755 "$helper_temp" && mv "$helper_temp" "$PERF_HELPER" || return 1
    fi
    cmp -s "$PERF_HELPER_SOURCE" "$PERF_HELPER" && [ -x "$PERF_HELPER" ] &&
        "$PERF_HELPER" --self-test >/dev/null
}
perf_lock_exec(){
    perf_prepare_helper || return 1
    if [ "${CARPLAY_PERFORMANCE_LOCK_PID:-}" = "$$" ]; then
        "$PERF_HELPER" lock-held
        return
    fi
    exec "$PERF_HELPER" lock-exec /bin/sh "$@"
}
perf_mode(){
    ls -ld "$1" | awk '{
      s=substr($1,2,9);extra=0
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
perf_decoder_alias(){
    perf_prepare_helper &&
        "$PERF_HELPER" same-file "$(perf_path decoder)" "$PERF_ROOT/eso/lib/libairplay.so"
}
perf_path(){
    case "$1" in
        decoder) printf '%s/mnt/app/eso/lib/libairplay.so\n' "$PERF_ROOT" ;;
        mcd) printf '%s/mnt/app/armle/usr/sbin/mcd\n' "$PERF_ROOT" ;;
        priority) printf '%s/mnt/system/etc/mcd.conf\n' "$PERF_ROOT" ;;
        *) return 1 ;;
    esac
}
perf_check(){ perf_prepare_helper && "$PERF_HELPER" check "$1" "$2"; }
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
    perf_prepare_helper && perf_decoder_alias || exit 1
    perf_train >/dev/null && perf_unit_key >/dev/null || exit 1
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
        perf_mode "$file" > "$temp/$role.mode" &&
            cksum < "$temp/$role.mode" > "$temp/$role.mode.cksum" || exit 1
    done
    touch "$temp/COMPLETE" && sync && mv "$temp" "$PERF_ORIGINAL" && sync || exit 1
    perf_originals_valid
)
perf_publish() (
    role=$1; operation=$2
    perf_prepare_helper || exit 1
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
    [ "$role" != decoder ] || perf_decoder_alias || exit 1
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
        if ! cmp -s "$PERF_ORIGINAL/$role" "$file" ||
            [ "$(perf_mode "$file")" != "$(cat "$PERF_ORIGINAL/$role.mode")" ]; then
            perf_publish "$role" restore || exit 1
        fi
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
        [ "$(perf_mode "$(perf_path "$role")")" = "$(cat "$PERF_ORIGINAL/$role.mode")" ] || exit 1
    done
    echo "PERFORMANCE_VERIFY=PASS state=$expected activation=NEXT_BOOT"
)
perf_decoder_restored() (
    perf_setup && perf_originals_valid && perf_decoder_alias || exit 1
    cmp -s "$PERF_ORIGINAL/decoder" "$(perf_path decoder)" &&
        [ "$(perf_mode "$(perf_path decoder)")" = "$(cat "$PERF_ORIGINAL/decoder.mode")" ]
)
perf_status() (
    perf_setup || exit 1
    [ -d "$PERF_ORIGINAL" ] || { echo "PERFORMANCE=NOT_INSTALLED"; exit 0; }
    for role in decoder mcd priority; do
        state=$(perf_check "$role" "$(perf_path "$role")") || state=INVALID
        echo "PERFORMANCE_$role=$state stored_file=YES"
    done
    active=$(perf_check priority "$PERF_ROOT/etc/mcd.conf") || active=UNKNOWN
    echo "PERFORMANCE_priority_active=$active source=/etc/mcd.conf"
    echo "PERFORMANCE_running_decoder_mcd=NOT_PROVEN_BY_FILE_HASH"
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
perf_unit_key() (
    perf_setup || exit 1
    if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
        cksum < "$PERF_ROOT/dev/shmem/test-unit-id"
        exit
    fi
    command -v pidin >/dev/null 2>&1 || exit 1
    record="$PERF_ROOT/ramdisk/carplay-performance-unit.$$"
    [ ! -e "$record" ] && [ ! -L "$record" ] || exit 1
    umask 077
    pidin ar > "$record" 2>/dev/null &
    child=$!
    for tick in 1 2 3 4 5; do
        kill -0 "$child" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$child" 2>/dev/null; then
        kill -KILL "$child" 2>/dev/null || true
        wait "$child" 2>/dev/null || true
        rm -f "$record"
        exit 1
    fi
    wait "$child" || { rm -f "$record"; exit 1; }
    value=$(awk '{for(i=1;i<=NF;i++) if($i ~ /^-DmyAudi[.]VIN=/) {
      sub(/^-DmyAudi[.]VIN=/,"",$i)
      if(length($i)==17 && $i !~ /[^A-Z0-9]/) {print $i;exit}
    }}' "$record")
    rm -f "$record" || exit 1
    [ -n "$value" ] || exit 1
    printf '%s' "$value" | cksum
)
perf_train() (
    perf_setup || exit 1
    for rel in /net/rcc/dev/shmem/version.txt /dev/shmem/version.txt /net/mmx/dev/shmem/version.txt; do
        [ -r "$PERF_ROOT$rel" ] || continue
        train=$(sed -n 's/.*\(MHI2Q_[A-Za-z0-9_]*\).*/\1/p' "$PERF_ROOT$rel" | head -n 1)
        [ -z "$train" ] || break
    done
    [ "${train:-}" = MHI2Q_US_AUG22_P5145 ] && printf '%s\n' "$train"
)
perf_install_kit() (
    perf_setup && perf_originals_valid && perf_prepare_helper || exit 1
    next="$PERF_KIT.staging"; previous="$PERF_KIT.previous"
    [ ! -e "$next" ] && [ ! -L "$next" ] && [ ! -e "$previous" ] && [ ! -L "$previous" ] || exit 1
    mkdir -m 700 "$next" && mkdir "$next/original" || exit 1
    for role in decoder mcd priority; do
        for suffix in '' .mode .mode.cksum; do
            cp "$PERF_ORIGINAL/$role$suffix" "$next/original/$role$suffix" &&
                cmp -s "$PERF_ORIGINAL/$role$suffix" "$next/original/$role$suffix" || exit 1
        done
    done
    cp "$PERF_HELPER" "$next/carplay_binpatch" && chmod 755 "$next/carplay_binpatch" &&
        cmp -s "$PERF_HELPER" "$next/carplay_binpatch" || exit 1
    for script in carplay_performance.sh carplay_performance_recover.sh; do
        cp "$PERF_VOLUME/Toolbox/scripts/$script" "$next/$script" &&
            chmod 755 "$next/$script" &&
            cmp -s "$PERF_VOLUME/Toolbox/scripts/$script" "$next/$script" || exit 1
    done
    perf_train > "$next/train" && perf_unit_key > "$next/unit.key" &&
        cksum < "$next/unit.key" > "$next/unit.key.cksum" &&
        (cd "$next" && cksum carplay_binpatch carplay_performance.sh carplay_performance_recover.sh \
            train unit.key unit.key.cksum) > "$next/payload.cksum" &&
        touch "$next/original/COMPLETE" && sync || exit 1
    [ ! -e "$PERF_KIT" ] || mv "$PERF_KIT" "$previous" || exit 1
    mv "$next" "$PERF_KIT" && sync || exit 1
    # This fixed previous directory is also part of the outer snapshot.
    [ ! -d "$previous" ] || rm -rf "$previous" || exit 1
    perf_verify_kit
)
perf_verify_kit() (
    perf_setup || exit 1
    kit=$PERF_KIT
    [ -d "$kit" ] && [ ! -L "$kit" ] &&
        [ "$(cd "$kit" && cksum carplay_binpatch carplay_performance.sh carplay_performance_recover.sh \
            train unit.key unit.key.cksum)" = "$(cat "$kit/payload.cksum")" ] &&
        [ "$(cksum < "$kit/unit.key")" = "$(cat "$kit/unit.key.cksum")" ] &&
        [ "$(perf_train)" = "$(cat "$kit/train")" ] &&
        [ "$(perf_unit_key)" = "$(cat "$kit/unit.key")" ] || exit 1
    if [ "${PERF_INTERNAL_RECOVERY:-0}" != 1 ]; then
        perf_prepare_helper && cmp -s "$PERF_HELPER" "$kit/carplay_binpatch" || exit 1
        for script in carplay_performance.sh carplay_performance_recover.sh; do
            cmp -s "$PERF_VOLUME/Toolbox/scripts/$script" "$kit/$script" || exit 1
        done
    fi
    PERF_INTERNAL_RECOVERY=1
    perf_setup && perf_originals_valid &&
        "$kit/carplay_binpatch" --self-test >/dev/null
)
perf_remove_kit() (
    perf_setup || exit 1
    [ ! -L "$PERF_KIT" ] && [ ! -L "$PERF_KIT.staging" ] && [ ! -L "$PERF_KIT.previous" ] || exit 1
    rm -rf "$PERF_KIT" "$PERF_KIT.staging" "$PERF_KIT.previous"
)
