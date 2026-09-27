#!/bin/sh
# MMI-Cockpit-Carplay: choose how the iPhone lays out the CarPlay map on the cluster.
#   cluster_layout.sh default|top|right|noeta
# Writes /mnt/app/root/hooks/cluster_ui.url; the RGI Java sends it as showUI to the
# cluster display each time the AltScreen video comes up (reconnect the phone).
# With no saved file, Java uses topaligned. The legacy "default" argument explicitly
# selects the original AltScreen layout, so all four presets remain available.
set -u
TESTING=${ALTSCREEN_CHAIN_TESTING:-0}
DEVICE_ROOT=""
[ "$TESTING" = 1 ] && DEVICE_ROOT=${ALTSCREEN_CHAIN_ROOT:-}
URL_FILE="$DEVICE_ROOT/mnt/app/root/hooks/cluster_ui.url"
BASE="maps:/car/instrumentcluster/map"
case "${1:-}" in
    default) URL="$BASE"; MENU_VALUE=3 ;;
    top)     URL="$BASE?maneuverLayout=topaligned"; MENU_VALUE=0 ;;
    right)   URL="$BASE?maneuverLayout=rightaligned"; MENU_VALUE=1 ;;
    noeta)   URL="$BASE?showETA=no"; MENU_VALUE=2 ;;
    *) echo "usage: cluster_layout.sh default|top|right|noeta"; exit 2 ;;
esac
PREFS="$DEVICE_ROOT/mnt/persist/var/app/carplay_altscreen/preferences"
if [ -e "$PREFS" ]; then
    CP_SETTINGS_FILE="$PREFS"
    . "$DEVICE_ROOT/mnt/app/root/hooks/carplay_settings.sh" || exit 1
    cp_setting layout 0 >/dev/null || exit 1
    TEMP="$PREFS.new.$$"
    awk -v value="$MENU_VALUE" '/^layout=/{print "layout=" value;next}{print}' "$PREFS" > "$TEMP" &&
        mv -f "$TEMP" "$PREFS" && sync || { echo "FAIL: cannot save MMI layout preference"; exit 1; }
    echo "CLUSTER_LAYOUT=$1 saved_in=MMI_preferences"
    echo "Reconnect the iPhone to apply."
    exit 0
fi
[ -d "$(dirname -- "$URL_FILE")" ] || { echo "FAIL: RGI is not installed (no /mnt/app/root/hooks)"; exit 1; }
[ "$TESTING" = 1 ] || mount -uw /mnt/app || { echo "FAIL: cannot mount /mnt/app writable"; exit 1; }
printf '%s\n' "$URL" > "$URL_FILE.new" && mv -f "$URL_FILE.new" "$URL_FILE" || {
    rm -f "$URL_FILE.new"; [ "$TESTING" = 1 ] || mount -ur /mnt/app; echo "FAIL: cannot write $URL_FILE"; exit 1; }
echo "CLUSTER_LAYOUT=$1 url=$URL"
sync
[ "$TESTING" = 1 ] || mount -ur /mnt/app
echo "Reconnect the iPhone to apply."
