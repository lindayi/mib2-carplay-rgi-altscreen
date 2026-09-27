#!/bin/sh
set -eu
TMP_ROOT="${ALT111_MIRROR_TMP_ROOT:-/tmp}"
FLAT="$TMP_ROOT/MMI-Cockpit-Carplay.mirror"
PIDFILE="$FLAT.pid"
WATCH_PIDFILE="$FLAT.lifecycle.pid"
STOP_GUARD="$FLAT.stop.requested"
RECOVERY_LOCK="$FLAT.recovery.lock"
ROOT=$(CDPATH= cd "$(dirname "$0")" && pwd)
. "$ROOT/mirror_health.sh"
BIN="$ROOT/carplay-alt111-mirror-display"
valid_pid(){ case "$1" in ''|*[!0-9]*|0|1) return 1 ;; *) return 0 ;; esac; }

# Publish the guard before killing anything so a concurrent private111 teardown
# watcher cannot relaunch a fresh sidecar while an explicit RESTORE/STOP is in
# progress.
: > "$STOP_GUARD" 2>/dev/null || true
rm -f "$FLAT.ready" "$FLAT.basevideo.ready" "${ALT111_JAVA_BASE_READY_FILE:-/tmp/mmi-mirror-basevideo.ready}"

if [ -f "$WATCH_PIDFILE" ]; then
  WATCH_PID="$(cat "$WATCH_PIDFILE" 2>/dev/null || true)"
  if valid_pid "$WATCH_PID" && kill -0 "$WATCH_PID" 2>/dev/null; then
    N=0
    while kill -0 "$WATCH_PID" 2>/dev/null && [ "$N" -lt 10 ]; do
      sleep 1
      N=$((N + 1))
    done
    if kill -0 "$WATCH_PID" 2>/dev/null; then
      echo "FAIL: mirror watcher has not stopped; no unverified PID will be signaled"
      exit 1
    fi
  fi
fi

if [ -f "$PIDFILE" ]; then
  PID="$(cat "$PIDFILE" 2>/dev/null || true)"
  if valid_pid "$PID" && kill -0 "$PID" 2>/dev/null; then
    mh_confirm_identity "$PID" "$BIN" || { echo "FAIL: mirror PID identity is not verified"; exit 1; }
    kill -TERM "$PID" || exit 1
    N=0
    while kill -0 "$PID" 2>/dev/null && [ "$N" -lt 3 ]; do
      sleep 1
      N=$((N + 1))
    done
    if kill -0 "$PID" 2>/dev/null; then
      mh_confirm_identity "$PID" "$BIN" || { echo "FAIL: mirror identity changed during stop"; exit 1; }
      kill -KILL "$PID" || exit 1
    fi
  fi
fi

# Keep STOP_GUARD published after STOP. Any already-scheduled delayed recovery
# child must continue to see the explicit-stop decision. A future manual START
# (RESTART_REASON empty) is the only path that clears this guard.
rm -f "$FLAT.pid" "$FLAT.lifecycle.pid" "$FLAT.ready" "$FLAT.basevideo.ready" \
      "${ALT111_JAVA_BASE_READY_FILE:-/tmp/mmi-mirror-basevideo.ready}"
printf 'HEALTH_STATE=STOPPED\n' > "$FLAT.health" || echo "WARN: cannot publish stopped mirror health"
rm -f "$RECOVERY_LOCK" 2>/dev/null || true

echo "MIRROR_DISPLAY=STOPPED lifecycle_watch=STOPPED context_writer=JAVA80 native_dmdt=DISABLED stop_guard=RETAINED"
