#!/bin/bash
set -euo pipefail
PROJECT=${1:?project required}
test -f /.dockerenv && test ! -d /proc/boot
T=$(mktemp -d)
trap 'rm -rf "$T"; rm -f /ramdisk/carplay_supervisor.owner /ramdisk/carplay_menu_session /ramdisk/carplay_maneuver_render.pid /ramdisk/MMI-Cockpit-Carplay.mirror.health' EXIT
mkdir -p "$T/bin" "$T/hooks" "$T/app" /ramdisk
for name in carplay_supervisor.owner carplay_menu_session carplay_maneuver_render.pid MMI-Cockpit-Carplay.mirror.health; do
    test ! -e "/ramdisk/$name"
done
cat > "$T/bin/mv" <<'MV'
#!/bin/sh
for arg in "$@"; do
    case "$arg" in /tmp/*) echo "QNX_FIXTURE: shared-memory rename unsupported" >&2; exit 1;; esac
done
exec /bin/mv "$@"
MV
chmod +x "$T/bin/mv"
export PATH="$T/bin:$PATH"
touch "$T/probe"
if mv "$T/probe" "$T/renamed"; then echo "FAIL: fault fixture inactive"; exit 1; fi
cp "$PROJECT/deploy/smartphone_integrator/carplay_settings.sh" "$T/hooks/"
cp "$PROJECT/tests/fixtures/carplay-preferences.txt" "$T/preferences"
printf '#!/bin/sh\necho "$$" > "%s/dio"\n' "$T" > "$T/app/dio_manager"
chmod +x "$T/app/dio_manager"
env -u OWNER_FILE -u MENU_SESSION_FILE H="$T/hooks" DIODIR="$T/app" WLOG="$T/wrapper.log" \
    MENU_VERBOSE_FILE="$T/verbose" CP_SETTINGS_FILE="$T/preferences" \
    /bin/sh "$PROJECT/deploy/smartphone_integrator/carplay_startup.sh"
test "$(cat /ramdisk/carplay_supervisor.owner)" = "$(cat "$T/dio")"
grep -q '^config_error=0$' /ramdisk/carplay_menu_session
unset CP_MANEUVER_PID_FILE
source "$PROJECT/deploy/smartphone_integrator/carplay_processes.sh"
cp_renderer_record_pid maneuver_render "$$"
test "$(cat /ramdisk/carplay_maneuver_render.pid)" = "$$"
ALTSCREEN_CHAIN_TESTING=0 TMP_ROOT=/tmp RESTART_COUNT=0
source "$PROJECT/altscreen/Toolbox/carplay_alt_screen/mirror_display/release/mirror_health.sh"
mh_publish VIDEO_PROGRESS
grep -q '^HEALTH_STATE=VIDEO_PROGRESS$' /ramdisk/MMI-Cockpit-Carplay.mirror.health
echo "Volatile state: production owner/session/PID/health paths survive unsupported /tmp mv PASS"
