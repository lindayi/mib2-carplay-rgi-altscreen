#!/bin/bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
TXN="$TMP/txn"
mkdir -p "$TXN" "$TMP/source/empty" "$TMP/destination"
printf 'snapshot-data\n' > "$TMP/source/file"
awk '/^snapshot_find\(\)/ { copy=1 } /^snapshot_item\(\)/ { exit } copy' \
    "$ROOT/altscreen/Toolbox/scripts/altscreen_transaction.sh" > "$TMP/functions"
source "$TMP/functions"
log(){ printf '%s\n' "$*"; }
ensure_dirs(){ mkdir -p "$@"; }
FSDOS_CASE=parent
find(){
    command find "$@"
    case "$FSDOS_CASE" in
        parent) echo 'find: ./folder/..: Filename too long' >&2; return 1 ;;
        mixed) echo 'find: ./folder/..: Filename too long' >&2
               echo 'find: ./unreadable: Permission denied' >&2; return 1 ;;
    esac
}
cp(){
    case "$1" in -R|-r) echo 'recursive copies must not be used' >&2; return 1 ;; esac
    command cp "$@"
}
copy_snapshot_tree "$TMP/source" "$TMP/destination"
same_dir "$TMP/source" "$TMP/destination"
test -d "$TMP/destination/empty"
FSDOS_CASE=mixed
if copy_snapshot_tree "$TMP/source" "$TMP/destination"; then
    echo 'Unexpected acceptance of a real read failure' >&2; exit 1
fi
FSDOS_CASE=none
ln -s "$TMP/source/file" "$TMP/source/link"
if copy_snapshot_tree "$TMP/source" "$TMP/destination"; then
    echo 'Snapshot followed a symbolic link' >&2; exit 1
fi
echo 'TRANSACTION_FSDOS_SNAPSHOT=PASS'
