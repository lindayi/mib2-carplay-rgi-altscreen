#!/bin/bash
# Host-only fixture driver; invoked in Docker by scripts/test_altscreen_e2e.sh.
set -euo pipefail

diagnostics(){
    rc=$?
    if [ "$rc" -ne 0 ]; then
        for log in "${CASE_DIR:-/nonexistent}"/*.log; do
            [ -f "$log" ] || continue
            echo "----- $log"; tail -40 "$log"
        done
    fi
    exit "$rc"
}
trap diagnostics EXIT

new_fixture(){
    CASE_DIR=$(mktemp -d /tmp/altscreen-e2e.XXXXXX)
    ROOT=$CASE_DIR/root VOL=$CASE_DIR/vol
    P=$ROOT/mnt/system/etc/eso/production
    H=$ROOT/mnt/app/root/hooks
    RUNTIME=$ROOT/mnt/app/root/carplay-altscreen
    SCRIPTS=$VOL/Toolbox/scripts
    F=/fixture/ORIGINAL/files
    mkdir -p "$VOL" "$ROOT/eso/bin/apps" "$ROOT/eso/lib" "$ROOT/armle/usr/lib" "$P" \
        "$ROOT/mnt/system/etc/boot" "$ROOT/mnt/app/root" "$ROOT/mnt/app/eso/hmi/lsd/jars" \
        "$ROOT/dev/shmem" "$ROOT/tmp"
    cp -a /sd/. "$VOL/"
    cp "$F/_eso_bin_apps_dio_manager" "$ROOT/eso/bin/apps/dio_manager"
    cp "$F/_eso_lib_libairplay.so" "$ROOT/eso/lib/libairplay.so"
    cp "$F/_armle_usr_lib_libNmeBaseClasses.so" "$ROOT/armle/usr/lib/libNmeBaseClasses.so"
    cp "$F/_mnt_system_etc_eso_production_smartphone_integrator.json" "$P/smartphone_integrator.json"
    cp "$F/_mnt_system_etc_eso_production_dio_manager.json" "$P/dio_manager.json"
    cp /fixture/firewall-original/pf.conf "$ROOT/mnt/system/etc/pf.conf"
    cp /fixture/boot-diagnostics/startup.sh "$ROOT/mnt/system/etc/boot/startup.sh"
    echo "Current train = MHI2Q_US_AUG22_P5145" > "$ROOT/dev/shmem/version.txt"
    export ALTSCREEN_CHAIN_TESTING=1 ALTSCREEN_CHAIN_ROOT=$ROOT ALTSCREEN_CHAIN_VOLUME=$VOL
}

need(){ grep -Fq "$2" "$CASE_DIR/$1.log" || { echo "FAIL $1: missing $2"; exit 1; }; }
absent(){ if grep -Fq "$2" "$CASE_DIR/$1.log"; then echo "FAIL $1: unexpected $2"; exit 1; fi; }
run(){
    name=$1; shift
    if timeout 180 /bin/sh "$@" > "$CASE_DIR/$name.log" 2>&1; then
        echo "  ok   $name: rc=0"
    else
        rc=$?
        echo "  exit $name: rc=$rc"
        return "$rc"
    fi
}
expect_failure(){
    expected=$1; shift
    if run "$@"; then echo "FAIL: $1 unexpectedly succeeded"; exit 1; else
        actual=$?
        [ "$actual" -eq "$expected" ] || { echo "FAIL: $1 expected rc=$expected, got $actual"; exit 1; }
    fi
}
stub_mirror(){
    # Only the installed fixture is changed; the shipping ARM payload stays intact.
    cat > "$RUNTIME/bin/mirror/start_vehicle.sh" <<'MIRROR'
#!/bin/sh
echo "HOST_TEST_ONLY: mirror launcher stub (no ARM execution or video validation)"
exit "${HOST_TEST_MIRROR_RC:-0}"
MIRROR
    printf '#!/bin/sh\nexit 0\n' > "$RUNTIME/bin/mirror/stop_vehicle.sh"
    chmod 755 "$RUNTIME/bin/mirror/"{start,stop}_vehicle.sh
}
install(){
    run install "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
    need install 'INSTALL=PASS integrated=AltScreen+H264Tap+DecoderTap+Displayable3+Java80+RGI'
    need install 'RGI_COMPANION=PASS'
    for mascot_file in libcarplay_mascot.so mascots.rgba; do
        cmp "$VOL/Toolbox/carplay_alt_screen/mirror_display/release/$mascot_file" "$RUNTIME/bin/mirror/$mascot_file"
    done
    stub_mirror
}
stock_configs(){
    cmp "$F/_mnt_system_etc_eso_production_smartphone_integrator.json" "$P/smartphone_integrator.json"
    cmp "$F/_mnt_system_etc_eso_production_dio_manager.json" "$P/dio_manager.json"
}
restored(){
    stock_configs
    test ! -e "$H"
    test ! -e "$ROOT/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar"
    test ! -e "$RUNTIME"
    grep -q 'action=RESTORE state=COMMITTED' "$VOL/MMI-Cockpit-Carplay/logs/operations/TRANSACTION.log"
}
inject_cleanup_failures(){
    script=$1
    sed '/^case "$ACTION" in/,$d' "$script" > "$script.fault"
    cat >> "$script.fault" <<'FAULT'
sync(){ echo sync >> "$ALTSCREEN_CHAIN_ROOT/cleanup.attempts"; return "${FAIL_SYNC:-0}"; }
mount_app_ro(){ echo app-ro >> "$ALTSCREEN_CHAIN_ROOT/cleanup.attempts"; return "${FAIL_APP_RO:-0}"; }
mount_system_ro(){ echo system-ro >> "$ALTSCREEN_CHAIN_ROOT/cleanup.attempts"; return "${FAIL_SYSTEM_RO:-0}"; }
FAULT
    sed -n '/^case "$ACTION" in/,$p' "$script" >> "$script.fault"
    mv "$script.fault" "$script"
}

new_fixture
echo "Current train = MHI2Q_ER_AUG22_P5092" > "$ROOT/dev/shmem/version.txt"
expect_failure 1 wrong-firmware "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
need wrong-firmware 'requires MHI2Q_US_AUG22_P5145'
stock_configs
test ! -e "$RUNTIME"
echo "Current train = MHI2Q_US_AUG22_P5145" > "$ROOT/dev/shmem/version.txt"
atlas="$VOL/Toolbox/carplay_alt_screen/mirror_display/release/mascots.rgba"
mv "$atlas" "$atlas.saved"
expect_failure 1 missing-mascot "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
need missing-mascot 'map mascot artifact missing'
stock_configs
test ! -e "$RUNTIME"
mv "$atlas.saved" "$atlas"
install
preference=maps:/car/instrumentcluster/map?maneuverLayout=rightaligned
printf '%s\n' "$preference" > "$H/cluster_ui.url"
run upgrade-with-preference "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
test "$(cat "$H/cluster_ui.url")" = "$preference"
need upgrade-with-preference 'INSTALL=PASS integrated=AltScreen+H264Tap+DecoderTap+Displayable3+Java80+RGI'
stub_mirror
grep -Fq '"CARPLAY_PRELOAD_EXTRA=/mnt/app/root/carplay-altscreen/lib/libcarplay_altscreen.so"' \
    "$P/smartphone_integrator.json"
sed -n '/^INHERITED_PRELOAD=/,/^echo "\[startup\] preload/p' "$H/carplay_startup.sh" > "$CASE_DIR/pre.sh"
got=$(env -u LD_PRELOAD MENU_VIDEO=1 CARPLAY_PRELOAD_EXTRA=/mnt/app/root/carplay-altscreen/lib/libcarplay_altscreen.so \
    H=/mnt/app/root/hooks WLOG=/dev/null /bin/sh -c ". \"$CASE_DIR/pre.sh\"; echo \$LD_PRELOAD")
test "$got" = /mnt/app/root/carplay-altscreen/lib/libcarplay_altscreen.so:/mnt/app/root/hooks/libcarplay_hook.so

expect_failure 1 premature-remove "$RUNTIME/bin/rgi_companion.sh" remove
need premature-remove 'SI still references the RGI runtime'
test -s "$H/carplay_startup.sh"

export HOST_TEST_MIRROR_RC=23
expect_failure 23 failed-start "$SCRIPTS/start_mmi_cockpit_carplay_rx_test.sh"
need failed-start 'START=PASS profile=UNIVERSAL'
absent failed-start 'START=PASS integrated='
test ! -e "$RUNTIME/state/basevideo3.enabled"
test ! -e "$RUNTIME/state/start.pending"
unset HOST_TEST_MIRROR_RC
run start "$SCRIPTS/start_mmi_cockpit_carplay_rx_test.sh"
need start 'HOST_TEST_ONLY: mirror launcher stub'
need start 'START=PASS integrated=AltScreen+H264Tap+DecoderTap+Displayable3+Java80'
test -f "$RUNTIME/state/basevideo3.enabled"
test ! -e "$RUNTIME/state/start.pending"
run status "$SCRIPTS/status_mmi_cockpit_carplay_test.sh"
for result in HMI_CONTROL_PLANE=PASS UNIVERSAL_PRELOAD_CONFIG=ARMED RGI_NATIVE=INSTALLED \
    RGI_SI_CHILD=WRAPPER RGI_DIO_IDS=5/5; do need status "$result"; done

dd if=/dev/zero bs=1000 count=300 2>/dev/null | tr '\000' x > "$ROOT/tmp/carplay_java.log"
printf '\nPRIVATE_DESTINATION_TEST\n' >> "$ROOT/tmp/carplay_java.log"
printf 'ctx=81\nvideo=1\nnav=1\ntime_ms=123\naddress=PRIVATE_DESTINATION_TEST\n' > "$ROOT/tmp/carplay_cluster.ctx"
mkdir -p "$ROOT/ramdisk"
printf 'HEALTH_STATE=VIDEO_PROGRESS\nHEALTH_RESTART_COUNT=0\n' > "$ROOT/ramdisk/MMI-Cockpit-Carplay.mirror.health"
printf 'action=export_full failure=fixture\nPRIVATE_FAILURE_DETAIL\n' > "$ROOT/tmp/carplay_menu_action.failure.log"
printf 'PRIVATE_ROTATED_LOG\n' > "$ROOT/tmp/carplay_java.log.1"
printf 'pid=123\nstate=LIZARD\nexpires=123\nprivate=PRIVATE_FAILURE_DETAIL\n' > "$ROOT/ramdisk/carplay_mascot.status"
printf 'VCPANEL1 123 7 2 300 1 1\nPRIVATE_PANEL_DETAIL\n' > "$ROOT/ramdisk/carplay_vc_panel.status"
: > "$ROOT/ramdisk/carplay_vc_panel.status.123.new"
run status-health "$SCRIPTS/status_mmi_cockpit_carplay_test.sh"
need status-health 'HEALTH_STATE=VIDEO_PROGRESS'
(cd "$ROOT" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/before-export"
(cd "$VOL" && find . -type f ! -path './MMI-Cockpit-Carplay/logs/exports/*' -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/sd-before-export"
run export-only "$SCRIPTS/export_mmi_cockpit_diagnostics.sh"
need export-only 'EXPORT=PASS no_restore=YES no_restart=YES'
absent export-only 'PRIVATE_DESTINATION_TEST'
absent export-only 'PRIVATE_FAILURE_DETAIL'
need export-only 'MASCOT_REPORTED_STATE=LIZARD'
need export-only 'VC_PANEL_REPORTED_STATE=1'
absent export-only 'PRIVATE_PANEL_DETAIL'
exports=$VOL/MMI-Cockpit-Carplay/logs/exports
exported=$(find "$exports" -mindepth 1 -maxdepth 1 -type d)
test -n "$exported"
grep -Fq 'PRIVATE_DESTINATION_TEST' "$exported/private/carplay_java.log"
grep -Fq 'PRIVATE_FAILURE_DETAIL' "$exported/private/carplay_menu_action.failure.log"
grep -Fq 'PRIVATE_ROTATED_LOG' "$exported/private/carplay_java.log.1"
test "$(wc -c < "$exported/private/carplay_java.log")" -eq 262144
test ! -e "$exported/lsd.jxe"
run export-again "$SCRIPTS/export_mmi_cockpit_diagnostics.sh"
run export-summary "$SCRIPTS/carplay_mmi_action.sh" export_summary
summary_export=$(sed -n 's/^EXPORT_PATH=//p' "$CASE_DIR/export-summary.log")
test -n "$summary_export"
test "$(find "$summary_export/private" -type f | wc -l)" -eq 0
need export-summary 'PRIVATE_LOGS_REQUESTED=0'
test "$(find "$exports" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 3
(cd "$ROOT" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/after-export"
cmp "$CASE_DIR/before-export" "$CASE_DIR/after-export"
(cd "$VOL" && find . -type f ! -path './MMI-Cockpit-Carplay/logs/exports/*' -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/sd-after-export"
cmp "$CASE_DIR/sd-before-export" "$CASE_DIR/sd-after-export"
# A blocked export destination must fail without changing the installed unit.
mv "$exports" "$CASE_DIR/saved-exports"
: > "$exports"
expect_failure 1 export-blocked "$SCRIPTS/export_mmi_cockpit_diagnostics.sh"
(cd "$ROOT" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/blocked-export"
cmp "$CASE_DIR/before-export" "$CASE_DIR/blocked-export"
run restore "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
need restore 'RGI_NATIVE=REMOVED'
need restore 'RESTORE=PASS integrated='
test ! -e "$ROOT/ramdisk/carplay_vc_panel.status"
test ! -e "$ROOT/ramdisk/carplay_vc_panel.status.123.new"
restored

new_fixture
install
native=$RUNTIME/bin/altscreen_chain_test_universal.sh
cp "$native" "$CASE_DIR/native-original.sh"
sed '/^cmd_restore(){/a\    echo "INJECTED_NATIVE_RESTORE_FAILURE" >&2\n    return 77' \
    "$native" > "$native.fault"
mv "$native.fault" "$native"
expect_failure 77 interrupted-restore "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
need interrupted-restore 'INJECTED_NATIVE_RESTORE_FAILURE'
absent interrupted-restore 'RGI_NATIVE=REMOVED'
test -s "$H/carplay_startup.sh"
grep -q '"exec": "carplay_startup.sh"' "$P/smartphone_integrator.json"
test -f "$RUNTIME/state/transaction.pending"
cp "$CASE_DIR/native-original.sh" "$native"
run retry-native-restore "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
restored

new_fixture
install
inject_cleanup_failures "$RUNTIME/bin/rgi_companion.sh"
for operation in sync app system all; do
    export FAIL_SYNC=0 FAIL_APP_RO=0 FAIL_SYSTEM_RO=0
    case "$operation" in
        sync) FAIL_SYNC=73 ;;
        app) FAIL_APP_RO=74 ;;
        system) FAIL_SYSTEM_RO=75 ;;
        all) FAIL_SYNC=73 FAIL_APP_RO=74 FAIL_SYSTEM_RO=75 ;;
    esac
    : > "$ROOT/cleanup.attempts"
    expect_failure 1 "failed-cleanup-$operation" "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
    need "failed-cleanup-$operation" 'originals restored but RGI cleanup failed'
    absent "failed-cleanup-$operation" 'RESTORE=PASS integrated='
    stock_configs
    test -f "$RUNTIME/state/transaction.pending"
    test -f "$RUNTIME/bin/altscreen_chain_test.sh"
    printf 'sync\napp-ro\nsystem-ro\n' > "$CASE_DIR/expected-cleanup"
    cmp "$CASE_DIR/expected-cleanup" "$ROOT/cleanup.attempts"
done
unset FAIL_SYNC FAIL_APP_RO FAIL_SYSTEM_RO
cp /sd/Toolbox/scripts/rgi_companion.sh "$RUNTIME/bin/rgi_companion.sh"
run retry-cleanup "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
restored

new_fixture
inject_cleanup_failures "$SCRIPTS/rgi_companion.sh"
export FAIL_SYNC=73
expect_failure 1 failed-install "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
absent failed-install 'RGI_COMPANION=PASS'
absent failed-install 'INSTALL=PASS integrated='
need failed-install 'RGI sync failed'
stock_configs
test -f "$RUNTIME/state/transaction.pending"
unset FAIL_SYNC
cp /sd/Toolbox/scripts/rgi_companion.sh "$SCRIPTS/rgi_companion.sh"
cp /sd/Toolbox/scripts/rgi_companion.sh "$RUNTIME/bin/rgi_companion.sh"
stub_mirror
run recover-failed-install "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
restored

echo "AltScreen+RGI host e2e: strict exit codes, mirror stub, restore ordering, cleanup failures and retries PASS"
bash /tests/mirror_health_test.sh /sd/Toolbox/carplay_alt_screen/mirror_display/release
