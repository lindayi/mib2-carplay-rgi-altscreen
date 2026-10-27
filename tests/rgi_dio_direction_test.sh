#!/bin/sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
sed -n '/^add_ids()/,/^}/p;/^del_ids()/,/^}/p;/^patch_dio()/,/^}/p' \
    "$ROOT/altscreen/Toolbox/scripts/rgi_companion.sh" > "$T/functions"
. "$T/functions"
DIO="$T/dio"
printf '## untouched\n"MessagesSentByAccessory":["0x7777","0x5202"],\n"MessagesReceivedFromDevice":["0x5203","0x8888"],\n' > "$DIO"
patch_dio
grep 'MessagesSentByAccessory' "$DIO" | grep -q '"0x5203"'
! grep 'MessagesReceivedFromDevice' "$DIO" | grep -q '"0x5203"'
grep 'MessagesReceivedFromDevice' "$DIO" | grep -q '"0x5202"'
grep -q '0x7777' "$DIO" && grep -q '0x8888' "$DIO"
! grep -q '0xA10' "$DIO"
cp "$DIO" "$T/once"
patch_dio
cmp "$DIO" "$T/once"
printf '"MessagesReceivedFromDevice":[]\n' >> "$DIO"
cp "$DIO" "$T/bad"
if patch_dio; then exit 1; fi
cmp "$DIO" "$T/bad"
printf '"MessagesSentByAccessory":["0xAE00","0xae00"],\n"MessagesReceivedFromDevice":[],\n' > "$DIO"
cp "$DIO" "$T/bad-case"
if patch_dio; then exit 1; fi
cmp "$DIO" "$T/bad-case"
echo 'RGI_DIO_DIRECTION_AND_GROUPS=PASS'
