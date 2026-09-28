#!/bin/bash
set -euo pipefail
PROJECT=${1:?project directory}
T=$(mktemp -d /tmp/carplay-settings-test.XXXXXX)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/hooks" "$T/app"
cp "$PROJECT/deploy/smartphone_integrator/carplay_settings.sh" "$T/hooks/"
cp "$PROJECT/tests/fixtures/carplay-preferences.txt" "$T/preferences"
export CP_SETTINGS_FILE="$T/preferences"
source "$T/hooks/carplay_settings.sh"
test "$(cp_setting enabled 1)" = 0
for key in mode layout distance road lanes progress text_size road_scroll background zoom zoom_speed touchpad touch_sensitivity recovery verbose info_default info_road info_return preset mascot; do
    cp_setting "$key" 0 >/dev/null
done
cp "$T/preferences" "$T/good"
cp "$PROJECT/tests/fixtures/carplay-preferences-v2.txt" "$T/preferences"
test "$(cp_setting mascot 2)" = 0
cmp "$T/preferences" "$PROJECT/tests/fixtures/carplay-preferences-v2.txt"
echo 'mascot=0' >> "$T/preferences"
if cp_setting mascot 0; then echo "FAIL: mixed v2/v3 accepted"; exit 1; fi
cp "$PROJECT/tests/fixtures/carplay-preferences-v1.txt" "$T/preferences"
for key in enabled info_default info_road info_return preset mascot; do test "$(cp_setting "$key" 1)" = 0; done
cmp "$T/preferences" "$PROJECT/tests/fixtures/carplay-preferences-v1.txt"
echo 'preset=0' >> "$T/preferences"
if cp_setting enabled 1; then echo "FAIL: mixed v1/v2 accepted"; exit 1; fi
sed '/info_return=/d' "$T/good" > "$T/preferences"
if cp_setting enabled 1; then echo "FAIL: incomplete v2 accepted"; exit 1; fi
for preset in 0 1 2 3; do
    sed "s/preset=0/preset=$preset/" "$T/good" > "$T/preferences"
    case "$preset" in
        0|2) expected="1 1 1 1 0 0 0 ";;
        1) expected="1 0 1 0 0 0 1 ";;
        3) expected="1 1 1 1 1 0 0 ";;
    esac
    actual=""
    for key in distance road lanes progress text_size road_scroll background; do actual="$actual$(cp_setting "$key" 0) "; done
    test "$actual" = "$expected"
done
cp "$T/good" "$T/preferences"
for mascot in 0 1 2; do
    sed "s/mascot=0/mascot=$mascot/" "$T/good" > "$T/preferences"
    test "$(cp_setting mascot 0)" = "$mascot"
done
cp "$T/good" "$T/preferences"
echo 'enabled=1' >> "$T/preferences"
if cp_setting enabled 1; then echo "FAIL: duplicate setting accepted"; exit 1; fi
cp "$T/good" "$T/preferences"
cat > "$T/app/dio_manager" <<'DIO'
#!/bin/sh
printf '%s|%s|%s\n' "$$" "${LD_PRELOAD:-}" "$*" > "$TEST_DIO"
DIO
cat > "$T/hooks/carplay_monitor.sh" <<'MONITOR'
#!/bin/sh
echo "$1" > "$TEST_MONITOR"
MONITOR
chmod +x "$T/app/dio_manager" "$T/hooks/carplay_monitor.sh"
touch "$T/hooks/libcarplay_hook.so" "$T/hooks/carplay_processes.sh"
export TEST_DIO="$T/dio" TEST_MONITOR="$T/monitor"
export H="$T/hooks" DIODIR="$T/app" WLOG="$T/wrapper.log" OWNER_FILE="$T/owner"
export MENU_SESSION_FILE="$T/session" MENU_VERBOSE_FILE="$T/verbose"
export CARPLAY_PRELOAD_EXTRA=/mnt/app/root/carplay-altscreen/lib/libcarplay_altscreen.so
bash "$PROJECT/deploy/smartphone_integrator/carplay_startup.sh" alpha beta
test ! -f "$TEST_MONITOR"
grep -q '||alpha beta$' "$TEST_DIO"
grep -q '^enabled=0$' "$MENU_SESSION_FILE"
grep -q '^video=0$' "$MENU_SESSION_FILE"
sed 's/enabled=0/enabled=1/;s/mode=0/mode=2/' "$T/good" > "$T/preferences"
bash "$PROJECT/deploy/smartphone_integrator/carplay_startup.sh" gamma
for i in $(seq 1 100); do [ ! -f "$TEST_MONITOR" ] || break; sleep .01; done
grep -q '^enabled=1$' "$MENU_SESSION_FILE"
grep -q '^video=0$' "$MENU_SESSION_FILE"
grep -q "$T/hooks/libcarplay_hook.so|gamma$" "$TEST_DIO"
if grep -q 'libcarplay_altscreen.so' "$TEST_DIO"; then echo "FAIL: Audi-map profile loaded AltScreen"; exit 1; fi
rm -f "$TEST_MONITOR"
sed 's/enabled=0/enabled=1/;s/verbose=0/verbose=1/' "$T/good" > "$T/preferences"
bash "$PROJECT/deploy/smartphone_integrator/carplay_startup.sh" delta
grep -q '^video=1$' "$MENU_SESSION_FILE"
grep -q 'libcarplay_altscreen.so:' "$TEST_DIO"
test -f "$MENU_VERBOSE_FILE"
for i in $(seq 1 100); do [ ! -f "$TEST_MONITOR" ] || break; sleep .01; done
test -f "$TEST_MONITOR"
echo 'invalid' > "$T/preferences"
rm -f "$TEST_MONITOR"
bash "$PROJECT/deploy/smartphone_integrator/carplay_startup.sh" epsilon
grep -q '^enabled=0$' "$MENU_SESSION_FILE"
grep -q '^config_error=1$' "$MENU_SESSION_FILE"
grep -q '||epsilon$' "$TEST_DIO"
test ! -e "$TEST_MONITOR"

# Normal MMI video actions use only tmp control files and the existing mirror launcher.
ROOT="$T/unit";VOL="$T/sd";RUNTIME="$ROOT/mnt/app/root/carplay-altscreen"
mkdir -p "$RUNTIME/bin/mirror" "$RUNTIME/state" "$ROOT/mnt/app/root/hooks" \
    "$ROOT/mnt/persist/var/app/carplay_altscreen" "$ROOT/tmp" "$ROOT/ramdisk" "$VOL/Toolbox"
cp "$T/hooks/carplay_settings.sh" "$ROOT/mnt/app/root/hooks/"
cp "$T/good" "$ROOT/mnt/persist/var/app/carplay_altscreen/preferences"
export ALTSCREEN_CHAIN_TESTING=1 ALTSCREEN_CHAIN_ROOT="$ROOT" ALTSCREEN_CHAIN_VOLUME="$VOL"
cat > "$RUNTIME/bin/mirror/start_vehicle.sh" <<'START'
#!/bin/sh
echo start >> "$ALTSCREEN_CHAIN_ROOT/actions"
START
cat > "$RUNTIME/bin/mirror/stop_vehicle.sh" <<'STOP'
#!/bin/sh
echo stop >> "$ALTSCREEN_CHAIN_ROOT/actions"
STOP
SCRIPT="$PROJECT/altscreen/Toolbox/scripts/carplay_mmi_action.sh"
sh "$SCRIPT" sync_mirror
test "$(cat "$ROOT/actions")" = stop
if sh "$SCRIPT" restart_video; then echo "FAIL: disabled restart accepted"; exit 1; fi
sed 's/enabled=0/enabled=1/' "$T/good" > "$ROOT/mnt/persist/var/app/carplay_altscreen/preferences"
printf 'pid=%s\nenabled=1\nvideo=1\nverbose=0\nconfig_error=0\n' "$$" > "$ROOT/ramdisk/carplay_menu_session"
echo "$$" > "$ROOT/ramdisk/carplay_supervisor.owner"
touch "$RUNTIME/state/basevideo3.enabled"
sh "$SCRIPT" sync_mirror
test -f "$ROOT/tmp/mmi-mirror-active"
sh "$SCRIPT" restart_video
test "$(tail -n 2 "$ROOT/actions" | tr '\n' ' ')" = "stop start "
touch "$RUNTIME/state/transaction.pending"
if sh "$SCRIPT" restart_video; then echo "FAIL: incomplete install restart accepted"; exit 1; fi
if sh "$SCRIPT" restore; then echo "FAIL: MMI helper exposed restore"; exit 1; fi
if sh "$SCRIPT" reboot; then echo "FAIL: MMI helper exposed reboot"; exit 1; fi
echo "Settings runtime: strict parser, stock bypass, RGI-only profile, preload isolation and safe action whitelist PASS"
