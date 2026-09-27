#!/bin/sh
# Sourced by start_vehicle.sh. No USB, dio_manager, HMI or context writes.
MH_CONTEXT="$TMP_ROOT/carplay_cluster.ctx"
MH_OWNER="$TMP_ROOT/carplay_supervisor.owner"
MH_STATUS="$TMP_ROOT/MMI-Cockpit-Carplay.mirror.health"
MH_RING=/dev/shmem/carplay111_h264
MH_INTERVAL=1
MH_READY_LIMIT=90
MH_STALL_LIMIT=40
if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
    case "$TMP_ROOT" in /tmp/*|/var/tmp/*) ;; *) echo "FAIL: invalid health test root"; exit 2 ;; esac
    MH_RING="$TMP_ROOT/carplay111_h264"
    MH_INTERVAL=${ALT111_TEST_INTERVAL:-0.05}
    MH_READY_LIMIT=${ALT111_TEST_READY_LIMIT:-6}
    MH_STALL_LIMIT=${ALT111_TEST_STALL_LIMIT:-6}
fi
MH_LAST_INPUT=""; MH_LAST_PRESENT=""; MH_STALLED=0
MH_SAMPLES=0; MH_REPORT_GAP=0; MH_MAX_REPORT_GAP=0

mh_publish(){
    mh_tmp="$MH_STATUS.new.$$"
    printf 'HEALTH_STATE=%s\nHEALTH_STALLED_TICKS=%s\nHEALTH_RESTART_COUNT=%s\nHEALTH_PROGRESS_SAMPLES=%s\n' \
        "$1" "$MH_STALLED" "$RESTART_COUNT" "$MH_SAMPLES" > "$mh_tmp" &&
        mv -f "$mh_tmp" "$MH_STATUS" ||
        { echo "WARN: mirror health status could not be published"; return 1; }
}
mh_context_ready(){
    [ -r "$MH_CONTEXT" ] && grep -Eq '^ctx=(74|80|81)$' "$MH_CONTEXT"
}
mh_phone_running(){
    mh_owner=""
    [ ! -r "$MH_OWNER" ] || read -r mh_owner < "$MH_OWNER"
    case "$mh_owner" in ''|*[!0-9]*|0|1) return 1 ;; esac
    kill -0 "$mh_owner" 2>/dev/null
}
mh_wait_for_hmi(){
    mh_wait=0
    echo "COLD_START_WAIT=JAVA_CLUSTER_CONTEXT main_carplay_delay=NONE"
    [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ] || return 1
    while ! mh_context_ready; do
        [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ] || return 1
        if mh_phone_running; then mh_wait=$((mh_wait + 1)); else mh_wait=0; fi
        if [ "$mh_wait" -ge "$MH_READY_LIMIT" ]; then
            echo "COLD_START_TIMEOUT=JAVA_CLUSTER_CONTEXT"
            return 1
        fi
        sleep "$MH_INTERVAL" || return 1
    done
    [ -f "$DEMAND" ] && [ ! -f "$STOP_GUARD" ] || return 1
    echo "COLD_START_READY=JAVA_CLUSTER_CONTEXT"
}
mh_input_signature(){
    [ -s "$MH_RING" ] || return 1
    # Known ring counters at 0x18: write offset, sequence, bytes and record count.
    # Do not mistake changes to session metadata for incoming video activity.
    mh_sig=$(dd if="$MH_RING" bs=4 skip=6 count=4 2>/dev/null | cksum) || return 1
    set -- $mh_sig
    [ "$#" -eq 2 ] && [ "$2" = 16 ] || return 1
    printf '%s\n' "$1"
}
mh_present_signature(){
    [ -f "$LOGFILE" ] || { echo UNKNOWN; return; }
    tail -c 65536 "$LOGFILE" | awk '
      /PHASE=RUN / {
        gen=""; frames=""
        for(i=1;i<=NF;i++) {
          if($i ~ /^generation=[0-9]+$/) gen=$i
          if($i ~ /^presented_frames=[0-9]+$/) frames=$i
        }
        if(gen!="" && frames!="") last=gen ":" frames
      }
      END { if(last=="") print "UNKNOWN"; else print last }
    '
}
mh_poll(){
    if [ -f "$STOP_GUARD" ] || [ ! -f "$DEMAND" ]; then
        MH_STALLED=0; mh_publish STOPPED || :; return 0
    fi
    if ! mh_context_ready; then
        MH_STALLED=0; MH_LAST_INPUT=""; MH_LAST_PRESENT=""
        MH_SAMPLES=0; MH_REPORT_GAP=0; MH_MAX_REPORT_GAP=0
        mh_publish WAITING_FOR_HMI || :; return 0
    fi
    mh_input=$(mh_input_signature) || mh_input=""
    mh_present=$(mh_present_signature) || mh_present=UNKNOWN
    if [ "$mh_present" = UNKNOWN ]; then
        MH_STALLED=0; MH_SAMPLES=0; MH_REPORT_GAP=0; MH_MAX_REPORT_GAP=0
        MH_LAST_INPUT=$mh_input; MH_LAST_PRESENT=UNKNOWN
        mh_publish WAITING_FOR_TELEMETRY || :
        return 0
    fi
    if [ -z "$mh_input" ] || [ "$mh_input" = "$MH_LAST_INPUT" ]; then
        MH_STALLED=0
        MH_REPORT_GAP=0
        mh_state=NO_INPUT_PROGRESS
    elif [ -n "$MH_LAST_INPUT" ] && [ "$mh_present" = "$MH_LAST_PRESENT" ]; then
        MH_REPORT_GAP=$((MH_REPORT_GAP + 1))
        if [ "$MH_SAMPLES" -ge 2 ]; then MH_STALLED=$((MH_STALLED + 1)); else MH_STALLED=0; fi
        mh_state=WAITING_FOR_PRESENT
    else
        if [ "$mh_present" != UNKNOWN ] && [ -n "$MH_LAST_PRESENT" ] && [ "$MH_LAST_PRESENT" != UNKNOWN ]; then
            MH_SAMPLES=$((MH_SAMPLES + 1))
            [ "$MH_REPORT_GAP" -le "$MH_MAX_REPORT_GAP" ] || MH_MAX_REPORT_GAP=$MH_REPORT_GAP
        fi
        MH_REPORT_GAP=0
        MH_STALLED=0
        mh_state=VIDEO_PROGRESS
    fi
    MH_LAST_INPUT=$mh_input; MH_LAST_PRESENT=$mh_present
    # Calibrate against observed RUN updates before treating missing reports as a
    # freeze. Unknown/buffered logging must not become an automatic kill policy.
    mh_limit=$((MH_MAX_REPORT_GAP * 4))
    [ "$mh_limit" -ge "$MH_STALL_LIMIT" ] || mh_limit=$MH_STALL_LIMIT
    if [ "$MH_SAMPLES" -ge 2 ] && [ "$MH_STALLED" -ge "$mh_limit" ]; then
        mh_publish PRESENTATION_STALLED || :
        return 1
    fi
    mh_publish "$mh_state" || :
    return 0
}
