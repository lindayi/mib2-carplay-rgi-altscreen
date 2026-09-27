# Carplay Altscreen: native MMI settings

Target: this owner's 2020 Q5, MHI2Q_US_AUG22_P5145 / MU1316 only.

## Entry and implementation

NAV -> right drawer -> Navigation settings -> **Carplay Altscreen**.
The stock screen is 400102. During the private build,
`tools/PatchNavigationSettings.java` validates the SHA256 of the reconstructed
`NaviScreenBag8.class` and replaces only the first MenuController constructor and
the ScreenWidgetEVO constructor in `mAPOPTNAVIGENERALSETTINGSMAIN`.
All other factory instructions and every other screen method remain unchanged.
The existing native fonts, rows, checkbox drawing, focus cursor, scrolling and
screen chrome are used. Our menu hides the stock menu items only while a submenu
is open; their original model bindings remain installed. Back returns to the
previous submenu, then the original Navigation Settings menu. Extension errors
are logged and withdraw our items rather than replacing the OEM settings page.

The woven OEM class is generated from the owner's private JAR into ignored build
output. It is not copied into the source repository. Do not relax the fingerprint
guard to make an unrelated firmware build pass.

## Settings and application

| Group | Controls | Application |
| --- | --- | --- |
| Main | Enabled | Off releases our cockpit modules immediately; the complete preload/extra-video bypass takes effect on the next CarPlay session. On after bypass also requires reconnect. |
| Presentation | CarPlay map + guidance / CarPlay map only / Audi map + guidance | Display and RGI changes apply live when possible. The launcher excludes the AltScreen preload for a new Audi-map session; switching back then needs reconnect. |
| Phone map | Top / right / no ETA / original AltScreen | Reconnect; requests the iPhone layout, not direct marker positioning. |
| Guidance | Overlay distance, road/exit, lanes, arrow progress fill | Live. This controls our overlay, not the HUD's BAP data. |
| Appearance | Standard/large text, shorten/scroll displayed road label, solid/reduced backing | Live. Road transport remains bounded to 32 UTF-8 bytes, with grapheme-safe ellipsis; scrolling is of that bounded label, not unlimited text. |
| Controls | CarPlay wheel zoom and speed; touchpad DPAD bridge and sensitivity | Live. Disabling the touchpad bridge restores stock raw-pad forwarding; ordinary knob input remains stock. |
| Diagnostics | Read-only status, summary export, confirmed full export, next-session verbosity | Status and export are immediate. Logging applies to the next session. Full exports contain private raw logs; they are not anonymized. |
| Recovery | Automatic mirror recovery; confirmed video-only restart | Live, but only for an installed, armed video-enabled session. No USB, dio_manager or MMI restart command is exposed. |
| Reset | Reset display/control preferences | Confirmation required. Keeps master On/Off, Audi settings, phone pairing and installation. |

No preference invokes a full MMI reboot, disconnects the phone automatically, or
uninstalls the patch. The initial patch installation still needs the normal
installation reboot.

### Master Off and wireless adapters

The next-session launcher clears `LD_PRELOAD` and `CARPLAY_PRELOAD_EXTRA`, skips
our renderer monitor, and execs the original `dio_manager` when disabled. It also
publishes a session-specific status record; Java keeps cockpit modules inactive
for a bypass session. This is not merely hiding the map. Existing Audi navigation
policy is left in charge; the switch does not force dual navigation.

This removes our added stream negotiation from the new receiver process. It is
**not proof that a particular wireless adapter works**; that acceptance test must
be performed on the car with a fresh connection.

## Persistence and concurrency

`/mnt/persist/var/app/carplay_altscreen/preferences` is a strict, versioned
key/index file. Java and shell validate the same complete schema. Save uses a
flushed/synced temporary file and rename. Invalid settings select the safe disabled
path and report an error; preference reset can repair them without enabling the
master switch. The old `cluster_ui.url` is imported only when no new preference
file exists. The GEM layout picker updates the new setting once it exists.

Normal-MMI callbacks queue work rather than running filesystem/process work on the
HMI event thread. A preference worker remains responsive during the separate,
bounded action worker. Status tells requested versus current-session behavior and
reconnect requirements. Actions are an allowlist, never shell commands from labels.
The native menu does not expose install, restore, firmware updates or arbitrary
process control.

## Tests and limitations

`TOOLS_DIR=... STOCK_JAR=MU1316-P5145-stock.jar ./scripts/test_mmi_settings.sh`
checks every preference choice, malformed files, atomic-save failure, reset,
guarded factory patch equivalence, strict class verification, real native widget
constructors, OEM-row retention, nonblocking action handling/timeouts, live
lifecycle gates, and launcher preload behavior.

Existing Java/renderer tests cover overlay option messages, reconnect replay and
input behavior. `./scripts/test_route_labels.sh` renders real GLES previews of
large text, scrolling and hidden lanes. `test_altscreen_e2e.sh` checks matching
package installation, preservation, diagnostic actions and recovery.

There is no host emulator of the complete Audi native HMI graphics/input service.
The native page's final rendering, focus/Back behavior, driver-lock behavior,
persistence on QNX, and adapter compatibility remain **unverified in the car**.
Do not describe the host tests as full vehicle validation.
