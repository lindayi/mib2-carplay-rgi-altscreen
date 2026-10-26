#!/bin/sh
# Fixed-port/uplink integration adapted from yuedizhibo V3.8.
# Copyright (c) 2026 yuedizhibo. PolyForm Noncommercial 1.0.0 plus
# upstream additional terms; see altscreen_native/ and staged mirror notices.
# Sourced by the universal controller inside its backup/mount/rollback transaction.

FIREWALL_STATIC_RULE="pass in quick on carplay0 proto tcp from any to any port = 6031 allow-opts"
FIREWALL_VARIANT_BACKUP_DIR="$BACKUP_ROOT/firewall-variants-original"

firewall_final_newline(){
    last=$(tail -c 1 "$1" && printf '.') || return 1
    if [ "$last" = "$(printf '\n.')" ]; then echo 1; else echo 0; fi
}
strip_firewall_block(){
    final_nl=$(firewall_final_newline "$1") || return 1
    awk -v begin="$FIREWALL_BEGIN" -v end="$FIREWALL_END" -v final_nl="$final_nl" '
      function emit(line){if(wrote)printf "\n";printf "%s",line;wrote=1}
      $0==begin {if(inside||seen++)exit 9;inside=1;next}
      $0==end {if(!inside)exit 9;inside=0;next}
      !inside {emit($0)}
      END{if(inside)exit 9;if(wrote&&final_nl)printf "\n"}
    ' "$1"
}
pf_variant_names(){
    for file in "$(dirname -- "$(p "$LIVE_PF_CONF")")"/pf.*.conf; do
        [ -f "$file" ] || continue
        name=${file##*/}
        case "$name" in *[!A-Za-z0-9._-]*) echo "FAIL: unsupported firewall filename" >&2; return 1 ;; esac
        printf '%s\n' "$name"
    done
}
verify_firewall_variant_backup() (
    dir=$FIREWALL_VARIANT_BACKUP_DIR
    [ -f "$dir/COMPLETE" ] && [ -f "$dir/list" ] || return 1
    [ "$(cksum < "$dir/list")" = "$(cat "$dir/list.cksum")" ] || return 1
    while IFS= read -r name; do
        case "$name" in pf.*.conf) ;; *) return 1 ;; esac
        case "$name" in *[!A-Za-z0-9._-]*) return 1 ;; esac
        [ -f "$dir/$name" ] && [ -f "$dir/$name.cksum" ] || return 1
        [ "$(cksum < "$dir/$name")" = "$(cat "$dir/$name.cksum")" ] || return 1
        case "$(cat "$dir/$name.mode")" in 644|755) ;; *) return 1 ;; esac
    done < "$dir/list"
)
backup_firewall_variants() (
    if [ -e "$FIREWALL_VARIANT_BACKUP_DIR" ]; then
        verify_firewall_variant_backup || return 1
        names=$(pf_variant_names) || return 1
        for name in $names; do
            grep -Fxq "$name" "$FIREWALL_VARIANT_BACKUP_DIR/list" || {
                say "FAIL: new firewall variant has no original backup: $name"; return 1;
            }
        done
        return 0
    fi
    dir="$FIREWALL_VARIANT_BACKUP_DIR.new.$$"
    [ ! -e "$dir" ] || return 1
    ensure_dirs "$dir" || return 1
    : > "$dir/list" || return 1
    names=$(pf_variant_names) || return 1
    for name in $names; do
        live="$(dirname -- "$(p "$LIVE_PF_CONF")")/$name"
        cp "$live" "$dir/$name" && same_bytes "$live" "$dir/$name" || return 1
        cksum < "$dir/$name" > "$dir/$name.cksum" || return 1
        mode=644; [ ! -x "$live" ] || mode=755
        printf '%s\n' "$mode" > "$dir/$name.mode" || return 1
        printf '%s\n' "$name" >> "$dir/list" || return 1
    done
    cksum < "$dir/list" > "$dir/list.cksum" || return 1
    sync || return 1
    touch "$dir/COMPLETE" && mv "$dir" "$FIREWALL_VARIANT_BACKUP_DIR" || return 1
    sync || return 1
    verify_firewall_variant_backup
)
stage_static_firewall_file() (
    live=$1; staged=$2
    strip_firewall_block "$live" > "$staged.clean" || return 1
    final_nl=$(firewall_final_newline "$staged.clean") || return 1
    awk -v begin="$FIREWALL_BEGIN" -v end="$FIREWALL_END" -v rule="$FIREWALL_STATIC_RULE" -v final_nl="$final_nl" '
      function emit(line){if(wrote)printf "\n";printf "%s",line;wrote=1}
      {
        key=$0; sub(/\r$/, "", key)
        if(!done && key ~ /^[ \t]*block[ \t]+((drop|return)[ \t]+)?in[ \t]+(quick[ \t]+)?on[ \t]+carplay0[ \t]+all[ \t]*$/) {
          emit(begin); emit(rule); emit(end); done=1
        }
        emit($0)
      }
      END{if(!done)exit 9;if(wrote&&final_nl)printf "\n"}
    ' "$staged.clean" > "$staged"
    result=$?
    if [ "$result" = 9 ]; then
        # No terminal filter in this variant: do not add an unrelated rule.
        cp "$staged.clean" "$staged" || return 1
    elif [ "$result" != 0 ]; then
        return 1
    fi
    strip_firewall_block "$staged" > "$staged.verify" &&
        same_bytes "$staged.clean" "$staged.verify" || {
            say "FAIL: firewall candidate changed unrelated configuration: $live"; return 1;
        }
    if grep -Fxq "$FIREWALL_BEGIN" "$staged"; then
        # Only our literal filter rule changed. Parsing untouched inactive ALTQ
        # sections can query absent interfaces even with pfctl -n (PPP on MU1316).
        printf '%s\n' "$FIREWALL_STATIC_RULE" > "$staged.rule" || return 1
        pfctl_rel=$(locate_first "/armle/sbin/pfctl /mnt/app/armle/sbin/pfctl /sbin/pfctl") || {
            say "FAIL: pfctl unavailable; firewall syntax was not checked"; return 1;
        }
        "$(p "$pfctl_rel")" -n -f "$staged.rule" || {
            say "FAIL: firewall rule syntax rejected: $live"; return 1;
        }
        say "FIREWALL_VALIDATE=FILTER_FRAGMENT OEM_REMAINDER=UNCHANGED file=$live"
    fi
)
install_static_firewall_rules() (
    verify_firewall_variant_backup || return 1
    dir="$STATE_DIR/firewall-stage.$$"
    [ ! -e "$dir" ] && ensure_dirs "$dir" || return 1
    names=$(pf_variant_names) || return 1
    pf_dir=$(dirname -- "$(p "$LIVE_PF_CONF")")
    cleanup(){
        for name in pf.conf $names; do
            rm -f "$dir/$name" "$dir/$name.clean" "$dir/$name.verify" "$dir/$name.rule"
        done
        rmdir "$dir"
    }
    trap cleanup EXIT
    # Validate every candidate before publishing any live file.
    for name in pf.conf $names; do
        stage_static_firewall_file "$pf_dir/$name" "$dir/$name" || return 1
    done
    for name in pf.conf $names; do
        mode=644; [ ! -x "$pf_dir/$name" ] || mode=755
        stage_and_publish "$dir/$name" "$pf_dir/$name" "$mode" || return 1
        same_bytes "$dir/$name" "$pf_dir/$name" || return 1
        rm -f "$dir/$name" "$dir/$name.clean" || return 1
    done
    rm -f "$STATE_DIR/FIREWALL_PATCHED" || return 1
    if grep -Fxq "$FIREWALL_STATIC_RULE" "$pf_dir/pf.conf"; then
        variants=$(printf '%s' "$names" | tr '\n' ',')
        say "FIREWALL=TYPE111_FIXED_PORT port=6031 variants=${variants:-NONE} effective=AFTER_REBOOT"
    else
        say "FIREWALL=TYPE111_STATIC_UNAVAILABLE reason=no_recognized_main_terminal_block runtime_fallback=EPHEMERAL_EXACT_PORT"
    fi
)
restore_firewall_variants() (
    [ -e "$FIREWALL_VARIANT_BACKUP_DIR" ] || return 0
    verify_firewall_variant_backup || return 1
    pf_dir=$(dirname -- "$(p "$LIVE_PF_CONF")")
    while IFS= read -r name; do
        src="$FIREWALL_VARIANT_BACKUP_DIR/$name"
        stage_and_publish "$src" "$pf_dir/$name" "$(cat "$src.mode")" || return 1
        same_bytes "$src" "$pf_dir/$name" || return 1
    done < "$FIREWALL_VARIANT_BACKUP_DIR/list"
)
