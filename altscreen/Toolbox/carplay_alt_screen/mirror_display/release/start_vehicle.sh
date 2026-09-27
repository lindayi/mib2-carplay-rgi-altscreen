#!/bin/sh
set -eu

# Mirror can be launched from startup.sh, GEM, diagnostics, or an SSH shell.
# Do not rely on the caller having inherited the MHI2Q/QNX runtime search path.
# Keep this in sync with the integrated Mirror boot block and diagnostics block.
PATH=${PATH:+$PATH:}/proc/boot:/armle/bin:/armle/scripts:/bin:/usr/bin:/usr/sbin:/sbin:/mnt/app/armle/bin:/mnt/app/armle/sbin:/mnt/app/armle/usr/bin:/mnt/app/armle/usr/sbin:/eso/bin:/eso/bin/apps
export PATH
# Only publish the QNX loader path on a real target. This keeps host-side
# fixtures usable while production MHI2Q launches remain deterministic.
if [ -d /proc/boot ] && [ -d /mnt/app ]; then
  LD_LIBRARY_PATH=${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}/proc/boot:/usr/lib:/armle/lib:/armle/lib/dll:/lib:/mnt/app/root/carplay-altscreen/lib:/eso/lib:/mnt/app/usr/lib:/mnt/app/armle/lib:/mnt/app/armle/lib/dll:/mnt/app/armle/usr/lib:/lib/dll
  export LD_LIBRARY_PATH
fi

case "$0" in
  */*) ROOT=${0%/*} ;;
  *)   ROOT=. ;;
esac

ROOT=$(CDPATH= cd "$ROOT" 2>/dev/null && pwd) || {
  echo "ERROR: cannot resolve mirror script directory from $0" >&2
  exit 2
}

if [ -n "${ALT111_MIRROR_BIN:-}" ]; then
  BIN="$ALT111_MIRROR_BIN"
elif [ -x "$ROOT/carplay-alt111-mirror-display" ]; then
  BIN="$ROOT/carplay-alt111-mirror-display"
else
  BIN="$ROOT/release/carplay-alt111-mirror-display"
fi
ALT111_LOGO_ASSET="${ALT111_LOGO_ASSET:-$ROOT/logo.rgba}"
ALT111_WATERMARK_ASSET="${ALT111_WATERMARK_ASSET:-$ROOT/watermark.rgba}"
export ALT111_LOGO_ASSET ALT111_WATERMARK_ASSET

TMP_ROOT="${ALT111_MIRROR_TMP_ROOT:-/tmp}"
PIDFILE="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.pid"
WATCH_PIDFILE="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.lifecycle.pid"
STOP_GUARD="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.stop.requested"
LOGFILE="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.log"
AUTORESTART_LOG="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.autorestart.log"
RECOVERY_LOCK="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.recovery.lock"
READY="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.ready"
BASE_READY="${ALT111_JAVA_BASE_READY_FILE:-/tmp/mmi-mirror-basevideo.ready}"
GATE_TOKEN="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.phone111.gate"
HOOK_LOG="$TMP_ROOT/MMI-Cockpit-Carplay.altscreen_hook.log"
VOLATILE_MODE=FLAT_TMP

trim_flat_log(){
  [ -f "$1" ] || return 0
  size=$(wc -c < "$1" 2>/dev/null || echo 0)
  [ "$size" -le "$2" ] || : > "$1"
}
trim_flat_log "$AUTORESTART_LOG" 1048576

DEMAND="${ALT111_MIRROR_ACTIVE_FILE:-/tmp/mmi-mirror-active}"
RESTART_REASON="${ALT111_MIRROR_RESTART_REASON:-}"
RESTART_COUNT="${ALT111_MIRROR_RESTART_COUNT:-0}"
MAX_ABNORMAL_RESTARTS="${ALT111_MIRROR_MAX_ABNORMAL_RESTARTS:-3}"
RECOVER_CURRENT_SESSION="${ALT111_RECOVER_CURRENT_SESSION:-0}"
export ALT111_RECOVER_CURRENT_SESSION="$RECOVER_CURRENT_SESSION"
case "$RESTART_COUNT" in ''|*[!0-9]*) RESTART_COUNT=0 ;; esac
case "$MAX_ABNORMAL_RESTARTS" in ''|*[!0-9]*) MAX_ABNORMAL_RESTARTS=3 ;; esac
[ -r "$ROOT/mirror_health.sh" ] || { echo "ERROR: mirror health helper missing"; exit 2; }
. "$ROOT/mirror_health.sh"

export ALT111_MIRROR_READY_FILE="$READY"
export ALT111_MIRROR_BASE_READY_FILE="$BASE_READY"
export ALT111_MIRROR_GATE_TOKEN_FILE="$GATE_TOKEN"
export ALT111_MIRROR_HOOK_LOG="$HOOK_LOG"

SINK_TEST_GRID_MODE=0
MIRROR_ARGS="--verbose"
if [ "${ALT111_SINK_TEST_GRID:-0}" = "1" ]; then
  SINK_TEST_GRID_MODE=1
  MIRROR_ARGS="$MIRROR_ARGS --sink-test-grid"
fi

if [ ! -x "$BIN" ]; then
  echo "ERROR: mirror display binary not found/executable: $BIN" >&2
  exit 2
fi

# An explicit stop wins over an automatic next-session restart. A normal boot,
# manual START, or diagnostic launch clears a stale guard from an earlier stop.
if [ -n "$RESTART_REASON" ] && [ -f "$STOP_GUARD" ]; then
  echo "MIRROR_RESTART=SUPPRESSED reason=explicit_stop guard=$STOP_GUARD"
  exit 0
fi
if [ -z "$RESTART_REASON" ]; then
  rm -f "$STOP_GUARD"
fi

# An abnormal-restart lock prevents the startup probe and lifecycle watcher from
# scheduling the same recovery twice. Keep it until the delayed child actually
# starts, then release it for future failures.
if [ "$RESTART_REASON" = "sidecar_abnormal" ]; then
  rm -f "$RECOVERY_LOCK" 2>/dev/null || true
elif [ -z "$RESTART_REASON" ]; then
  rm -f "$RECOVERY_LOCK" 2>/dev/null || true
fi

if [ -f "$PIDFILE" ]; then
  OLD="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
    echo "ALREADY_RUNNING pid=$OLD"
    exit 0
  fi
  rm -f "$PIDFILE"
fi

# A prior launcher may have died after its sidecar. Do not let a stale lifecycle
# watcher survive into the new session.
if [ -f "$WATCH_PIDFILE" ]; then
  OLD_WATCH="$(cat "$WATCH_PIDFILE" 2>/dev/null || true)"
  if [ -n "$OLD_WATCH" ] && kill -0 "$OLD_WATCH" 2>/dev/null; then
    kill -TERM "$OLD_WATCH" 2>/dev/null || true
  fi
  rm -f "$WATCH_PIDFILE"
fi

rm -f "$READY" "$BASE_READY"
if [ -n "$RESTART_REASON" ]; then
  {
    echo ""
    echo "MIRROR_SESSION_RESTART reason=$RESTART_REASON launcher_pid=$$"
  } >> "$LOGFILE"
else
  : > "$LOGFILE"
fi

{
  echo "MIRROR_LAUNCH_ENV=READY pid=$ bin=$BIN volatile_mode=$VOLATILE_MODE restart_reason=${RESTART_REASON:-NONE} restart_count=$RESTART_COUNT max_abnormal_restarts=$MAX_ABNORMAL_RESTARTS recover_current_session=$RECOVER_CURRENT_SESSION"
  echo "PATH=$PATH"
  echo "LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-<unset>}"
  echo "HOOK_LOG=$HOOK_LOG"
  echo "GATE_TOKEN=$GATE_TOKEN"
  echo "SCREEN_CONTEXT_POLICY=JAVA80_ONLY native_context_writer=0"
  echo "READY_POLICY=destination_first_present_only base_ready=$BASE_READY"
  echo "DIRECT111_SOURCE=ScreenStreamProcessData+H264_SHM decoded_shm=/carplay111_decoded"
  echo "WINDOW58_POLICY=NOT_ENUMERATED sidecar_screen_read_window=0 hook_exact_stock_window_readback=1 window_manager_context=0"
  echo "SINK_TEST_GRID_MODE=$SINK_TEST_GRID_MODE opt_in_env=ALT111_SINK_TEST_GRID"
  echo "DECODER_POLICY=stock_omx_then_screen_linearizer decoded_shm=/carplay111_decoded h264_shm=/carplay111_h264 raw_vendor_fallback=DISABLED"
  echo "SHM_POLICY=fstat_size_guard writer_ready_last=1 session_identity=writer_pid+generation+stream_cookie"
  echo "SIDECAR_PRELOAD_POLICY=ISOLATED inherited_preload_ignored=${LD_PRELOAD:-<unset>}"
  echo "SESSION_END_POLICY=hook_DIRECT111_TAP_STOP after_first_present restart_while_demand=1"
} >> "$LOGFILE"

count_tap_stops() {
  # Match only the authoritative current-session teardown. Do not count
  # PHASE=DIRECT111_TAP_STOP_STALE from a late old stream.
  N="$(grep -c 'PHASE=DIRECT111_TAP_STOP stream=' "$HOOK_LOG" 2>/dev/null || true)"
  case "$N" in
    ''|*[!0-9]*) N=0 ;;
  esac
  echo "$N"
}

sidecar_is_current() {
  [ -f "$PIDFILE" ] || return 1
  CURRENT="$(cat "$PIDFILE" 2>/dev/null || true)"
  [ "$CURRENT" = "$PID" ] || return 1
  kill -0 "$PID" 2>/dev/null
}

schedule_abnormal_restart() {
  WHY=$1
  current_owner=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ -n "$current_owner" ] && [ "$current_owner" != "$PID" ]; then
    echo "MIRROR_ABNORMAL_RESTART=SUPPRESSED reason=newer_generation"
    return 1
  fi
  # Even when the retry budget is exhausted, stale readiness must not retain ctx 81.
  rm -f "$READY" "$BASE_READY"
  if ! mh_video_wanted || ! mh_recovery_allowed; then
    mh_publish RECOVERY_DISABLED || :
    echo "MIRROR_ABNORMAL_RESTART=SUPPRESSED reason=menu_preferences"
    return 1
  fi
  if ! ( set -C; : > "$RECOVERY_LOCK" ) 2>/dev/null; then
    echo "MIRROR_ABNORMAL_RESTART=ALREADY_SCHEDULED reason=$WHY lock=$RECOVERY_LOCK"
    return 0
  fi
  NEXT=$((RESTART_COUNT + 1))
  if [ "$NEXT" -gt "$MAX_ABNORMAL_RESTARTS" ]; then
    echo "MIRROR_ABNORMAL_RESTART=EXHAUSTED reason=$WHY count=$RESTART_COUNT max=$MAX_ABNORMAL_RESTARTS"
    mh_publish RECOVERY_EXHAUSTED || :
    rm -f "$RECOVERY_LOCK" 2>/dev/null || true
    return 1
  fi
  [ -f "$DEMAND" ] || {
    echo "MIRROR_ABNORMAL_RESTART=SUPPRESSED reason=$WHY demand_present=0"
    rm -f "$RECOVERY_LOCK" 2>/dev/null || true
    return 1
  }
  [ ! -f "$STOP_GUARD" ] || {
    echo "MIRROR_ABNORMAL_RESTART=SUPPRESSED reason=$WHY explicit_stop=1"
    rm -f "$RECOVERY_LOCK" 2>/dev/null || true
    return 1
  }

  DELAY=$NEXT
  rm -f "$PIDFILE" "$READY" "$BASE_READY"
  echo "MIRROR_ABNORMAL_RESTART=SCHEDULED reason=$WHY next_count=$NEXT delay_s=$DELAY recover_current_session=1"
  (
    sleep "$DELAY"
    ALT111_MIRROR_RESTART_REASON=sidecar_abnormal \
    ALT111_MIRROR_RESTART_COUNT="$NEXT" \
    ALT111_RECOVER_CURRENT_SESSION=1 \
      /bin/sh "$ROOT/start_vehicle.sh" >>"$AUTORESTART_LOG" 2>&1
  ) &
  return 0
}

mirror_confirm_identity(){
  sidecar_is_current || return 1
  mh_confirm_identity "$PID" "$BIN"
}
recover_stalled_mirror(){
  [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ] || return 1
  mh_video_wanted && mh_recovery_allowed || return 1
  sidecar_is_current || return 1
  rm -f "$READY" "$BASE_READY"
  if ! mirror_confirm_identity; then
    echo "MIRROR_STALL_RECOVERY=BLOCKED reason=unverified_identity fallback=STOCK"
    mh_publish RECOVERY_BLOCKED || :
    return 1
  fi
  [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ] || return 1
  mh_video_wanted && mh_recovery_allowed || return 1
  echo "MIRROR_STALL_RECOVERY=TERM pid=$PID input_advancing=YES presentation_stalled=YES"
  kill -TERM "$PID" || return 1
  stopped=0
  while kill -0 "$PID" 2>/dev/null && [ "$stopped" -lt 3 ]; do
    sleep 1; stopped=$((stopped + 1))
  done
  if kill -0 "$PID" 2>/dev/null; then
    mirror_confirm_identity || { echo "MIRROR_STALL_RECOVERY=BLOCKED reason=identity_changed"; return 1; }
    kill -KILL "$PID" || return 1
  fi
  schedule_abnormal_restart presentation_stalled
}

# Deliberately do not inherit the CarPlay/dio_manager preload into the sidecar.
# Direct-display consumes SHM only and does not need any Window58 ID bridge.
(
  if [ "$SINK_TEST_GRID_MODE" = 0 ]; then
    mh_wait_for_hmi || exit 1
    # HMI readiness can arrive after PHONE_REQUEST_111. The native recovery mode
    # validates the writer PID/generation/cookie before accepting that live stream.
    if [ -z "$RESTART_REASON" ]; then
      ALT111_RECOVER_CURRENT_SESSION=1
      export ALT111_RECOVER_CURRENT_SESSION
      echo "COLD_START_RECOVER_CURRENT_SESSION=1"
    fi
  fi
  mascot_preload=
  if [ -d /proc/boot ]; then
    if [ -s "$ROOT/libcarplay_mascot.so" ] && [ -s "$ROOT/mascots.rgba" ]; then
      mascot_preload="$ROOT/libcarplay_mascot.so"
      CARPLAY_MASCOT_ATLAS="$ROOT/mascots.rgba"
      export CARPLAY_MASCOT_ATLAS
    else
      echo "MASCOT=UNAVAILABLE missing_optional_renderer_or_assets"
    fi
  fi
  LD_PRELOAD="$mascot_preload" exec "$BIN" $MIRROR_ARGS
) >>"$LOGFILE" 2>&1 &
PID=$!
echo "$PID" > "$PIDFILE"

# The QNX sidecar deliberately freezes the last decoded frame through temporary
# stalls. The stock hook already emits DIRECT111_TAP_STOP only when the private
# stream is really torn down. Observe that append-only log after first present:
# teardown -> SIGTERM sidecar -> marker(false) -> Java releases Context80.
# If BaseVideo demand remains active, start a fresh sidecar so a later CarPlay
# reconnect can consume the next PHONE_REQUEST_111 gate without rebooting MMI.
if [ "$SINK_TEST_GRID_MODE" = "0" ]; then
  (
    BASELINE="$(count_tap_stops)"

    # Before first physical destination present, ignore only stop records that
    # already existed when this watcher started. Any NEW DIRECT111_TAP_STOP is a
    # real teardown for the current attempt and must not be absorbed into the
    # baseline; otherwise the sidecar can wait forever on an ended session.
    while [ ! -f "$STOP_GUARD" ] && [ ! -f "$BASE_READY" ]; do
      trim_flat_log "$LOGFILE" 8388608
      if ! sidecar_is_current; then
        current_owner=$(cat "$PIDFILE" 2>/dev/null || true)
        [ -n "$current_owner" ] && [ "$current_owner" != "$PID" ] || rm -f "$WATCH_PIDFILE"
        schedule_abnormal_restart "before_first_present" || true
        exit 0
      fi
      if ! mh_poll; then
        recover_stalled_mirror || echo "WARN: mirror recovery incomplete; main CarPlay left untouched"
        exit 0
      fi

      CURRENT_STOPS="$(count_tap_stops)"
      if [ "$CURRENT_STOPS" -lt "$BASELINE" ]; then
        BASELINE="$CURRENT_STOPS"
      elif [ "$CURRENT_STOPS" -gt "$BASELINE" ]; then
        echo "LIFECYCLE_WATCH=PRIVATE111_STOP detected=1 phase=before_first_present sidecar_pid=$PID stop_count=$CURRENT_STOPS action=TERM_AND_RESTART_IF_DEMAND"
        kill -TERM "$PID" 2>/dev/null || true
        WAIT_N=0
        while kill -0 "$PID" 2>/dev/null && [ "$WAIT_N" -lt 10 ]; do
          sleep 1
          WAIT_N=$((WAIT_N + 1))
        done
        if kill -0 "$PID" 2>/dev/null; then
          echo "LIFECYCLE_WATCH=SIDECAR_TERM_TIMEOUT phase=before_first_present action=KILL pid=$PID"
          kill -KILL "$PID" 2>/dev/null || true
        fi
        rm -f "$PIDFILE" "$READY" "$BASE_READY" "$WATCH_PIDFILE"

        if [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ]; then
          echo "LIFECYCLE_WATCH=RESTART_NEXT_SESSION phase=before_first_present demand=$DEMAND gate_policy=next_PHONE_REQUEST_111"
          ALT111_MIRROR_RESTART_REASON=private111_session_end \
          ALT111_MIRROR_RESTART_COUNT=0 \
          ALT111_RECOVER_CURRENT_SESSION=0 \
            /bin/sh "$ROOT/start_vehicle.sh" >>"$AUTORESTART_LOG" 2>&1 &
        fi
        exit 0
      fi
      sleep "$MH_INTERVAL"
    done

    sidecar_is_current || {
      schedule_abnormal_restart "before_watch_armed" || true
      exit 0
    }
    [ ! -f "$STOP_GUARD" ] || exit 0

    echo "LIFECYCLE_WATCH=ARMED sidecar_pid=$PID stop_count=$BASELINE hook_log=$HOOK_LOG base_ready=$BASE_READY"

    while [ ! -f "$STOP_GUARD" ]; do
      trim_flat_log "$LOGFILE" 8388608
      if ! sidecar_is_current; then
        current_owner=$(cat "$PIDFILE" 2>/dev/null || true)
        [ -n "$current_owner" ] && [ "$current_owner" != "$PID" ] || rm -f "$WATCH_PIDFILE"
        schedule_abnormal_restart "after_first_present" || true
        exit 0
      fi
      if ! mh_poll; then
        recover_stalled_mirror || echo "WARN: mirror recovery incomplete; main CarPlay left untouched"
        exit 0
      fi
      CURRENT_STOPS="$(count_tap_stops)"
      if [ "$CURRENT_STOPS" -lt "$BASELINE" ]; then
        # Defensive handling for an unexpected log replacement/truncation.
        BASELINE="$CURRENT_STOPS"
      elif [ "$CURRENT_STOPS" -gt "$BASELINE" ]; then
        echo "LIFECYCLE_WATCH=PRIVATE111_STOP detected=1 sidecar_pid=$PID stop_count=$CURRENT_STOPS action=TERM_AND_RESTART_IF_DEMAND"
        kill -TERM "$PID" 2>/dev/null || true
        WAIT_N=0
        while kill -0 "$PID" 2>/dev/null && [ "$WAIT_N" -lt 10 ]; do
          sleep 1
          WAIT_N=$((WAIT_N + 1))
        done
        if kill -0 "$PID" 2>/dev/null; then
          echo "LIFECYCLE_WATCH=SIDECAR_TERM_TIMEOUT action=KILL pid=$PID"
          kill -KILL "$PID" 2>/dev/null || true
        fi

        rm -f "$PIDFILE" "$READY" "$BASE_READY"
        rm -f "$WATCH_PIDFILE"

        if [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ]; then
          echo "LIFECYCLE_WATCH=RESTART_NEXT_SESSION demand=$DEMAND gate_policy=next_PHONE_REQUEST_111"
          ALT111_MIRROR_RESTART_REASON=private111_session_end \
          ALT111_MIRROR_RESTART_COUNT=0 \
          ALT111_RECOVER_CURRENT_SESSION=0 \
            /bin/sh "$ROOT/start_vehicle.sh" >>"$AUTORESTART_LOG" 2>&1 &
        else
          echo "LIFECYCLE_WATCH=NO_RESTART demand_present=$([ -f "$DEMAND" ] && echo 1 || echo 0) explicit_stop=$([ -f "$STOP_GUARD" ] && echo 1 || echo 0)"
        fi
        exit 0
      fi
      sleep "$MH_INTERVAL"
    done
  ) >>"$LOGFILE" 2>&1 &
  WATCH_PID=$!
  echo "$WATCH_PID" > "$WATCH_PIDFILE"
  echo "MIRROR_LIFECYCLE_WATCH=STARTED pid=$WATCH_PID trigger=DIRECT111_TAP_STOP after_first_present=1 restart_while_demand=1"
fi

sleep 1
if ! kill -0 "$PID" 2>/dev/null; then
  current_owner=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ -n "$current_owner" ] && [ "$current_owner" != "$PID" ]; then
    echo "MIRROR_DISPLAY=SUPERSEDED"
    exit 0
  fi
  echo "ERROR: mirror display exited during startup" >&2
  tail -80 "$LOGFILE" 2>/dev/null || true
  if [ -f "$WATCH_PIDFILE" ]; then
    WATCH_PID="$(cat "$WATCH_PIDFILE" 2>/dev/null || true)"
    [ -z "$WATCH_PID" ] || kill -TERM "$WATCH_PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE" "$WATCH_PIDFILE"
  if schedule_abnormal_restart "startup_probe"; then
    echo "MIRROR_DISPLAY=RECOVERY_SCHEDULED current_session_validation=required"
    exit 0
  fi
  exit 3
fi

echo "MIRROR_DISPLAY=STARTED_OR_WAITING_FOR_HMI pid=$PID log=$LOGFILE volatile_mode=$VOLATILE_MODE sink_test_grid=$SINK_TEST_GRID_MODE"
if [ "$SINK_TEST_GRID_MODE" = 1 ]; then
  echo "WAITING_FOR=BASEVIDEO_ACTIVE_then_Java_CTX80 test_grid_already_presented=1"
else
  echo "WAITING_FOR=PHONE_REQUEST_111_then_H264_TAP_then_DECODER_FIRST_FRAME_then_DISPLAYABLE3"
fi
