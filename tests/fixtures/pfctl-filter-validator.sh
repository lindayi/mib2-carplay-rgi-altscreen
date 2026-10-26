#!/bin/sh
# Fixture for the production invocation, not a replacement PF syntax parser.
set -eu
[ "$#" = 3 ] && [ "$1" = -n ] && [ "$2" = -f ] || exit 2
if grep -q '^altq on.*ppp0' "$3"; then
    echo 'pfctl: SIOCGIFMTU: No such device or address' >&2
    exit 1
fi
[ "${PF_TEST_VALIDATION_FAIL:-0}" = 0 ] || exit 1
[ "$(cat "$3")" = 'pass in quick on carplay0 proto tcp from any to any port = 6031 allow-opts' ] || exit 1
[ "$(wc -l < "$3")" -eq 1 ] || exit 1
