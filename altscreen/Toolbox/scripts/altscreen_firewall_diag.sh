#!/bin/sh
# Read-only, bounded firewall provenance adapted from V3.8 flat_firewall_sources.
# Raw source detail belongs only to explicitly private/full diagnostic captures.
set -u
ROOT=""
if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
    ROOT=${ALTSCREEN_CHAIN_ROOT:-}
    case "$ROOT" in /work/build/*|/repo/build/*|/tmp/*|/var/tmp/*) ;; *) exit 2 ;; esac
fi
rule="pass in quick on carplay0 proto tcp from any to any port = 6031 allow-opts"
echo "FIREWALL_SOURCES_VERSION=1"
echo "SOURCE_RULES_ARE_NOT_PROOF_OF_LIVE_LOADED_RULES=YES"
count=0
for f in "$ROOT/mnt/system/etc"/* "$ROOT/etc"/* \
         "$ROOT/mnt/system/etc"/scripts/* "$ROOT/etc"/scripts/* \
         "$ROOT/armle/scripts"/* "$ROOT/mnt/system/etc"/boot/* \
         "$ROOT/var"/*pf* "$ROOT/tmp"/*pf*; do
    [ -f "$f" ] && [ ! -L "$f" ] || continue
    [ "$count" -lt 40 ] || { echo "SOURCE_LIMIT_REACHED=40"; break; }
    size=$(wc -c < "$f") || continue
    [ "$size" -gt 0 ] && [ "$size" -le 262144 ] || continue
    case "${f##*/}" in
        pf.conf|pf.*.conf) kind=RULESET ;;
        *) grep -q -e pfctl -e carplay0 "$f" || continue; kind=LOADER ;;
    esac
    count=$((count + 1))
    echo "SOURCE_BEGIN path=${f#"$ROOT"} kind=$kind cksum_size=$(cksum < "$f")"
    if [ "$kind" = RULESET ]; then
        if grep -Fxq "$rule" "$f"; then echo "FIXED_6031=PRESENT"
        else echo "FIXED_6031=ABSENT"; fi
    fi
    # Keep each source excerpt and the combined report bounded.
    grep -n -e pfctl -e carplay0 -e 'pf[.].*conf' "$f" | head -n 8 | cut -c 1-512
    echo SOURCE_END
done
echo "FIREWALL_SOURCES_END files=$count"
