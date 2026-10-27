MMI Cockpit CarPlay AltScreen + RGI SD Package

This source-built candidate requires MHI2Q_US_AUG22_P5145 / MU1316.
This candidate enables Maps30 profile selection and exact-unit decoder/MCD
performance changes. The stock-file changes apply after the installation reboot,
affect both video paths/media handling, and remain until RESTORE ORIGINAL.
Master Off controls CarPlay overlays, not these installation-level changes.
Unknown firmware file bytes fail before installation. Keep the matching SD,
original backups and transaction history for restore/rescue.
It has passed host checks, but has not been verified in the vehicle.
The menu appears in NAV -> right drawer -> Navigation settings -> Carplay Altscreen.
No menu option reboots MMI. Connection changes explicitly require CarPlay reconnect.
Optional mascots: Carplay Altscreen -> Overlay appearance -> Map mascot ->
Off / Raccoon / Nian / Capybara / Lizard. Default Off.
They animate inside the bottom of the cluster
map, above the separate information bar; not on the main MMI or Audi native map.
The reference artwork is included for this owner's private use, not licensed here
for redistribution. Host graphics tests are not QNX or vehicle validation.
The new runtime, native-menu controls and wireless-adapter behavior need a new
in-car trial. Earlier local menu tests do not validate this source candidate.
Owner report (2026-09-27, last prepared test card cb64eee): navigation text and
distance work. Google Maps centering is intermittent and can regress on later
routes; the top-card preset is not a confirmed centering fix. This report does not
validate the later native-menu or recovery additions.

1. Before updating the card, back up and hash-verify all data files, including
   hidden files except Windows System Volume Information. Keep earlier backups.
   Merge this package's contents into the FAT32 card root. Do not format, mirror
   or delete unrelated files. Verify package hashes and unchanged existing files.
   The card root should directly contain:
   metainfo2.txt, Toolbox/, SD_CARD_README.txt, and SHA256SUMS-SD.txt.
2. If your previous SD card already has an MMI-Cockpit-Carplay directory with stock
   backups, state, or logs, preserve it and copy it to the new card. RESTORE ORIGINAL
   requires the original backups created during the initial installation.
3. Unit already has MIB Toolbox: Run "Update Toolbox" in the Toolbox menu to refresh
   scripts and the Green Engineering Menu (GEM).
   Do not use the red Software Update menu for this existing-Toolbox upgrade.
4. Execution in GEM (MMI-Cockpit-Carplay menu):
   Park with stable power -> Disconnect iPhone -> INSTALL -> Wait for PASS ->
   One full MMI reboot -> Connect iPhone / CarPlay -> Launch navigation.
   Stop on FAIL. INSTALL includes boot-only START arming. The separate START
   entry remains compatible but is not a required second installation step.
   This replacement transaction is host-tested, not vehicle-confirmed.
5. This fork targets the owner's Q5 2020 P5145/MU1316 only. Earlier upstream builds
   were tested on Audi Q5 FY 2019, MHI2Q_ER_AUG22_P5092, MU1329.
   - Display video is rendered at 1:1 aspect ratio with clean bottom crop (no distortion).
   - No logo or watermark assets are used. The mirror waits for actual video.
   - Steering-wheel roller zooms CarPlay map and native map simultaneously.
   - Normal-MMI and VC settings provide top/right/left cards, map-only and original
     layouts, plus phone ETA and Off/our badge/phone sign. Card position applies
     live; profile, phone sign and ETA require reconnect. Top is the default when
     no preference is saved; upgrades preserve explicit choices. GEM's retired
     layout/area/shift scripts cannot change preferences.
6. To restore stock firmware configurations, run RESTORE ORIGINAL from the menu.
   Failed transactions retain recovery evidence. Do not delete it or bypass
   unit/checksum guards. Guarded manual rescue is separate from normal MMI:
   with the matching SD inserted, run the installed altscreen_rescue.sh helper
   under /mnt/app/root/carplay-altscreen/bin with "recover", or
   "restore-original --confirm-stock-rescue".
   Successful stock rescue removes that installed directory. Its reversal uses
   the matching SD's Toolbox/scripts/altscreen_rescue.sh with
   "undo --confirm-pre-rescue". Undo restores the pre-rescue state, even if damaged.
   If a performance regression prevents SD discovery but Green Menu still works:
   MMI-Cockpit-Carplay -> RESCUE: restore performance originals (no SD).
   Park with stable power and disconnect iPhone first. Wait for
   PERFORMANCE_RECOVERY=PASS, then perform one full MMI reboot.
   This restores only decoder/MCD files and modes from the installed, unit-bound
   kit. It does not remove CarPlay, restart MCD live or finish a failed INSTALL.
   On FAIL retain the evidence; the kit remains available for another attempt.
   This path needs functioning OS/HMI/Green Menu access, not an SD card.
   Full RESTORE ORIGINAL removes the kit; keep the matching SD for full recovery.
   If this SD lacks performance-original but an internal kit exists, full restore
   and stock rescue refuse without changing it. Use the matching backups or the
   no-SD action above; an older pre-performance backup is not sufficient.
   STATUS distinguishes stored and active priority configuration, but file hashes
   cannot establish that the running decoder/MCD has reloaded those files.
7. Runtime state flags are stored on the unit at /mnt/app/root/carplay-altscreen/state.
   Cold boots do not require the SD card to remain inserted once installed.
   The SD card holds stock backups and diagnostic logs; always retain your backup card.
8. Atomic volatile snapshots use /ramdisk. Ordinary bounded logs use /tmp.
   Configuration replacements retain their original backups. Applicable firewall
   variants also have separate, checksum-verified original backups.
9. EXPORT DIAGNOSTICS ONLY (no restore) saves a summary and bounded private logs to
   MMI-Cockpit-Carplay/logs/exports/. It does not stop, restart or restore anything.
   Raw logs may contain destinations/device details; review them before sharing.
   STORE LOGS + RESTORE remains a separate restoration action.
10. The mirror waits for the Java cluster controller during cold start. Calibrated
    active-input/presentation stalls may trigger a bounded, identity-checked mirror
    restart; USB and the main CarPlay process are not reset. Unknown/idle telemetry
    does not trigger recovery. Retry exhaustion withdraws stale video readiness.

The native hook/mirror and adapted V3.8 firewall helper are source-available under
PolyForm Noncommercial plus upstream additional terms, not OSI open source.
Notices and license texts are in Toolbox/carplay_alt_screen/mirror_display/release.
They do not grant rights to redistribute private mascot artwork.
Earlier vehicle reports do not validate this source-built candidate.
