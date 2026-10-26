#!/bin/bash
# Disposable package/stock fixture. Staged mode uses package scripts, build pins
# and real ARM payloads unchanged; source-overlay mode is explicitly developmental.
# ARM binaries are checked/copied, never replaced with synthetic binaries or run.
set -euo pipefail
WORK=/work/build/installer-transaction-tests
mkdir -p "$WORK"
RUN="$WORK/run-$(date +%Y%m%d-%H%M%S)-$$"
mkdir "$RUN"
CASE_DIR=""
finish(){
    rc=$?
    if [ "$rc" != 0 ]; then
        if [ -n "$CASE_DIR" ]; then
            for f in "$CASE_DIR"/*.log; do [ ! -f "$f" ] || { echo "=== $f"; tail -45 "$f"; }; done
        fi
    else
        for case_dir in "$RUN"/*; do
            rm -rf "$case_dir/root" "$case_dir/vol" "$case_dir/saved-exports"
            rm -f "$case_dir/original-si"
        done
        echo "Fixture logs retained: $RUN (private stock fixture copies removed)"
    fi
    exit "$rc"
}
trap finish EXIT
PACKAGE_MODE="${INSTALLER_PACKAGE_MODE:-source-overlay}"
case "$PACKAGE_MODE" in
    source-overlay|staged) ;;
    *) echo "FAIL: unsupported INSTALLER_PACKAGE_MODE=$PACKAGE_MODE"; exit 2 ;;
esac
echo "TEST_PACKAGE_MODE=$PACKAGE_MODE"
if [ "$PACKAGE_MODE" = staged ]; then
    # Require inventory coverage for the integrated entry points, recovery
    # helpers and actual executable payloads, not merely hashes of other files.
    python3 - <<'PY'
import pathlib, struct
root = pathlib.Path("/sd")
manifest = root / "SHA256SUMS-SD.txt"
assert manifest.is_file(), "staged package has no SHA256SUMS-SD.txt"
listed = set()
for line in manifest.read_text().splitlines():
    if not line.strip():
        continue
    digest, name = line.split(maxsplit=1)
    assert len(digest) == 64 and all(c in "0123456789abcdefABCDEF" for c in digest), "invalid SHA256 entry"
    name = name.lstrip("*")
    if name.startswith("./"):
        name = name[2:]
    path = pathlib.PurePosixPath(name)
    assert not path.is_absolute() and ".." not in path.parts, "unsafe package inventory path"
    assert name not in listed, "duplicate package inventory path: " + name
    listed.add(name)
scripts = [
    "install_mmi_cockpit_carplay_rx.sh", "start_mmi_cockpit_carplay_rx_test.sh",
    "stop_mmi_cockpit_carplay_test.sh", "altscreen_chain_test.sh",
    "altscreen_chain_test_universal.sh", "rgi_companion.sh",
    "export_mmi_cockpit_diagnostics.sh", "altscreen_transaction.sh",
    "altscreen_rescue.sh", "altscreen_firewall_diag.sh",
]
payload = "Toolbox/carplay_alt_screen/"
arm = [
    "universal/libcarplay_altscreen.so", "rgi/libcarplay_hook.so",
    "rgi/maneuver_render", "mirror_display/release/carplay-alt111-mirror-display",
    "mirror_display/release/libcarplay_mascot.so",
]
required = ["Toolbox/scripts/" + name for name in scripts]
required += [payload + name for name in arm]
required += [payload + "hmi/carplay_hook-basevideo3.jar",
             payload + "mirror_display/release/mascots.rgba"]
for name in required:
    assert name in listed, "required staged file is not hash-inventoried: " + name
    assert (root / name).is_file(), "missing staged file: " + name
for name in arm:
    with (root / (payload + name)).open("rb") as stream:
        header = stream.read(20)
    assert header[:6] == b"\x7fELF\x01\x01" and len(header) == 20, "expected ELF32 little-endian: " + name
    assert struct.unpack_from("<H", header, 18)[0] == 40, "expected ARM machine: " + name
for script in (root / "Toolbox/scripts").glob("*.sh"):
    assert b"@CARPLAY_JAR_" not in script.read_bytes(), "unexpanded packaged build pins: " + script.name
print("STAGED_INVENTORY=PASS required_helpers=YES arm_payloads=ELF32_ARM packaged_pins=UNCHANGED")
PY
    (cd /sd && sha256sum --check --quiet SHA256SUMS-SD.txt) > "$RUN/staged-checksums.log" 2>&1 || {
        cat "$RUN/staged-checksums.log"; exit 1;
    }
    echo "STAGED_CHECKSUMS=PASS"
fi
fixture(){
    CASE_DIR="$RUN/$1"; ROOT="$CASE_DIR/root"; VOL="$CASE_DIR/vol"
    SCRIPTS="$VOL/Toolbox/scripts"; SD="$VOL/MMI-Cockpit-Carplay"
    RUNTIME="$ROOT/mnt/app/root/carplay-altscreen"; HOOKS="$ROOT/mnt/app/root/hooks"
    P="$ROOT/mnt/system/etc/eso/production"
    F=/fixture/ORIGINAL/files
    mkdir -p "$VOL" "$ROOT/eso/bin/apps" "$ROOT/eso/lib" "$ROOT/armle/usr/lib" "$P" \
        "$ROOT/mnt/system/etc/boot" "$ROOT/mnt/app/root" "$ROOT/mnt/app/eso/hmi/lsd/jars" \
        "$ROOT/dev/shmem" "$ROOT/tmp" "$ROOT/ramdisk" "$ROOT/armle/sbin"
    cp /work/tests/fixtures/pfctl-filter-validator.sh "$ROOT/armle/sbin/pfctl"
    chmod 755 "$ROOT/armle/sbin/pfctl"
    cp -a /sd/. "$VOL/"
    if [ "$PACKAGE_MODE" = staged ]; then
        diff -qr /sd "$VOL" > "$CASE_DIR/package-copy.log" || {
            cat "$CASE_DIR/package-copy.log"; return 1;
        }
    else
        cp /work/altscreen/Toolbox/scripts/* "$SCRIPTS/"
        read -r sum bytes < <(cksum < "$VOL/Toolbox/carplay_alt_screen/hmi/carplay_hook-basevideo3.jar")
        for f in "$SCRIPTS"/*.sh; do
            sed -i "s/@CARPLAY_JAR_SIZE@/$bytes/g;s/@CARPLAY_JAR_CKSUM@/$sum/g" "$f"
        done
    fi
    cp "$F/_eso_bin_apps_dio_manager" "$ROOT/eso/bin/apps/dio_manager"
    cp "$F/_eso_lib_libairplay.so" "$ROOT/eso/lib/libairplay.so"
    cp "$F/_armle_usr_lib_libNmeBaseClasses.so" "$ROOT/armle/usr/lib/libNmeBaseClasses.so"
    cp "$F/_mnt_system_etc_eso_production_smartphone_integrator.json" "$P/smartphone_integrator.json"
    cp "$F/_mnt_system_etc_eso_production_dio_manager.json" "$P/dio_manager.json"
    cp /fixture/firewall-original/pf.conf "$ROOT/mnt/system/etc/pf.conf"
    cp /fixture/firewall-original/pf.conf "$ROOT/mnt/system/etc/pf.ecm0.conf"
    printf 'altq on ppp0 cbq bandwidth 256Kb queue { normal }\nqueue normal bandwidth 100%% cbq(default)\nblock in quick on carplay0 all\n' > "$ROOT/mnt/system/etc/pf.ppp0.conf"
    chmod 755 "$ROOT/mnt/system/etc/pf.ecm0.conf"
    cp /fixture/boot-diagnostics/startup.sh "$ROOT/mnt/system/etc/boot/startup.sh"
    printf '\n# unrelated startup setting preserved\n' >> "$ROOT/mnt/system/etc/boot/startup.sh"
    echo "Current train = MHI2Q_US_AUG22_P5145" > "$ROOT/dev/shmem/version.txt"
    echo exact-test-unit > "$ROOT/dev/shmem/test-unit-id"
    mkdir -p "$ROOT/mnt/persist/var/app/carplay_altscreen" "$HOOKS"
    echo private-owner-preferences > "$ROOT/mnt/persist/var/app/carplay_altscreen/preferences"
    echo unrelated-hook > "$HOOKS/owner-unrelated"
    echo rightaligned > "$HOOKS/cluster_ui.url"
    export ALTSCREEN_CHAIN_TESTING=1 ALTSCREEN_CHAIN_ROOT="$ROOT" ALTSCREEN_CHAIN_VOLUME="$VOL"
}
run(){
    name=$1; shift
    timeout 180 /bin/sh "$@" > "$CASE_DIR/$name.log" 2>&1
}
fails(){
    name=$1; shift
    if run "$name" "$@"; then echo "FAIL: $name unexpectedly passed"; exit 1; fi
    echo "ok rejected: $name"
}
need(){ grep -Fq "$2" "$CASE_DIR/$1.log"; }
state(){
    (
        cd "$ROOT"
        find mnt eso armle -type f ! -name '*.transaction-new' -print0 | sort -z | xargs -0 sha256sum
    ) > "$CASE_DIR/$1"
}
unchanged(){ state after; cmp "$CASE_DIR/$1" "$CASE_DIR/after"; }
stock(){
    cmp "$F/_mnt_system_etc_eso_production_smartphone_integrator.json" "$P/smartphone_integrator.json"
    cmp "$F/_mnt_system_etc_eso_production_dio_manager.json" "$P/dio_manager.json"
    cmp /fixture/firewall-original/pf.conf "$ROOT/mnt/system/etc/pf.conf"
    cmp /fixture/firewall-original/pf.conf "$ROOT/mnt/system/etc/pf.ecm0.conf"
    test -x "$ROOT/mnt/system/etc/pf.ecm0.conf"
}
install(){
    run install "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
    need install 'INSTALL=PASS integrated='
    need install 'BOOT_ARM=PASS live_launch=NO'
    need install 'INSTALL_VERIFY=PASS'
    test -f "$RUNTIME/state/basevideo3.enabled"
    test -f "$RUNTIME/state/ACTIVE"
    test ! -e "$ROOT/tmp/mmi-mirror-active"
    test ! -e "$SD/install-transaction/active"
    grep -q 'unrelated startup setting preserved' "$ROOT/mnt/system/etc/boot/startup.sh"
    grep -q private-owner-preferences "$ROOT/mnt/persist/var/app/carplay_altscreen/preferences"
    grep -q rightaligned "$HOOKS/cluster_ui.url"
}
stop_stub(){
    echo "HOST_FIXTURE_STUB=$RUNTIME/bin/mirror/stop_vehicle.sh native_execution=NO"
    printf '#!/bin/sh\nexit 0\n' > "$RUNTIME/bin/mirror/stop_vehicle.sh"
}
inject(){
    script=$1; pattern=$2; replacement=$3
    echo "HOST_FIXTURE_FAULT=$script"
    python3 - "$script" "$pattern" "$replacement" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); text=p.read_text()
assert text.count(sys.argv[2]) == 1, (sys.argv[1],sys.argv[2],text.count(sys.argv[2]))
p.write_text(text.replace(sys.argv[2],sys.argv[3]))
PY
}

case "${INSTALLER_CASES:-all}" in all|rescue) ;; *) exit 2 ;; esac
if [ "${INSTALLER_CASES:-all}" = all ]; then
fixture success
state pristine
mkdir -p "$SD/.operation.lock"
install
need install 'STALE_LOCK=RECOVERED'
run status "$SCRIPTS/status_mmi_cockpit_carplay_test.sh"
need status 'HMI_CONTROL_PLANE=PASS'
need status 'RGI_DIO_IDS=5/5'
fails premature-rgi-remove "$SCRIPTS/rgi_companion.sh" remove
need premature-rgi-remove 'SI still references the RGI runtime'
cp "$SD/backup/original/files/_mnt_system_etc_eso_production_smartphone_integrator.json" "$CASE_DIR/original-si"
run upgrade "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
cmp "$CASE_DIR/original-si" "$SD/backup/original/files/_mnt_system_etc_eso_production_smartphone_integrator.json"
need upgrade 'BACKUP=EXISTING kept'
test "$(grep -c '^# BEGIN ALT111 BASEVIDEO3 AUTOSTART$' "$ROOT/mnt/system/etc/boot/startup.sh")" = 1
stop_stub
echo "HOST_FIXTURE_GUARD=$RUNTIME/bin/mirror/start_vehicle.sh must_not_execute=YES"
printf '#!/bin/sh\necho "FAIL: idempotent START launched the mirror" >&2\nexit 97\n' > "$RUNTIME/bin/mirror/start_vehicle.sh"
run compatible-start "$SCRIPTS/start_mmi_cockpit_carplay_rx_test.sh"
need compatible-start 'START=ALREADY_ACTIVE'

# Export remains immutable, including SD outside the newly created export tree.
echo PRIVATE_FIREWALL_SOURCE >> "$ROOT/mnt/system/etc/pf.ecm0.conf"
sed -i '1i# pfctl PRIVATE_FIREWALL_SOURCE' "$ROOT/mnt/system/etc/pf.ecm0.conf"
dd if=/dev/zero bs=1000 count=300 2>/dev/null | tr '\000' x > "$ROOT/tmp/carplay_java.log"
printf '\nPRIVATE_DESTINATION\n' >> "$ROOT/tmp/carplay_java.log"
printf 'receiver_pid=123\nreceiver_epoch=42\nexpiry_ms=999\nview=FULL\nPRIVATE_HMI_LEASE\n' > "$ROOT/ramdisk/mmi-mirror-hmi.state"
state before-export
(cd "$VOL"; find . -type f ! -path './MMI-Cockpit-Carplay/logs/exports/*' -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/sd-before"
run export "$SCRIPTS/export_mmi_cockpit_diagnostics.sh"
need export 'EXPORT=PASS no_restore=YES no_restart=YES'
! grep -q PRIVATE_DESTINATION "$CASE_DIR/export.log"
! grep -q PRIVATE_FIREWALL_SOURCE "$CASE_DIR/export.log"
! grep -q PRIVATE_HMI_LEASE "$CASE_DIR/export.log"
export_path=$(sed -n 's/^EXPORT_PATH=//p' "$CASE_DIR/export.log")
grep -q PRIVATE_DESTINATION "$export_path/private/carplay_java.log"
test "$(wc -c < "$export_path/private/carplay_java.log")" = 262144
grep -q PRIVATE_FIREWALL_SOURCE "$export_path/private/firewall_sources.log"
test "$(wc -c < "$export_path/private/firewall_sources.log")" -le 262144
grep -q PRIVATE_HMI_LEASE "$export_path/private/mmi-mirror-hmi.state"
run summary "$SCRIPTS/export_mmi_cockpit_diagnostics.sh" --summary
summary_path=$(sed -n 's/^EXPORT_PATH=//p' "$CASE_DIR/summary.log")
test "$(find "$summary_path/private" -type f | wc -l)" = 0
unchanged before-export
(cd "$VOL"; find . -type f ! -path './MMI-Cockpit-Carplay/logs/exports/*' -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/sd-after"
cmp "$CASE_DIR/sd-before" "$CASE_DIR/sd-after"
mv "$SD/logs/exports" "$CASE_DIR/saved-exports"
: > "$SD/logs/exports"
fails blocked-export "$SCRIPTS/export_mmi_cockpit_diagnostics.sh"
unchanged before-export
run restore "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
stock
test ! -e "$RUNTIME"
test ! -e "$ROOT/ramdisk/mmi-mirror-hmi.state"
test -f "$HOOKS/cluster_ui.url"
test -f "$HOOKS/owner-unrelated"
run restore-again "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
need restore-again 'already_restored=YES'
echo "ok: one-step, upgrade, compatible START, exact restore and immutable exports"

fixture wrong-firmware
state before
echo MHI2Q_ER_AUG22_P5092 > "$ROOT/dev/shmem/version.txt"
fails wrong "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
need wrong 'requires MHI2Q_US_AUG22_P5145'
unchanged before

fixture missing-payload
state before
rm "$VOL/Toolbox/carplay_alt_screen/mirror_display/release/mascots.rgba"
fails missing-mascot "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
need missing-mascot 'map mascot artifact missing'
unchanged before
fixture original-local-files
printf 'original-project-named-hook\n' > "$HOOKS/libcarplay_hook.so"
printf 'original-project-named-jar\n' > "$ROOT/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar"
state original-local
install
stop_stub
run restore-local "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
unchanged original-local
echo "ok: source preflight and original hook/JAR preservation"

for fault in mutation autostart verification activation; do
    fixture "$fault"
    install
    state before
    case "$fault" in
        mutation) inject "$SCRIPTS/rgi_companion.sh" '    patch_dio || fail' '    false || fail' ;;
        autostart) inject "$SCRIPTS/start_mmi_cockpit_carplay_rx_test.sh" 'touch "$ENABLED" || fail' 'false || fail' ;;
        verification) inject "$SCRIPTS/altscreen_transaction.sh" '    verify_installed || fail' '    false || fail' ;;
        activation) inject "$SCRIPTS/altscreen_transaction.sh" '    rm -f "$RUNTIME/state/transaction.pending" || fail' '    false || fail' ;;
    esac
    fails "$fault" "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
    need "$fault" 'ROLLBACK=PASS'
    unchanged before
done
echo "ok: exact pre-upgrade rollback at mutation, autostart, verification and activation"

for fault in sync remount; do
    fixture "$fault"
    state before
    if [ "$fault" = sync ]; then
        inject "$SCRIPTS/start_mmi_cockpit_carplay_rx_test.sh" 'sync || fail "cannot sync autostart"' 'false || fail "cannot sync autostart"'
    else
        inject "$SCRIPTS/start_mmi_cockpit_carplay_rx_test.sh" 'mount_system_ro || fail "cannot remount /mnt/system read-only"' 'false || fail "cannot remount /mnt/system read-only"'
    fi
    fails "$fault" "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
    need "$fault" 'ROLLBACK=PASS'
    unchanged before
    test ! -e "$RUNTIME"
done
echo "ok: first-install sync/remount failures never commit"

fixture interrupted
install
state before
cp "$SCRIPTS/altscreen_transaction.sh" "$CASE_DIR/transaction.clean"
inject "$SCRIPTS/altscreen_transaction.sh" '    verify_installed || fail' '    kill -KILL $$; verify_installed || fail'
fails interrupted "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
test -f "$SD/install-transaction/active/PREPARED"
test -f "$RUNTIME/state/transaction.pending"
cp "$CASE_DIR/transaction.clean" "$SCRIPTS/altscreen_transaction.sh"
echo another-unit > "$ROOT/dev/shmem/test-unit-id"
fails different-unit "$SCRIPTS/altscreen_rescue.sh" recover
need different-unit 'firmware_or_unit_mismatch=YES'
test -f "$SD/install-transaction/active/PREPARED"
echo exact-test-unit > "$ROOT/dev/shmem/test-unit-id"
cp "$SD/install-transaction/active/data/si" "$CASE_DIR/snapshot-si"
echo damaged-snapshot >> "$SD/install-transaction/active/data/si"
fails corrupt-snapshot "$SCRIPTS/altscreen_rescue.sh" recover
test -f "$SD/install-transaction/active/ROLLBACK_INCOMPLETE"
cp "$CASE_DIR/snapshot-si" "$SD/install-transaction/active/data/si"
rm "$CASE_DIR/snapshot-si"
run recover "$SCRIPTS/altscreen_rescue.sh" recover
need recover 'STALE_TRANSACTION=RECOVERED'
unchanged before
echo "ok: interrupted upgrade recovered before new operations"

fixture interrupted-activation
install
state before
cp "$SCRIPTS/altscreen_transaction.sh" "$CASE_DIR/transaction.clean"
inject "$SCRIPTS/altscreen_transaction.sh" 'touch "$TXN/COMMITTED" && sync || fail "durable commit"' \
    'touch "$TXN/COMMITTED" && sync || fail "durable commit"; kill -KILL $$'
fails interrupted-activation "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
test -f "$SD/install-transaction/active/COMMITTED"
test ! -f "$SD/install-transaction/active/ACTIVATED"
test -f "$RUNTIME/state/transaction.pending"
cp "$CASE_DIR/transaction.clean" "$SCRIPTS/altscreen_transaction.sh"
run recover-activation "$SCRIPTS/altscreen_rescue.sh" recover
need recover-activation 'STALE_TRANSACTION=RECOVERED'
unchanged before
echo "ok: committed-but-not-activated interruption remains boot-inert and reversible"

fixture cleanup-failure
install
stop_stub
state before
cp "$SCRIPTS/altscreen_transaction.sh" "$CASE_DIR/transaction.clean"
inject "$SCRIPTS/rgi_companion.sh" '    remove_files || fail' '    false || fail'
inject "$SCRIPTS/altscreen_transaction.sh" '    mount_system_rw && SYS_RW=1 || return 1' '    false && SYS_RW=1 || return 1'
fails cleanup "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
need cleanup 'rollback=INCOMPLETE'
test -f "$SD/restore-transaction/active/ROLLBACK_INCOMPLETE"
test -s "$RUNTIME/bin/altscreen_transaction.sh"
test -f "$RUNTIME/state/transaction.pending"
cp "$CASE_DIR/transaction.clean" "$SCRIPTS/altscreen_transaction.sh"
run recover-cleanup "$SCRIPTS/altscreen_rescue.sh" recover
unchanged before
echo "ok: incomplete cleanup retains complete recovery evidence and runtime"
fi

fixture rescue
install
# Simulate a pre-transaction release's intact original set. Upgrade may add a
# separate verified unit witness, but must not rewrite those original files.
rm "$SD/backup/original/firmware_train.txt" "$SD/backup/original/vehicle_key.txt" "$SD/backup/original/identity.cksum"
(cd "$SD/backup/original"; find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/legacy-backup-before"
run bind-legacy-upgrade "$SCRIPTS/install_mmi_cockpit_carplay_rx.sh"
need bind-legacy-upgrade 'BACKUP_IDENTITY=BOUND legacy_original_bytes=UNCHANGED'
(cd "$SD/backup/original"; find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$CASE_DIR/legacy-backup-after"
cmp "$CASE_DIR/legacy-backup-before" "$CASE_DIR/legacy-backup-after"
stop_stub
echo damaged-native > "$ROOT/eso/bin/apps/dio_manager"
state damaged
fails ordinary-refuses-damage "$SCRIPTS/stop_mmi_cockpit_carplay_test.sh"
unchanged damaged
run rescue "$SCRIPTS/altscreen_rescue.sh" restore-original --confirm-stock-rescue
need rescue 'RESCUE=PASS reversible=YES'
cmp "$F/_eso_bin_apps_dio_manager" "$ROOT/eso/bin/apps/dio_manager"
test -f "$SD/restore-transaction/rescue/PREPARED"
run undo "$SCRIPTS/altscreen_rescue.sh" undo --confirm-pre-rescue
need undo 'RESCUE_UNDO=PASS'
unchanged damaged
test ! -e "$SD/restore-transaction/rescue"
echo "ok: guarded native rescue and exact reversible pre-rescue state"
if [ "$PACKAGE_MODE" = staged ]; then
    (cd /sd && sha256sum --check --quiet SHA256SUMS-SD.txt) >> "$RUN/staged-checksums.log" 2>&1 || {
        cat "$RUN/staged-checksums.log"; exit 1;
    }
    echo "STAGED_PACKAGE_AFTER_TESTS=UNCHANGED"
fi
echo "PASS: installer/transaction/export fixtures package_mode=$PACKAGE_MODE (packaged binaries copied; ARM execution and vehicle behavior not validated)"
