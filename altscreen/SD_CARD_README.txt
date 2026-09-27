MMI Cockpit CarPlay AltScreen + RGI SD Package

This personal native-MMI menu package requires MHI2Q_US_AUG22_P5145 / MU1316.
The menu appears in NAV -> right drawer -> Navigation settings -> Carplay Altscreen.
No menu option reboots MMI. Connection changes explicitly require CarPlay reconnect.
Optional mascots: Carplay Altscreen -> Overlay appearance -> Map mascot ->
Off / Raccoon / Nian. Default Off. They animate inside the bottom of the cluster
map, above the separate information bar; not on the main MMI or Audi native map.
The reference artwork is included for this owner's private use, not licensed here
for redistribution. Host graphics tests are not QNX or vehicle validation.
Native-menu rendering, controls and wireless-adapter behavior need in-car validation.
Owner report (2026-09-27, last prepared test card cb64eee): navigation text and
distance work. Google Maps centering is intermittent and can regress on later
routes; the top-card preset is not a confirmed centering fix. This report does not
validate the later native-menu or recovery additions.

1. Copy all contents of this package directly to the root of a FAT32 SD card.
   The card root should directly contain:
   metainfo2.txt, Toolbox/, SD_CARD_README.txt, and SHA256SUMS-SD.txt.
2. If your previous SD card already has an MMI-Cockpit-Carplay directory with stock
   backups, state, or logs, preserve it and copy it to the new card. RESTORE ORIGINAL
   requires the original backups created during the initial installation.
3. Unit already has MIB Toolbox: Run "Update Toolbox" in the Toolbox menu to refresh
   scripts and the Green Engineering Menu (GEM).
   Unit does not have MIB Toolbox: Install the menu and scripts via the MMI Software
   Update (SWDL) menu using metainfo2.txt.
4. Execution in GEM (MMI-Cockpit-Carplay menu):
   Disconnect iPhone -> INSTALL -> Full MMI Reboot -> START -> Full MMI Reboot ->
   Connect iPhone / CarPlay -> Launch navigation.
5. This fork targets the owner's Q5 2020 P5145/MU1316 only. Earlier upstream builds
   were tested on Audi Q5 FY 2019, MHI2Q_ER_AUG22_P5092, MU1329.
   - Display video is rendered at 1:1 aspect ratio with clean bottom crop (no distortion).
   - Watermarks are completely removed (transparent overlay).
   - Startup screen displays the Audi logo (logo.rgba) for ~2 seconds.
   - Steering-wheel roller zooms CarPlay map and native map simultaneously.
   - Four Cluster map layout presets are available in GEM. Maneuver card on top
     is the default when no preference is saved; upgrades preserve explicit choices.
     Select "original AltScreen" to request the original layout. Reconnect to apply.
6. To restore stock firmware configurations, run RESTORE ORIGINAL from the menu.
7. Runtime state flags are stored on the unit at /mnt/app/root/carplay-altscreen/state.
   Cold boots do not require the SD card to remain inserted once installed.
   The SD card holds stock backups and diagnostic logs; always retain your backup card.
8. Temporary runtime files are written to /tmp. Configuration file replacements are
   performed atomically with .carplay-stock backups maintained.
9. EXPORT DIAGNOSTICS ONLY (no restore) saves a summary and bounded private logs to
   MMI-Cockpit-Carplay/logs/exports/. It does not stop, restart or restore anything.
   Raw logs may contain destinations/device details; review them before sharing.
   STORE LOGS + RESTORE remains a separate restoration action.
10. The mirror waits for the Java cluster controller during cold start. Calibrated
    active-input/presentation stalls may trigger a bounded, identity-checked mirror
    restart; USB and the main CarPlay process are not reset. Unknown/idle telemetry
    does not trigger recovery. Retry exhaustion withdraws stale video readiness.

Earlier upstream releases reported on-vehicle display, aspect-ratio, zoom and route
guidance results. Those reports do not validate the newer native menu, alignment
reapplication, customization or mascot additions on this owner's vehicle.
