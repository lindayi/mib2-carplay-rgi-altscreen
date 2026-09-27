#!/bin/sh
# Fixed normal-MMI actions. No install, restore, USB reset or MMI reboot operations.
set -u
ROOT=""
if [ "${ALTSCREEN_CHAIN_TESTING:-0}" = 1 ]; then
    ROOT=${ALTSCREEN_CHAIN_ROOT:-}
    case "$ROOT" in /tmp/*|/var/tmp/*) ;; *) echo "FAIL: invalid test root"; exit 2 ;; esac
fi
ACTION=${1:-}
if [ "$#" -eq 2 ]; then
    case "$2" in /tmp/carplay_menu_action.*.log) ;; *) echo "FAIL: invalid action log"; exit 2 ;; esac
    number=${2#/tmp/carplay_menu_action.};number=${number%.log}
    case "$number" in ''|*[!0-9]*) echo "FAIL: invalid action log token"; exit 2 ;; esac
    exec > "$2" 2>&1
elif [ "$#" -ne 1 ]; then
    echo "Usage: carplay_mmi_action.sh action [result-log]"; exit 2
fi
RUNTIME="$ROOT/mnt/app/root/carplay-altscreen"
HOOKS="$ROOT/mnt/app/root/hooks"
TMP="$ROOT/tmp"
SETTINGS="$HOOKS/carplay_settings.sh"
CP_SETTINGS_FILE="$ROOT/mnt/persist/var/app/carplay_altscreen/preferences"
ACTIVE="$TMP/mmi-mirror-active"
READY="$TMP/mmi-mirror-basevideo.ready"
export ALT111_MIRROR_TMP_ROOT="$TMP" ALT111_MIRROR_ACTIVE_FILE="$ACTIVE" ALT111_JAVA_BASE_READY_FILE="$READY"
export ALT111_SETTINGS_HELPER="$SETTINGS"
SCRIPTS="$RUNTIME/bin"
case "$ACTION" in
    export_summary|export_full)
        [ -f "$SCRIPTS/export_mmi_cockpit_diagnostics.sh" ] || { echo "FAIL: exporter missing"; exit 1; }
        if [ "$ACTION" = export_summary ]; then
            exec /bin/sh "$SCRIPTS/export_mmi_cockpit_diagnostics.sh" --summary
        fi
        exec /bin/sh "$SCRIPTS/export_mmi_cockpit_diagnostics.sh"
        ;;
    sync_mirror|restart_video) ;;
    *) echo "FAIL: unsupported normal-MMI action"; exit 2 ;;
esac
[ -r "$SETTINGS" ] || { echo "FAIL: settings helper missing"; exit 1; }
. "$SETTINGS"
export CP_SETTINGS_FILE
enabled=$(cp_setting enabled 1) || exit 1
mode=$(cp_setting mode 0) || exit 1
session="$TMP/carplay_menu_session"
session_video(){
    [ -r "$session" ] || return 1
    session_pid=$(sed -n 's/^pid=\([0-9][0-9]*\)$/\1/p' "$session")
    case "$session_pid" in ''|*[!0-9]*|0|1) return 1 ;; esac
    kill -0 "$session_pid" 2>/dev/null &&
        [ "$(cat "$TMP/carplay_supervisor.owner" 2>/dev/null)" = "$session_pid" ] &&
        grep -q '^enabled=1$' "$session" && grep -q '^video=1$' "$session" &&
        grep -q '^config_error=0$' "$session"
}
video=0
if session_video; then video=1; fi
if [ "$enabled" != 1 ] || [ "$mode" = 2 ]; then
    if [ "$ACTION" = restart_video ]; then echo "FAIL: enable video and reconnect CarPlay first"; exit 1; fi
    rm -f "$ACTIVE" "$READY" || { echo "FAIL: cannot withdraw video demand"; exit 1; }
    [ -f "$SCRIPTS/mirror/stop_vehicle.sh" ] || { echo "VIDEO=DISABLED runtime_absent=YES"; exit 0; }
    exec /bin/sh "$SCRIPTS/mirror/stop_vehicle.sh"
fi
if [ "$video" != 1 ]; then
    if [ "$ACTION" = restart_video ]; then echo "FAIL: reconnect CarPlay for a video-enabled session"; exit 1; fi
    echo "VIDEO=WAITING_FOR_CONNECTION";exit 0
fi
[ -f "$RUNTIME/state/basevideo3.enabled" ] && [ ! -e "$RUNTIME/state/transaction.pending" ] &&
    [ ! -e "$RUNTIME/state/start.pending" ] || {
        echo "FAIL: runtime is not installed/armed or has an incomplete transaction"; exit 1; }
[ -f "$SCRIPTS/mirror/start_vehicle.sh" ] || { echo "FAIL: mirror launcher missing"; exit 1; }
if [ "$ACTION" = restart_video ]; then
    /bin/sh "$SCRIPTS/mirror/stop_vehicle.sh" || exit 1
fi
enabled=$(cp_setting enabled 1) || exit 1
mode=$(cp_setting mode 0) || exit 1
[ "$enabled" = 1 ] && [ "$mode" != 2 ] ||
    { echo "FAIL: video action cancelled by newer preferences"; exit 1; }
session_video || { echo "FAIL: video connection changed; reconnect CarPlay"; exit 1; }
touch "$ACTIVE" || { echo "FAIL: cannot publish video demand"; exit 1; }
exec /bin/sh "$SCRIPTS/mirror/start_vehicle.sh"
