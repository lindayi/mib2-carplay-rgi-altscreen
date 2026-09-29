# Carplay Altscreen: native MMI settings

Target: this owner's 2020 Q5, MHI2Q_US_AUG22_P5145 / MU1316 only.

> **Vehicle failure reported 2026-09-27:** the mascot release on the `c8ec3e7`
> card produced no mascot and `CONTROL_ERROR`; the owner reported the whole MMI
> freezing, including physical buttons. Boot logs show failed control-file
> publication and native HMI rendering attempted from the settings worker
> (`EGL_BAD_CONTEXT`). The returned `5a3a292` trial on 2026-09-28 matches the
> installed/card JAR pins and confirms stable menu use, working mascots and full
> diagnostic export. System-status overflow, stale Saved feedback, mascot size/
> occlusion and occasional Google Maps drift remain. The new corrections described
> below have not yet been deployed or confirmed in the car.
> Avoid the custom settings page on `c8ec3e7`; mascot Off alone does not correct
> its worker-thread repaint problem. Host results are not vehicle validation.

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

### Menu layers and navigation

The selectable list contains controls only: switches, exclusive choices,
submenus and actions. Page titles, help, results and status values are not
disabled or label-shaped menu options.

- Audi's existing title bar shows **Carplay Altscreen** and the current page.
  The original title models and menu bounds are restored on exit or failure.
- Switches use native checkboxes; exclusive choices use native radio buttons.
  Choice pages initially focus the selected value. Back restores the parent
  selection, and status notifications preserve focus when a switch changes.
- Explanations use Audi's native focused-item infoline. Reconnect instructions
  are help/notice text, not suffixes appended to selectable labels.
- A separate, non-menu native label below the list shows save/action feedback and
  any pending CarPlay reconnect. Successful saves name the setting/value and
  disappear after five seconds; failures and reconnect requirements do not expire.
  A long result directs the owner to
  **Status & diagnostics -> Last result**, where the complete message remains
  available, including failures.
- **System status** and **Last result** use a read-only text area above their
  Back/Previous page/Next page controls. Page numbers appear in the title bar.
  Previous/Next wrap around, and are disabled when there is only one page.
  The native text-node subtree is clipped to that area's bounds, including after
  paging/resizing; widget bounds alone do not constrain native glyph drawing.
- Confirmation warnings occupy the read-only area, separately from **Cancel**
  and the specifically named action. Initial focus is Cancel. A warning that
  cannot fit the native viewport is an extension error, not silently clipped.

Text and controls divide the existing stock content bounds using live native
font/row measurements. Status paging windows the native wrapped lines, rather
than truncating the underlying message. No new graphics context, custom font,
hard-coded screen overlay position or firmware-factory substitution is added.
The text layer is attached only in the queued HMI refresh, after stock tree
connection, and is hidden/reused across disconnects. Stock parent teardown caches
its child count, so removing a sibling inside that traversal is unsafe.
No widget-tree changes occur in paint or in the parent's disconnect traversal.
These main-MMI layers remain separate from the experimental VC panel described below.

## Settings and application

| Group | Controls | Application |
| --- | --- | --- |
| Main | Enabled | Off releases our cockpit modules immediately; the complete preload/extra-video bypass takes effect on the next CarPlay session. On after bypass also requires reconnect. |
| Main | CarPlay map + guidance / CarPlay map only / Audi map + guidance | Direct access beside Enabled. Display and RGI changes apply live when possible. The launcher excludes the AltScreen preload for a new Audi-map session; switching back then needs reconnect. |
| Main | Reapply cluster layout | Queues the current connection's selected URL again; no restart, reconnect or crop change. Requires active cluster video and a live receiver with a latched URL. Success means queued, not phone-confirmed or guaranteed recentering. |
| Phone map | Top / right / no ETA / original AltScreen | Reconnect; requests the iPhone layout, not direct marker positioning. |
| Guidance | Overlay distance, road/exit, lanes, arrow progress fill | Live. This controls our overlay, not the HUD's BAP data. |
| VC information bar | Default Road/exit or Trip summary; next-road/exit or current-road text; timed return or keep selection | Live, in the existing lower VC bar. No new overlay on the main MMI map or additional content in the maneuver box. |
| Appearance | Custom / Minimal / Standard / Large text presets; individual text size, scrolling and backing controls | Live. Road transport remains bounded to 32 UTF-8 bytes, with grapheme-safe ellipsis; scrolling is of that bounded label, not unlimited text. |
| Appearance | Map mascot: Off / Raccoon / Nian / Capybara / Lizard | Default Off. Live on the CarPlay cluster map only; not in the maneuver box, native information bar, main MMI or Audi map. |
| Controls | CarPlay wheel zoom and speed; touchpad DPAD bridge and sensitivity | Live. Disabling the touchpad bridge restores stock raw-pad forwarding; ordinary knob input remains stock. |
| Diagnostics | Read-only status and last result, summary export, confirmed full export, next-session verbosity | Status and export are immediate. Logging applies to the next session. Full exports contain private raw logs; they are not anonymized. |
| Recovery | Automatic mirror recovery; confirmed video-only restart | Live, but only for an installed, armed video-enabled session. No USB, dio_manager or MMI restart command is exposed. |
| Reset | Reset display/control preferences | Confirmation required. Keeps master On/Off, Audi settings, phone pairing and installation. |

No preference invokes a full MMI reboot, disconnects the phone automatically, or
uninstalls the patch. The initial patch installation still needs the normal
installation reboot.

The phone-map URL is latched per receiver connection and reapplied on later
route transitions and settled View-size changes, including in map-only mode. A live route change or video
restart does not silently apply a newly saved layout; reconnect CarPlay for that
change. This addresses missing layout requests, not direct vehicle-marker
positioning. Google Maps alignment still needs vehicle confirmation; see
[layout request lifetime](../input/steering-wheel.md#google-maps-alignment-route-lifetime-correction-vehicle-confirmation-pending).

### Information bar and appearance

OK toggles Road and Trip. By default a manual override returns to the configured
default page 20 seconds after successful publication. **Keep until OK or route
ends** also preserves the selection across View changes. A route/session boundary
resets to the configured default; changing that default applies it immediately.
The current-road option affects only this lower bar: the maneuver footer still
uses next-road/exit information. No now-playing page is added.

| Preset | Distance | Road | Lanes | Progress | Text | Scrolling | Backing |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Custom | Stored individual choices | Stored | Stored | Stored | Stored | Stored | Stored |
| Minimal | On | Off | On | Off | Standard | Off | Reduced |
| Standard | On | On | On | On | Standard | Off | Solid |
| Large text | On | On | On | On | Large | Off | Solid |

Selecting a preset retains the stored Custom configuration. Selecting Custom
restores it. Editing an individual appearance/guidance control while a preset is
active copies its effective values into a new Custom configuration, then changes
that control. Presets do not change master enable, map layout, information-bar
selection, mascot, input or recovery.

### Optional map mascots

**Overlay appearance -> Map mascot** selects Off, Raccoon, Nian, Capybara or Lizard.
The VC panel's **Map mascot** page offers the same choices. A transparent,
80-video-pixel-high animation (twice the former width and height) travels across
the lower navigation canvas. Following owner feedback that the enlarged mascot
sat too high, its bottom clearance is 70% of the previous value:
`0.70 * (ceil(videoHeight * 0.20) + 4)`, or 66.5 rather than 95 video pixels in
the logged 1440x455 mirror viewport. Its size is unchanged.
This is a trial clearance, not a measured boundary of Audi's full-width street/
Trip bar. That bar is composed in the VC after the video and cannot be outranked
by changing draw order inside the mirror. The map crop and Audi bar are untouched.
Placement in both View sizes still needs vehicle confirmation. Mascots travel
back and forth at 48 video pixels/second, turning when their canvas reaches either
edge of the current video viewport. The raccoon starts at the left moving right;
Nian, Capybara and Lizard start at the right moving left. On the return leg the
image flips horizontally, without reversing frame order or changing animation
timing. Travel speed is not individually stride-matched. If the sprite is as wide
as or wider than the viewport, it stays centered and clipped rather than moving.
The white
exterior of the raccoon and capybara references is removed, without erasing
enclosed white details such as the raccoon's face.

Each animation is cropped once to the union of its frames' visible bounds before
scaling. External GIF padding therefore does not change its on-screen ground
line. Do not bottom-align or crop every frame separately: that would remove
intentional hopping and change the animation. At 80-video-pixel canvas height,
the approximate gap below pixels with at least 50% opacity varies by 0..28 pixels
for the bouncing raccoon, 0..2 for Nian, 0..12 for Capybara and 0..2 for Lizard.
These are host asset measurements, not cockpit-bar measurements. They add to the
66.5-pixel base clearance at 455 pixels high; do not subtract the source GIF's
raw transparent margin again when applying the owner's 30% clearance reduction.

`libcarplay_mascot.so` is preloaded only into the existing mirror sidecar, never
the receiver or HMI. It composites immediately before that mirror's EGL swap;
there is no new displayable, context writer, independent redraw timer or swap.
Animation therefore pauses if real video presentation pauses; it cannot conceal
a decoder stall by advancing mirror telemetry. Off performs no mascot GL work.
Graphics state, viewport bounds and destination alpha are preserved.

The settings worker publishes a PID-bound, four-second RAM-file lease at
`/ramdisk/carplay_mascot.control`; native status is
`/ramdisk/carplay_mascot.status`. `/ramdisk` is the existing QNX4 RAM filesystem,
not shared-memory `/tmp`, where rename-based publication failed on the vehicle.
There is no fallback to persistent flash or delete-before-rename publication.
The control format is
(`MASCOT2 <selection> <mirror-pid> <expiry-ms>`). The native worker requires that
lease and the mirror's video-ready marker. Old-process, expired, master-disabled,
bypass and Audi-map sessions cannot enable it. Normal-MMI Status shows the
reported renderer state, not proof of visible vehicle pixels. Asset/control/GL
failures are logged; asset failure disables only the mascot for that mirror
process, and GL failures retry at most once every five seconds. A stalled control
worker loses its render-side lease after one second. Native status-write errors
include operation, errno and path, with repeated identical errors limited to once
per 30 seconds. `CONTROL_ERROR` and `STATUS_ERROR` distinguish Java publication
from status-reading failures.

GIF decoding and background removal happen on the host, not the HU. Supply the
owner's local GIFs to `tools/build_mascot_assets.py` (Python/Pillow):

```sh
python3 tools/build_mascot_assets.py --raccoon <local-raccoon.gif> \
    --nian <local-nian.gif> --capybara <local-capybara.gif> --lizard <local-lizard.gif> \
    --output build/mascot-assets/mascots.rgba
```

The native build produces the library; SD staging requires both it and the
generated four-animation atlas; staging rejects an older atlas.
Existing IDs stay Off=0, Raccoon=1, Nian=2 and Capybara=3; Lizard appends ID 4.
The preference format remains version 3, and existing selections are preserved.
Older builds may not support newer mascot values: select Off before an intentional
downgrade rather than relying on an older build's invalid-preference safe path.
Source GIFs, derived artwork and SHA256 provenance stay in
private inputs/ignored build output, not Git. No redistribution license for the
reference artwork is asserted. The binary-only mirror is not rebuilt or
represented as fully source-audited.

The owner's blue-background lizard video is converted locally using FFmpeg and
Pillow; neither is needed in the vehicle. Its selected walking cycle is source
frames 66..85 at 30 fps (about 2.20..2.87 seconds). GIF frame delays alternate
30/40 ms to preserve the cycle's timing within GIF's 10 ms precision: 20 frames,
670 ms total. The blue background is keyed out before a fixed union crop, keeping
the eyes and body opaque without moving the crop each frame.

```sh
python3 tools/build_mascot_video.py --input <local-lizard.mp4> \
    --output <local-lizard.gif> --start-frame 66 --frames 20 --key-color 0044b8
```

This converter is for constant-frame-rate, solid-key-background clips, not an
automatic subject-segmentation service for arbitrary footage. It rejects invalid
frame ranges, unsupported timing and a key that leaves the frame borders opaque.
The mascot suite includes synthetic-video checks for transparency, white details,
fixed cropping, frame selection, timing and preservation on failure. The generated
GIF and its hash/crop/timing report remain private.

Arrival time uses a valid phone-supplied destination UTC offset when available,
otherwise the existing HU-local conversion. It appears in Audi's existing arrival
clock and the fullscreen Trip summary, **not** as new maneuver-box text. The Trip
clock adds `dest` when it differs from HU-local time; Audi's numeric clock cannot
carry that label. Duration remains UTC-based and the HU's 12/24-hour preference
still applies. This does not change Google Maps' own video-rendered ETA. Actual
app transmission and cross-zone behavior require vehicle confirmation; see
[the transport evidence and limits](../rgd/rgd-tlv.md#destination-time-zone-offset).

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
key/index file. Java and shell validate the same complete schema. New saves use
`format=3` (21 settings). Complete `format=1` files (16 settings) are accepted with
Road/exit, next-road text, 20-second return, Custom and mascot Off defaults.
Complete `format=2` files (20 settings) retain their choices and default mascot Off. Reads do not
rewrite old files; the next save migrates them. Incomplete or mixed-version files
are rejected rather than filled with silent defaults. Save uses a
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

Worker notifications post coalesced stock `RunnableEvent`s to Audi's HMI event
dispatcher. Widget changes and synchronous repaint run only on that thread;
disconnect invalidates queued updates. Row replacement happens before repaint,
never inside `managePaint`. Error cleanup is deferred too, so a failing row's
connection callback cannot re-enter an unfinished rebuild.

Owner, receiver-session, renderer-PID, mirror-health and boot-token snapshots
also use `/ramdisk`. Logs/readiness markers stay in `/tmp`; Java and native log
rotation copy bounded archive tails and truncate rather than renaming shared
memory files. Failed menu actions retain an 8-KiB output tail at error severity
and in `/tmp/carplay_menu_action.failure.log`. Confirmed full exports include
that private failure detail and rotated Java/hook logs; summary exports do not.

## Tests and limitations

`TOOLS_DIR=... STOCK_JAR=MU1316-P5145-stock.jar ./scripts/test_mmi_settings.sh`
checks every preference choice, v1/v2 migration/v3 strictness, preset preservation,
malformed files, atomic-save failure, reset,
guarded factory patch equivalence, strict class verification, real native widget
constructors, OEM-row retention, nonblocking action handling/timeouts, live
lifecycle gates, and launcher preload behavior. Regressions exercise actual
stock `RunnableEvent` dispatch, worker-thread isolation, coalescing, close and
reconnect generations, queue rejection, and prohibit tree updates inside paint.
A filesystem fault fixture rejects `/tmp` rename while allowing ordinary
read/write. Java logger rotation and production shell snapshot paths are tested
against that contract; nonzero/timeout action output must remain diagnosable.

`NativeMenuPresentationTest` checks action-only rows, native radio selection
semantics, initial/restored focus, separate text/control bounds, all pages of
long status messages, confirmation-fit rejection, OEM title/bounds restoration
and a stable parent child list across repeated disconnect/reconnect cycles.
It also checks the native clip rectangle/inheritance and expired-notice visibility;
runtime checks cover fresh/repeated saves, expiry notification, retained results
and non-expiring failures.
Its font metrics are simulated; it does not emulate Audi's graphics service or
establish final vehicle readability. The production renderer uses native fonts
and wrapping. Deferred fallback cleanup also has an HMI-thread regression.

Existing Java/renderer tests cover overlay option messages, reconnect replay and
input behavior. `./scripts/test_route_labels.sh` renders real GLES previews of
large text, scrolling, hidden lanes and the three named presets. Route tests cover
both default pages, pinned selection, live changes, destination-zone arithmetic
and synthetic native-TLV-to-Java/BAP transmission. `test_altscreen_e2e.sh` checks matching
package installation, preservation, diagnostic actions and recovery.

`./scripts/test_mascots.sh` uses the local generated atlas and host EGL/GLES2.
It checks bounded parsing, real rendered pixels, viewport clipping, destination
alpha, exact doubled dimensions/raised bounds using opaque fixtures, caller
graphics state, frame/wrap timing, injected graphics failures,
actual EGL interposition, missing assets, stale PID/expiry/readiness controls,
context recreation, swap-failure forwarding and the absence of extra swaps.
It also tests the production `/ramdisk` paths with `/tmp` rename unavailable,
precise/rate-limited status-write failures, and a blocked control reader whose
render lease must expire. `run_tests.sh` checks native bounded log rotation when
rename returns `ENOSYS`.
The generated PNGs are host composites, not cockpit photographs. The `5a3a292`
trial confirms animation on the HU; the enlarged/raised version's placement,
readability and performance remain unverified in the vehicle.

There is no host emulator of the complete Audi native HMI graphics/input service.
The owner confirmed stable basic menu use in `5a3a292`, while reporting status
overflow. The new clipping/notice changes, driver-lock behavior, persistence across
reboots and adapter compatibility are not established by that report.
Do not describe the host tests as full vehicle validation.

## Experimental Virtual Cockpit access

The [VC quick-settings panel](vc-quick-settings.md) now has mirror rendering,
wheel routing and shared preference saves. Long-press is enabled by default in
the new source, with large-map View and a map-zoom detent required to establish
entry eligibility. This is a custom panel, not an extra row inside Audi's drawer.
It is not yet vehicle-confirmed or present on the tested `5a3a292` card. The
Navigation Settings entry remains available, including when no panel surface exists.
