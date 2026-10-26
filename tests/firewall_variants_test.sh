#!/bin/sh
set -eu
ROOT=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT
BACKUP_ROOT="$TEST_ROOT/backup"
STATE_DIR="$TEST_ROOT/state"
LIVE_PF_CONF=/mnt/system/etc/pf.conf
TESTING=1
FIREWALL_BEGIN="# BEGIN ALTSCREEN TYPE111 FIREWALL"
FIREWALL_END="# END ALTSCREEN TYPE111 FIREWALL"
p(){ printf '%s%s\n' "$TEST_ROOT" "$1"; }
say(){ printf '%s\n' "$*"; }
ensure_dirs(){ mkdir -p "$@"; }
same_bytes(){ cmp -s "$1" "$2"; }
stage_and_publish(){ cp "$1" "$2" && chmod "$3" "$2"; }
locate_first(){ [ -x "$(p /armle/sbin/pfctl)" ] && printf '/armle/sbin/pfctl\n'; }
. "$ROOT/altscreen/Toolbox/scripts/altscreen_firewall.sh"
PF_DIR=$(dirname "$(p "$LIVE_PF_CONF")")
mkdir -p "$PF_DIR" "$BACKUP_ROOT" "$STATE_DIR" "$(p /armle/sbin)"
cp "$ROOT/tests/fixtures/pfctl-filter-validator.sh" "$(p /armle/sbin/pfctl)"
chmod 755 "$(p /armle/sbin/pfctl)"
printf 'pass out all\nblock in quick on carplay0 all\n' > "$PF_DIR/pf.conf"
cp "$PF_DIR/pf.conf" "$PF_DIR/pf.ecm0.conf"
printf 'pass out all\n' > "$PF_DIR/pf.mlan0.conf"
printf 'set timeout interval 10\r\naltq on ppp0 cbq bandwidth 256Kb queue { normal }\r\nqueue normal bandwidth 100%% cbq(default)\r\nnat on ppp0 from 10.0.0.0/8 to any -> (ppp0)\r\nblock in quick on carplay0 all' > "$PF_DIR/pf.ppp0.conf"
cp "$PF_DIR/pf.ppp0.conf" "$TEST_ROOT/ppp-original"
# Demonstrate why the old complete-variant validation fails without PPP.
! "$(p /armle/sbin/pfctl)" -n -f "$PF_DIR/pf.ppp0.conf"
chmod 755 "$PF_DIR/pf.ecm0.conf"
cp "$PF_DIR/pf.conf" "$TEST_ROOT/original"
backup_firewall_variants
install_static_firewall_rules
for name in pf.conf pf.ecm0.conf pf.ppp0.conf; do
    grep -Fxq "$FIREWALL_STATIC_RULE" "$PF_DIR/$name"
    [ "$(grep -n '^pass in quick' "$PF_DIR/$name" | cut -d: -f1)" -lt \
      "$(grep -n '^block in' "$PF_DIR/$name" | cut -d: -f1)" ]
done
strip_firewall_block "$PF_DIR/pf.ppp0.conf" > "$TEST_ROOT/ppp-remainder"
cmp "$TEST_ROOT/ppp-original" "$TEST_ROOT/ppp-remainder"
! grep -q '6031' "$PF_DIR/pf.mlan0.conf"
[ -x "$PF_DIR/pf.ecm0.conf" ]
backup_firewall_variants
install_static_firewall_rules
[ "$(grep -c '^pass in quick' "$PF_DIR/pf.conf")" = 1 ]
restore_firewall_variants
cmp "$TEST_ROOT/original" "$PF_DIR/pf.ecm0.conf"
cmp "$TEST_ROOT/ppp-original" "$PF_DIR/pf.ppp0.conf"
# Genuine validator failures are never bypassed in testing or production.
cp "$PF_DIR/pf.conf" "$TEST_ROOT/main-before"
PF_TEST_VALIDATION_FAIL=1; export PF_TEST_VALIDATION_FAIL
! install_static_firewall_rules
cmp "$TEST_ROOT/main-before" "$PF_DIR/pf.conf"
PF_TEST_VALIDATION_FAIL=0
# Validate all variants before publishing; malformed blocks must leave main unchanged.
cp "$PF_DIR/pf.conf" "$TEST_ROOT/main-before"
printf '%s\n' "$FIREWALL_BEGIN" >> "$PF_DIR/pf.ecm0.conf"
! install_static_firewall_rules
cmp "$TEST_ROOT/main-before" "$PF_DIR/pf.conf"
restore_firewall_variants
printf 'new original\n' > "$PF_DIR/pf.extra0.conf"
! backup_firewall_variants
rm "$PF_DIR/pf.extra0.conf"
printf 'corruption\n' >> "$FIREWALL_VARIANT_BACKUP_DIR/pf.ecm0.conf"
! verify_firewall_variant_backup
! restore_firewall_variants
echo "firewall_variants_test: fixed port, idempotence, exact restore and failure isolation PASS"
