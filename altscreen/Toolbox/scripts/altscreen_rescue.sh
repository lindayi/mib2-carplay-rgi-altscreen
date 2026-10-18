#!/bin/sh
# Manual SD-only recovery, never a normal-MMI action. No implicit reboot.
set -u
HERE=$(CDPATH='' cd -P -- "$(dirname -- "$0")" && pwd -P) || exit 1
case "${1:-}" in
    recover) exec /bin/sh "$HERE/altscreen_transaction.sh" recover ;;
    restore-original)
        [ "${2:-}" = --confirm-stock-rescue ] || {
            echo "FAIL: use restore-original --confirm-stock-rescue; original unit-bound backup required"
            exit 2
        }
        ALTSCREEN_RESCUE=1; export ALTSCREEN_RESCUE
        exec /bin/sh "$HERE/altscreen_transaction.sh" rescue --confirm-stock-rescue ;;
    undo)
        [ "${2:-}" = --confirm-pre-rescue ] || exit 2
        exec /bin/sh "$HERE/altscreen_transaction.sh" undo-rescue ;;
    *) echo "usage: altscreen_rescue.sh recover|restore-original --confirm-stock-rescue|undo --confirm-pre-rescue"; exit 2 ;;
esac
