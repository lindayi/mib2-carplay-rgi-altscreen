#!/bin/bash
set -euo pipefail
RELEASE=${1:?mirror release directory required}
T=$(mktemp -d /tmp/mirror-health-test.XXXXXX)
sleep 300 &
TEST_OWNER_PID=$!
trap 'kill "$TEST_OWNER_PID" 2>/dev/null || true; wait "$TEST_OWNER_PID" 2>/dev/null || true; rm -rf "$T"' EXIT
export ALTSCREEN_CHAIN_TESTING=1 ALT111_TEST_INTERVAL=0.02 ALT111_TEST_READY_LIMIT=4 ALT111_TEST_STALL_LIMIT=6
TMP_ROOT=$T
DEMAND=$T/active STOP_GUARD=$T/stop LOGFILE=$T/mirror.log
RESTART_COUNT=0
touch "$DEMAND" "$LOGFILE"
printf 'ctx=74\n' > "$T/carplay_cluster.ctx"
printf 'pid=%s\nenabled=1\nvideo=1\nverbose=0\nconfig_error=0\n' "$TEST_OWNER_PID" > "$T/carplay_menu_session"
echo "$TEST_OWNER_PID" > "$T/carplay_supervisor.owner"
. "$RELEASE/mirror_health.sh"

sample(){
    printf '%040d' "$1" > "$MH_RING"
    [ "$2" = keep ] || echo "direct111: PHASE=RUN generation=1 presented_frames=$2" >> "$LOGFILE"
    mh_poll
}
sample 1 100
sample 2 101
sample 3 102
test "$MH_SAMPLES" -eq 2
for i in 4 5 6 7 8; do sample "$i" keep; done
if sample 9 keep; then echo "FAIL: active input plus stalled output was not detected"; exit 1; fi
grep -q 'HEALTH_STATE=PRESENTATION_STALLED' "$MH_STATUS"
sample 10 103
test "$MH_STALLED" -eq 0
for i in $(seq 1 15); do sample 10 keep; done
test "$MH_STALLED" -eq 0
: > "$LOGFILE"
for i in $(seq 11 30); do sample "$i" keep; done
test "$MH_SAMPLES" -eq 0
touch "$STOP_GUARD"
sample 11 keep
grep -q 'HEALTH_STATE=STOPPED' "$MH_STATUS"
if mh_wait_for_hmi; then echo "FAIL: explicit stop ignored by ready HMI gate"; exit 1; fi
rm "$STOP_GUARD"

. "$RELEASE/mirror_health.sh"
: > "$LOGFILE"
for i in $(seq 1 20); do sample "$i" keep; done
test "$MH_SAMPLES" -eq 0

# Slow/buffered log telemetry is calibrated, not mistaken for a live freeze.
. "$RELEASE/mirror_health.sh"
: > "$LOGFILE"
sample 1 1
for i in $(seq 2 11); do sample "$i" keep; done
sample 12 2
for i in $(seq 13 22); do sample "$i" keep; done
sample 23 3
test "$MH_MAX_REPORT_GAP" -eq 10
for i in $(seq 24 53); do sample "$i" keep; done
test "$MH_STALLED" -eq 30
sample 54 4
export CP_SETTINGS_FILE="$T/preferences"
MH_SETTINGS_HELPER="$RELEASE/../../rgi/carplay_settings.sh"
sed 's/enabled=0/enabled=1/;s/recovery=1/recovery=0/' /tests/fixtures/carplay-preferences.txt > "$CP_SETTINGS_FILE"
sample 55 keep
grep -q 'HEALTH_STATE=RECOVERY_DISABLED' "$MH_STATUS"
rm "$CP_SETTINGS_FILE"
unset CP_SETTINGS_FILE

PIDFILE=$T/pid READY=$T/ready BASE_READY=$T/base-ready RECOVERY_LOCK=$T/recovery.lock
PID=$TEST_OWNER_PID MAX_ABNORMAL_RESTARTS=0
ROOT=$RELEASE
eval "$(sed -n '/^schedule_abnormal_restart() {/,/^}/p' "$RELEASE/start_vehicle.sh")"
echo "$PID" > "$PIDFILE"; touch "$READY" "$BASE_READY"
if schedule_abnormal_restart test_exhausted; then echo "FAIL: exhausted restart budget accepted"; exit 1; fi
test ! -e "$READY" && test ! -e "$BASE_READY"
grep -q 'HEALTH_STATE=RECOVERY_EXHAUSTED' "$MH_STATUS"
echo 999999 > "$PIDFILE"; touch "$READY" "$BASE_READY"
if schedule_abnormal_restart test_superseded; then echo "FAIL: old generation accepted"; exit 1; fi
test -e "$READY" && test -e "$BASE_READY"

(
    mkdir "$T/identity-tools"
    cat > "$T/identity-tools/pidin" <<'PIDIN'
#!/bin/sh
if [ "${TEST_IDENTITY_TIMEOUT:-0}" = 1 ]; then exec sleep 30; fi
printf '%s %s\n' "$TEST_IDENTITY_PID" "$TEST_IDENTITY_EXE"
PIDIN
    chmod +x "$T/identity-tools/pidin"
    export PATH="$T/identity-tools:$PATH"
    PID=$TEST_OWNER_PID
    BIN="$T/carplay-alt111-mirror-display"
    export TEST_IDENTITY_PID="$PID" TEST_IDENTITY_EXE="$BIN"
    echo "$PID" > "$PIDFILE"
    eval "$(sed -n '/^sidecar_is_current() {/,/^}/p' "$RELEASE/start_vehicle.sh")"
    eval "$(sed -n '/^mirror_confirm_identity(){/,/^}/p' "$RELEASE/start_vehicle.sh")"
    mirror_confirm_identity
    TEST_IDENTITY_EXE=/bin/unrelated
    if mirror_confirm_identity; then echo "FAIL: unrelated PID identity accepted"; exit 1; fi
    TEST_IDENTITY_EXE="$BIN"
    export TEST_IDENTITY_TIMEOUT=1
    if mirror_confirm_identity; then echo "FAIL: timed-out identity probe accepted"; exit 1; fi
    kill -0 "$PID"
)

# Exercise the actual freeze recovery action with recorded signals, never real PIDs.
(
    eval "$(sed -n '/^recover_stalled_mirror(){/,/^}/p' "$RELEASE/start_vehicle.sh")"
    sidecar_is_current(){ return 0; }
    mirror_confirm_identity(){ [ "$IDENTITY" = known ]; }
    kill(){ [ "$1" = -0 ] || echo "$*" >> "$T/signals"; return 0; }
    sleep(){ :; }
    schedule_abnormal_restart(){ echo "$1" > "$T/restart-reason"; }
    PID=321 IDENTITY=unknown
    if recover_stalled_mirror; then echo "FAIL: unverified process recovery accepted"; exit 1; fi
    test ! -e "$T/signals"
    mirror_confirm_identity(){ touch "$STOP_GUARD"; return 0; }
    if recover_stalled_mirror; then echo "FAIL: stop during identity probe ignored"; exit 1; fi
    test ! -e "$T/signals"
    rm "$STOP_GUARD"
    mirror_confirm_identity(){ [ "$IDENTITY" = known ]; }
    IDENTITY=known
    recover_stalled_mirror
    grep -qx -- '-TERM 321' "$T/signals"
    grep -qx -- '-KILL 321' "$T/signals"
    test "$(cat "$T/restart-reason")" = presentation_stalled
)

await_file(){
    for i in $(seq 1 150); do [ ! -e "$1" ] || return 0; sleep 0.02; done
    echo "FAIL: file not published: $1"; return 1
}
make_launch_fixture(){
    D=$(mktemp -d "$T/launch.XXXXXX")
    mkdir "$D/release"
    cp "$RELEASE/start_vehicle.sh" "$RELEASE/mirror_health.sh" "$D/release/"
    cat > "$D/fake-mirror" <<'FAKE'
#!/bin/sh
echo launched > "$ALT111_MIRROR_TMP_ROOT/launched"
echo "${ALT111_RECOVER_CURRENT_SESSION:-0}" > "$ALT111_MIRROR_TMP_ROOT/recovery-mode"
touch "$ALT111_MIRROR_READY_FILE" "$ALT111_MIRROR_BASE_READY_FILE"
exit 0
FAKE
    chmod +x "$D/fake-mirror"
    touch "$D/active"
    printf 'pid=%s\nenabled=1\nvideo=1\nverbose=0\nconfig_error=0\n' "$TEST_OWNER_PID" > "$D/carplay_menu_session"
    export ALT111_MIRROR_BIN=$D/fake-mirror ALT111_MIRROR_TMP_ROOT=$D
    export ALT111_MIRROR_ACTIVE_FILE=$D/active ALT111_JAVA_BASE_READY_FILE=$D/base-ready
    export ALT111_MIRROR_MAX_ABNORMAL_RESTARTS=0
}
make_launch_fixture
/bin/sh "$D/release/start_vehicle.sh" > "$D/launch.log" 2>&1
test ! -e "$D/launched"
echo "$TEST_OWNER_PID" > "$D/carplay_supervisor.owner"
printf 'ctx=74\n' > "$D/carplay_cluster.ctx"
await_file "$D/launched"
test "$(cat "$D/recovery-mode")" = 1
for i in $(seq 1 100); do
    if grep -q 'HEALTH_STATE=RECOVERY_EXHAUSTED' "$D/MMI-Cockpit-Carplay.mirror.health" 2>/dev/null; then break; fi
    sleep 0.02
done
grep -q 'HEALTH_STATE=RECOVERY_EXHAUSTED' "$D/MMI-Cockpit-Carplay.mirror.health"
test ! -e "$D/base-ready"

make_launch_fixture
echo "$TEST_OWNER_PID" > "$D/carplay_supervisor.owner"
if /bin/sh "$D/release/start_vehicle.sh" > "$D/launch.log" 2>&1; then
    echo "FAIL: missing HMI context with an active phone must time out"; exit 1
fi
test ! -e "$D/launched"
grep -q 'COLD_START_TIMEOUT=JAVA_CLUSTER_CONTEXT' "$D/MMI-Cockpit-Carplay.mirror.log"

echo "Mirror health: cold gate, timeout, active-input freeze, telemetry calibration, stop/identity guards and retry exhaustion PASS"
