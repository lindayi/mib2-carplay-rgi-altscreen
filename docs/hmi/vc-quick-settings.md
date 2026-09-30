# Virtual Cockpit quick settings

Target: the owner's 2020 Q5, MHI2Q_US_AUG22_P5145 / MU1316.

**Experimental runtime integration; partial owner confirmation only.** The panel
uses the mirror compositor, real settings worker and steering-wheel handlers.
The owner requested long-press availability **by default**, not a per-session
main-MMI opt-in. This does not add a row inside Audi's native right drawer.
After the `3c3de7e` card preparation, the owner reported that the panel mostly
works, but its top-right placement was clipped/covered, scrolling felt reversed,
and it closed during active use. No diagnostic export or installed build-ID
readback accompanied that report. The centering, direction and acknowledgement
corrections were included in the subsequent `aac9ddb` card trial. On 2026-09-29
the owner reported that it mostly works well and opens easily after a View
round trip. All options were visible, but Audi elements covered the title and
bottom; starting already in large-map View prevented entry even after a detent
visibly zoomed the map. No diagnostics or installed build-ID readback accompanied
this report. The shorter panel and startup replay correction below need a retest.

## Controls and eligibility

With CarPlay cluster video active, choose large-map View and turn the left
roller one detent, then hold it to open **Carplay Altscreen**. That initial
detent still zooms normally; it establishes map-input evidence. It is required
again after a session change or native drawer/tab takeover. This conservative
guard exists because the tested VC's drawer-open flags remained false.
The accepted stock presentation Status, including its initial `updateAll()`
replay, seeds panel View eligibility; a View toggle is not required. Unknown or
small-map state still cannot open the panel, and the zoom detent remains required.

| Input | Panel behavior |
| --- | --- |
| Long roller hold | Open, or close an already-open panel; the opening release does not select |
| Rotation | Move once per BAP scale step (positive down, negative up); no project CarPlay/native-map zoom while input is owned |
| Short roller press/release | Select the displayed row; outside the panel, Road/Trip toggles on release rather than initial press |
| Back | Return to the parent selection, then close at the root |
| Either native drawer button, tab or View change | Dismiss the panel and retain the stock action |

Only a successfully presented, matching panel snapshot grants input routing.
Selection waits for the displayed revision; turning then pressing faster than
the new row is acknowledged does not select an unseen row. The panel closes
on parking/camera intent, video/session loss, Master Off, incompatible display
mode, expired presentation acknowledgement or 30 seconds without menu input.
An unacknowledged opening times out after 1.5 seconds. It does not reopen itself.

Small-map View and Audi-map-only mode are not supported panel surfaces in this
first integration. Map-only CarPlay mode is supported without requiring active
route guidance. Turning Enabled Off or selecting Audi-map mode can remove the
panel's own video surface; use the main-MMI menu to restore those settings.
No preference reboots MMI or disconnects the phone automatically.

## Layout and preferences

The centered panel uses a charcoal background, light text, restrained red
selection strip, chevrons, checkboxes and radio choices. It is Audi-inspired,
not pixel-identical OEM rendering. Typography reuses the embedded DejaVu-derived
font; retain `maneuver_render/LICENSE.DEJAVU` with distributions.

The root contains Enabled, Display mode, Map layout, Map mascot and More settings.
More provides Guidance, Appearance, Information bar, Controls, Reapply map
layout and Map cards. Map cards independently toggles the passive Now Playing
and estimated Trip cards, both default Off; it does not change input ownership.
They share a fixed curved left pocket: one section expands inside it when the
other is unavailable or Off, and both Off removes it. Long media title/artist
lines scroll independently. The pocket's intended Audi-dial masking still needs
parked calibration; switching View never moves it inward.
These use the same `Setting` definitions, `Preferences` snapshots and
asynchronous `SettingsRuntime` save/action path as main MMI, not a second settings
file. Checked values reflect persisted snapshots, not optimistic local toggles.
Save errors, transient success notices and reconnect requirements occupy the
non-selectable hint area. Main-MMI Last result retains complete details.

Titles, hints and the fixed control footer are not menu rows. Back restores
parent focus; choices start at the saved value. There are at most six selectable
rows per page, with bounded ASCII labels and ellipsis for long display text.
Lists larger than six choices use four values per page plus Previous/Next links.
These links never save a preference. Mascot choices have a right-hand animated
preview of the focused value, without changing the saved/map mascot. Off and
pagination links show explanatory text, not an animation.
Export, install, reset and receiver-restart actions are not included.

`vc_menu/panel.c` paints a 420x288 panel, reduced from 420x348 after the latest
vehicle feedback. Rows use 32 rather than 38 pixels of vertical spacing; option
font sizes and width are unchanged. All six rows, title, status hint and control
footer remain. The runtime uploads a padded 452x320
image at the center of the mirror viewport, preserving caller GL state,
destination alpha and pixels outside the panel. It draws after the mascot and
before the mirror's existing EGL swap; there is no extra displayable, context
writer or swap. In the logged 1440x455 viewport the panel occupies x=510..929,
with 84 pixels above and 83 below (30 more on each side). This is a map-video canvas, not the entire
cockpit. Audi's VC-local layers may still occlude it; final placement needs a
parked-car check. Do not squeeze it into the 328x181 maneuver box.

Only the mascot chooser expands horizontally to **640x288** (padded 672x320):
at 1440x455 its visible bounds are x=400..1039, with the same vertical clearance.
The original 420-pixel list and its fonts stay unchanged. The worker composites
the preview in a 180x100 region to the right, at most twice atlas size, sampling
original frame timing on its existing 100 ms poll. This is a bounded 10 Hz menu
preview, not a change to the map animation's frame timing. CPU painting and atlas
loading remain on the single worker, outside the GL mutex. Immutable decoded
artwork is shared with the map mascot. Each changed preview image gets a fresh,
validated texture without recompiling shaders or changing the Java revision.
Only actual successful swaps renew presentation; animation alone grants no input
ownership. Host pixels and timing do not establish Audi-layer clearance.

## Runtime and acknowledgement contract

`VcPanel.java` owns navigation and gesture state. Raw key callbacks only publish
intent; its bounded worker exchanges atomic RAM files. The mirror's existing
overlay worker parses/rasterizes snapshots; only its EGL thread touches GL.
The native metadata lock covers no file I/O or CPU text painting.

- Control: `/ramdisk/carplay_vc_panel.control`.
- Header: `VCPANEL1 pid epoch revision expires_ms connection count focus`.
- Open snapshots then contain title, hint and `count` rows, each
  `kind checked<TAB>label<TAB>value`. Closed snapshots have count/focus zero.
- A mascot chooser appends `PREVIEW n`: 1 means Off, 2..17 mean mascot IDs 1..16,
  and 18 means a pagination link. Zero, out-of-range values and additional
  trailing content are invalid; ordinary pages omit the extension.
- Status: `/ramdisk/carplay_vc_panel.status`, one line
  `VCPANEL1 pid epoch revision expires_ms connection state`.
  State is 1 for presented, 0 for withdrawn/pending, -1 for a renderer error.
- Control expires after 600 ms. Native rendering additionally requires an
  advancing control worker (at most 400 ms without renewal) and video readiness.
- Presentation expires after 300 ms and advances only after successful real
  `eglSwapBuffers`, not worker polling. Java requires matching PID, epoch,
  connection and revision bounds; selection requires the exact shown revision.

No input is captured during opening without an acknowledgement. Stalled video,
failed swaps, stale PID/session files, stopped workers or missing runtime support
withdraw ownership. Context recreation rebuilds GL objects. Fresh texture
allocation is validated before a new UI revision can be acknowledged; an upload
failure must not report old pixels as the new selection.

Two reproduced acknowledgement races are corrected without increasing leases:
callbacks reject input on an expired cached lease but let the worker read fresh
status before deciding to close; and a successful swap may acknowledge its
actually displayed older revision when a newer request arrived during that swap.
Selection still requires the exact current revision. True expiry, renderer
withdrawal, errors and lifecycle changes still close the panel. These races are
host-proven defects, not a confirmed diagnosis of every vehicle closure.

`libcarplay_mascot.so` retains its compatibility filename but now contains both
overlays. Mascot Off does not disable the panel. Conversely, a missing mascot
atlas does not prevent panel rendering. Keep the library preload restricted to
the mirror sidecar, never the main receiver or HMI.

## Input routing and native coexistence

Road/Trip and CarPlay zoom are project-controlled, not unavoidable VC-local
actions. `SteeringWheelInputModule` now arbitrates raw key 40 press/hold/release.
Ordinary completed short gestures still call `ScreenModule` and `RouteGuidance`.
Wheel-origin normalized short/long Select copies are filtered separately from
the centre-console knob. An owned panel's Back gesture also filters only its
wheel-origin main-CarPlay copy, including release after closing the panel.
Console events clear stale Back markers; ordinary closed-panel Back is unchanged.

`ScreenCombiBAPListener.setMapScale` uses one movement source: BAP steps. Raw
encoder events remain trace-only; they describe the same detents with opposite
signs in the returned capture. Menu movement does not use the zoom-speed setting.
While owned, the override calls protected stock `updateMapScale()` to report the
unchanged scale, skipping both the CarPlay command and stock zoom mutation.
Otherwise both original zoom paths run unchanged.

Exact MU1316 bytecode confirms that stock `setMapScale` normally changes map
context value 400476, may change auto-zoom, and then calls that status method.
No global keyboard grab or arbitrary native drawer-entry API is claimed.
Raw right/left/Back/tab events still reach Audi. Native coexistence, particularly
Back outside an OEM drawer, must be checked on the car.

Panel presentation state is observed by `GatedCombiService.updateMapPresentation`
before forwarding the unchanged stock Status. MU1316's private
`CombiBAPListener.updateMapPresentation()` is also called by startup `updateAll()`,
bypassing the incoming `setMapPresentation` override. Observing only requests
left the panel's initial `largeMap` false until a View change. Capture the replay
even before the wheel module starts; do not initialize eligibility to true or
infer large-map state from a zoom gesture. Duplicate identical replays preserve
detent evidence, while size changes/drawer flags invalidate it. This source gap
is reproduced on the host; without vehicle logs it is not a proven explanation
of every startup failure. No new layout request, display-context writer or
phone reconnect is introduced.

## Evidence and checks

The returned `5a3a292` trial proves raw press/release/long states 3/4, encoder,
right-button 100 and Back 41 delivery. The owner saw Road/Trip toggling, map zoom
and native drawer operation, with no main-CarPlay reaction. All logged Fct54
drawer flags stayed false despite the visible drawer. These observations
motivated the local router and map-scale entry guard; they do not prove final
menu usability or complete input ownership.

Host coverage:

- `VcPanelTest`: gesture arbitration, map/size eligibility, exact-stock scale
  reply without a MapManager, single movement source, revision/lease checks,
  focus restoration, session/parking dismissal and shared settings persistence;
  private stock startup presentation replay before wheel start, duplicate replay,
  small-map rejection and drawer invalidation without a synthetic View toggle.
- `VcPanelWorkerTest`: actual Java worker/atomic files with explicitly simulated
  renderer acknowledgements, fresh-status/cached-expiry races, stale epochs/PIDs,
  timeout, malformed status and stop.
- `vc_panel_protocol_test.c`: bounded schema, malformed/truncated input, lease/PID
  gates and a snapshot generated by the real Java serializer.
- `test_vc_panel.sh`: sanitizer-backed model/rendering/parser checks and previews.
- `test_mascots.sh`: actual host EGL panel pixels and alpha at 1440x455, successful
  swap acknowledgements, readiness loss/recovery, revisions, stalls, expiry,
  repeated revision changes during real swaps, small-surface rejection, texture
  failure and context recreation.
- Package fixtures retain export-only immutability and remove panel RAM state
  during restore. Summary export includes only selected status fields.

The actual GL composite is `build/mascot-tests/vc-panel-live.png`. Earlier
`build/vc-panel-previews/*.png` remain schematic UI-only previews. Neither is a
cockpit photo. Host tests cannot establish native drawer coexistence, physical
occlusion, QNX graphics responsiveness or in-car legibility.

Verbose `[VcInput]` traces remain available; `[VcPanel]` records opening,
presentation, selection and reason-coded closure. Failures are explicit and the main-MMI
System status includes panel state. Keep diagnostic exports private.
