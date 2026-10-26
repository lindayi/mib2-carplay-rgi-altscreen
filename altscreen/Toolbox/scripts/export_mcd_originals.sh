#!/bin/sh
# Private, read-only head-unit export. Only the SD card is written.
set -eu
umask 077

fail() { echo "MCD_EXPORT=FAIL: $*" >&2; exit 1; }
SD=/net/mmx/fs/sda0
BIN=/net/mmx/mnt/app/armle/usr/sbin/mcd
CONF=/net/mmx/etc/mcd.conf
SYSTEM_CONF=/net/mmx/mnt/system/etc/mcd.conf

[ "$#" -eq 0 ] || fail "This exporter takes no arguments."
[ -d "$SD/Toolbox" ] || fail "Put this Toolbox SD in slot 1."
for source in "$BIN" "$CONF" "$SYSTEM_CONF"; do
    [ -f "$source" ] && [ -r "$source" ] || fail "Cannot read $source"
done

stamp=$(date '+%Y%m%d-%H%M%S') || fail "Cannot read clock"
OUT="$SD/Dump/mcd-original-$stamp-$$"
[ ! -L "$SD/Dump" ] || fail "SD Dump folder must not be a symbolic link"
[ ! -e "$OUT" ] && [ ! -L "$OUT" ] || fail "Export folder already exists: $OUT"
if ! mkdir -p "$SD/Dump"; then
    mount -uw "$SD" || fail "Cannot make the SD writable"
    mkdir -p "$SD/Dump" || fail "Cannot create SD Dump folder"
fi
if ! mkdir "$OUT"; then
    mount -uw "$SD" || fail "Cannot make the SD writable"
    mkdir "$OUT" || fail "Cannot create new export folder"
fi

copy_checked() {
    before=$(cksum < "$1") || fail "Cannot checksum $1"
    cp "$1" "$OUT/$2" || fail "Cannot copy $1"
    cmp -s "$1" "$OUT/$2" || fail "Copy differs from $1"
    after=$(cksum < "$1") || fail "Cannot recheck $1"
    copied=$(cksum < "$OUT/$2") || fail "Cannot checksum exported $2"
    [ "$before" = "$after" ] && [ "$after" = "$copied" ] || fail "Source changed: $1"
    printf '%s %s\n' "$after" "$1" >> "$OUT/SOURCE-CKSUMS.txt" || fail "Cannot record $1"
}
copy_checked "$BIN" mcd
copy_checked "$CONF" mcd.conf
copy_checked "$SYSTEM_CONF" mcd.system.conf
(
    cd "$OUT" && cksum mcd mcd.conf mcd.system.conf > CKSUMS.txt
) || fail "Cannot checksum exported files"
sync || fail "Cannot flush SD writes"
echo "MCD_EXPORT=PASS"
echo "Export: $OUT"
echo "Installed files unchanged; no process was stopped or restarted."
