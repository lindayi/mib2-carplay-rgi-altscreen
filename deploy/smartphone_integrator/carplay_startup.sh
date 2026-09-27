#!/bin/sh
# Thin smartphone_integrator child wrapper.
# Publish this generation, start the renderer monitor, then immediately become
# dio_manager so SI retains exact PID/watchdog ownership.

DIODIR=${DIODIR:-/mnt/app/eso/bin/apps}
H=${H:-/mnt/app/root/hooks}
WLOG=${WLOG:-/tmp/carplay_wrapper.log}
OWNER_FILE=${OWNER_FILE:-/tmp/carplay_supervisor.owner}
DIO_PID=$$
MONITOR_PID=
MENU_SESSION_FILE=${MENU_SESSION_FILE:-/tmp/carplay_menu_session}
MENU_VERBOSE_FILE=${MENU_VERBOSE_FILE:-/tmp/carplay_menu_verbose}

# A disabled next session executes the original receiver without our preloads.
# The MMI menu and its saved preferences remain installed.
MENU_ERROR=0
if [ -r "$H/carplay_settings.sh" ]; then
    . "$H/carplay_settings.sh"
    MENU_ENABLED=$(cp_setting enabled 1) || { MENU_ENABLED=0; MENU_ERROR=1; }
    MENU_VERBOSE=$(cp_setting verbose 0) || { MENU_VERBOSE=0; MENU_ERROR=1; }
    MENU_MODE=$(cp_setting mode 0) || { MENU_MODE=0; MENU_ENABLED=0; MENU_ERROR=1; }
else
    MENU_ENABLED=0; MENU_VERBOSE=0; MENU_MODE=0; MENU_ERROR=1
    echo "[startup] settings helper missing; using unmodified CarPlay receiver" >> "$WLOG"
fi
MENU_STAGE="$MENU_SESSION_FILE.$$"
if [ "$MENU_VERBOSE" = 1 ]; then
    : > "$MENU_VERBOSE_FILE" || { MENU_ERROR=1; echo "[startup] cannot enable menu logging" >> "$WLOG"; }
else
    rm -f "$MENU_VERBOSE_FILE" || { MENU_ERROR=1; echo "[startup] cannot clear menu logging marker" >> "$WLOG"; }
fi
OWNER_STAGE=${OWNER_FILE}.${DIO_PID}
echo "$DIO_PID" > "$OWNER_STAGE" && mv "$OWNER_STAGE" "$OWNER_FILE" || {
    MENU_ERROR=1;echo "[startup] cannot publish session ownership" >> "$WLOG"
}
[ "$MENU_ERROR" = 0 ] || MENU_ENABLED=0
MENU_VIDEO=0
[ "$MENU_ENABLED" != 1 ] || [ "$MENU_MODE" = 2 ] || MENU_VIDEO=1
printf 'pid=%s\nenabled=%s\nvideo=%s\nverbose=%s\nconfig_error=%s\n' \
    "$$" "$MENU_ENABLED" "$MENU_VIDEO" "$MENU_VERBOSE" "$MENU_ERROR" > "$MENU_STAGE" &&
    mv -f "$MENU_STAGE" "$MENU_SESSION_FILE" || {
        echo "[startup] cannot publish menu session; stock receiver only" >> "$WLOG"
        MENU_ENABLED=0
    }
if [ "$MENU_ENABLED" != 1 ]; then
    echo "[startup] AltScreen disabled for this session; no project preloads or monitor" >> "$WLOG"
    unset LD_PRELOAD CARPLAY_PRELOAD_EXTRA
    exec "$DIODIR/dio_manager" "$@"
    echo "[startup] stock receiver exec failed" >> "$WLOG"
    exit 127
fi

startup_fail()
{
    echo "[startup] abort pid=$DIO_PID" >> "$WLOG"
    [ -n "$MONITOR_PID" ] && kill -15 "$MONITOR_PID" 2>/dev/null
    exit 127
}
trap 'startup_fail' 1 2 15

[ -x "$DIODIR/dio_manager" ] || {
    echo "[startup] missing $DIODIR/dio_manager" >> "$WLOG"
    exit 127
}
[ -r "$H/libcarplay_hook.so" ] || {
    echo "[startup] missing $H/libcarplay_hook.so" >> "$WLOG"
    exit 127
}
[ -x "$H/carplay_monitor.sh" ] || {
    echo "[startup] missing $H/carplay_monitor.sh" >> "$WLOG"
    exit 127
}
[ -r "$H/carplay_processes.sh" ] || {
    echo "[startup] missing $H/carplay_processes.sh" >> "$WLOG"
    exit 127
}
cd "$DIODIR" 2>/dev/null || startup_fail

# Never expose a partially-written owner file to an older monitor.
echo "===== CarPlay generation pid=$DIO_PID ppid=${PPID:-unknown} =====" >> "$WLOG"

LD_PRELOAD= "$H/carplay_monitor.sh" "$DIO_PID" </dev/null >>"$WLOG" 2>&1 &
MONITOR_PID=$!

# Only dio_manager receives the hook. The monitor and the renderer explicitly
# clear LD_PRELOAD. Other preloads for dio_manager (the AltScreen universal hook)
# come first: CARPLAY_PRELOAD_EXTRA from the child env, then whatever LD_PRELOAD
# still holds here - a preload loaded into this shell may already have removed
# itself from it. Ours goes last; each entry once.
INHERITED_PRELOAD=${LD_PRELOAD:-}
PRELOAD_LIST=
OLD_IFS=$IFS
IFS=:
for lib in ${CARPLAY_PRELOAD_EXTRA:-} $INHERITED_PRELOAD $H/libcarplay_hook.so; do
    [ -n "$lib" ] || continue
    if [ "$MENU_VIDEO" = 0 ]; then
        case "$lib" in */libcarplay_altscreen.so) continue ;; esac
    fi
    case ":$PRELOAD_LIST:" in
        *":$lib:"*) ;;
        *) PRELOAD_LIST=${PRELOAD_LIST:+$PRELOAD_LIST:}$lib ;;
    esac
done
IFS=$OLD_IFS
LD_PRELOAD=$PRELOAD_LIST
export LD_PRELOAD
echo "[startup] preload inherited='${INHERITED_PRELOAD}' extra='${CARPLAY_PRELOAD_EXTRA:-}' -> '$LD_PRELOAD'" >> "$WLOG"

echo "[startup] exec dio_manager pid=$DIO_PID monitor=$MONITOR_PID" >> "$WLOG"
exec "$DIODIR/dio_manager" "$@"

# Preflight makes this reachable only for an exec/runtime loader failure.
STARTUP_EXEC_RC=$?
echo "[startup] exec dio_manager failed rc=$STARTUP_EXEC_RC" >> "$WLOG"
startup_fail
