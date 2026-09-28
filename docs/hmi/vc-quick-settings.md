# Virtual Cockpit quick-settings prototype

Target: the owner's 2020 Q5, MHI2Q_US_AUG22_P5145 / MU1316.

**This is an isolated UI prototype, not an enabled vehicle menu.** The native
renderer and navigation model compile for QNX, but no vehicle process links them,
no new wheel shortcut is installed, and they cannot change saved preferences.
The main MMI and existing VC drawer retain their current behavior.

## Visual and interaction design

The proposed panel sits on the right of the VC map area. It uses a charcoal
background, light text, a restrained red selection strip, familiar submenu
chevrons, checkboxes for switches and radio marks for exclusive choices.
It is Audi-inspired, not a claim of pixel-identical OEM rendering. Typography
reuses the project's embedded DejaVu-derived font; retain
`maneuver_render/LICENSE.DEJAVU` with distributions.

The first page contains Enabled, Display mode, Map layout, Map mascot and More
settings. Titles and explanatory/status text are separate from selectable rows.
Focus never lands on a heading, help text or a fake Back option. Back restores
the parent page's previous focus, then dismisses the root panel. The UI model
supports selection and in-memory toggle/choice changes only.

`vc_menu/panel.c` paints a bounded 420x348 panel into an RGBA surface while
preserving destination alpha and all pixels outside the panel. It refuses a
surface too small to maintain legible text; it does not squeeze a settings page
into the 328x181 maneuver box. The eventual vehicle composition must use measured
map-plane geometry, not assume that host preview coordinates fit every VC stage.

Run the host prototype:

```sh
bash scripts/test_vc_panel.sh
```

It requires a host C compiler with ASan/UBSan and Python/Pillow. In the existing
Linux test image put `/usr/bin` before the QNX toolchain in `PATH`.
It produces `build/vc-panel-previews/root.png`, `mascot.png` and `closed.png`.
The background is a schematic fixture, not captured CarPlay video or a cockpit
photograph. The regular native build also produces
`build/libvc_menu_prototype.a`, deliberately not linked or deployed.

## Project-owned actions and remaining input gate

The observed Road/Trip toggle and CarPlay zoom are implemented by this project,
not proof of unavoidable VC-local actions. Source and exact MU1316 bytecode
inspection after the owner's clarification establishes these control points:

| Action | Current path | Candidate menu routing |
| --- | --- | --- |
| Road/Trip toggle | `SteeringWheelInputModule.RawKeyListener.updateKey2` handles raw roller state 1, then `ScreenModule.onSteeringWheelOkPressed` calls `RouteGuidance.requestInfoModeToggle` | Defer a short action until release; a recognized long hold can open the panel without first toggling Road/Trip. While the panel is active, route a new short press to selection instead. |
| CarPlay map zoom | `ScreenCombiBAPListener.setMapScale` -> `AltScreenCluster.onMapScaleSteps` -> `CMD_ALT_ZOOM` -> native `alt_on_zoom` -> AirPlay `changeMapZoomLevel` | Route the unscaled BAP steps to menu navigation instead of sending the zoom command. The existing zoom preference already gates the CarPlay command. |
| Audi map zoom behind the video | The same override calls stock `super.setMapScale(steps)` | Stock increments map-context value 400476 and may change auto-zoom, then calls protected `updateMapScale()`. An owned-menu branch can call the latter directly to report the unchanged scale without applying those changes. Preserve its initialized-scale/status behavior; do not simply discard the BAP request. |
| Main-CarPlay Select copy | `consumeCollapsedSelect` filters raw-wheel-origin pressed/released `DDS_SELECT` events in `CarplayDSILifecycleController` | Preserve wheel/centre-knob separation. Long/repeat states require their own review; the existing filter is not a general input grab. |

Both short and long presses currently toggle Road/Trip because the action runs
at state 1, before state 3 identifies a long hold. Long states are traced but
do not invoke a separate action. This behavior does not establish that the VC
itself toggled the information page.

A global exclusive keyboard API is therefore **not a prerequisite for gating
these project-controlled actions**. The candidate is one local router with
explicit closed/opening/active/closing state and a renderer-acknowledged,
generation-bound ownership lease. Use one source for menu movement: raw encoder
and BAP scale describe the same detents, and the returned trace has opposite
signs between them. Do not apply both or apply the user's map-zoom speed multiplier
to menu navigation.

This is an implementation approach, not an enabled feature or verified native
drawer integration. It still needs activation eligibility, press/release/hold
arbitration, loss-of-renderer recovery and dismissal tests. In particular:

- Existing map-tab fallback checks connection/RGI state, not authoritative VC
  focus; map-only sessions must not inherit the active-RGI requirement blindly.
- Right/left drawer, Back and tab keys can request local dismissal without
  suppressing Audi's actions, but their observation does not detect an already-open
  native drawer or prove that no other cluster-local selection occurs.
- Preserve native map-scale status replies, normal closed-menu behavior and the
  centre-console knob. A delayed callback from an old connection or closed panel
  must not reactivate menu ownership.
- A long-roller custom panel is distinct from inserting a new entry inside
  Audi's existing right drawer. No arbitrary OEM drawer-entry mechanism has been
  demonstrated here.

The next step can be a disabled-by-default local routing/renderer integration
with host state-machine tests, followed by a bounded parked-car interaction test.
Another identical event-delivery trace, by itself, is not the missing mechanism.

The exact stock `org.dsi.ifc.keypanel.Constants` defines:

| Input | Code |
| --- | --- |
| Left roller press | 40 |
| Wheel cancel/Back | 41 |
| Left/right side-menu keys | 99 / 100 |
| Press / release | 1 / 0 |
| Double press / long press | 2 / 3 |
| Further long-press states | 4 / 5 |

The returned trace establishes delivery of the tested keys, but not consumption
of arbitrary cluster-local actions. `DSIKeyPanel`
listeners observe events; they do not return a consumed flag. Existing raw
roller suppression addresses its later CarPlay `DDS_SELECT` copy, not arbitrary
stock VC behavior.

Navigation BAP FctID 54 reports large-map and left/right drawer-open flags.
The stock handler stores/acknowledges these flags and updates map size; this
does not grant a custom panel keyboard ownership. FctID 44 exposes fixed
map-view/orientation options, not an arbitrary menu-entry payload.
The optional `IMMICombiScreenChangeManager` is not a dependable service on this
configuration. `ScreenModule` video/RGI flags alone are not proof of the active
VC tab or input focus.

Before declaring a live shortcut usable, host integration and parked-car
observation must establish:

- Correct arbitration of the observed press, release, long-press and roller events.
- Whether the native drawer or zoom UI acts before the head unit sees them.
- How to establish and revoke scoped menu input ownership without a competing
  display-context writer, fake map option or global button suppression.
- Dismissal on Back, native drawer opening, tab/View changes, camera/parking
  takeover, disconnect, missing renderer acknowledgement and stale generations.
- No underlying map zoom, Road/Trip toggle or main-CarPlay selection while the
  panel owns input; centre-console controls and ordinary right-button behavior
  remain stock.

## Observe-only evidence

The returned `5a3a292` trial on 2026-09-28 proves raw roller press/release and
long states 3/4, encoder steps, right-menu key 100 and Back key 41 reach the HU.
The owner reports short/long presses both changing the project-controlled Road/Trip bar,
rotation zooming the map, and right/Back controlling Audi's drawer; main CarPlay
did not react. Crucially, every logged Fct54 left/right-menu flag stayed false
despite that visible drawer. The trace establishes delivery, **not consumption,
exclusive ownership or a reliable native-drawer dismissal signal**. The live
panel remains disabled. The controllable HU actions above narrow this gate; the
false flags still prevent treating BAP drawer status as an authoritative focus signal.

With existing verbose diagnostics enabled, Java now logs `[VcInput]` records:

- `RAW_KEY`: only relevant MFW navigation keys, including long/repeat states.
- `RAW_ENCODER`: the left-wheel encoder when optional notification is available.
- `BAP_MAP_SCALE` and `BAP_PRESENTATION`: stock navigation callback delivery.
- `CARPLAY_KEY`: relevant normalized keys reaching the existing CarPlay path,
  including the already-existing MFW select suppression.

The new trace does not consume any additional keys, change stock BAP handling,
claim focus or render the prototype. Encoder-trace registration failure is
reported without disabling the existing key listener. Logging remains on the
existing asynchronous bounded writer and is quiet at normal verbosity.

Capture while parked, noting visible behavior as well as the trace, with main
MMI CarPlay left open. Include short/long roller press, a few detents, Back,
ordinary right drawer open/close, and a VC tab change. Video/RGI fields are
explicitly marked `input_owner=UNVERIFIED`. Full exports remain private and are
not uploaded automatically. An input trace alone is not proof of suppression.
