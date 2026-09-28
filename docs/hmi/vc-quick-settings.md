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

## Unresolved input-ownership gate

The exact stock `org.dsi.ifc.keypanel.Constants` defines:

| Input | Code |
| --- | --- |
| Left roller press | 40 |
| Wheel cancel/Back | 41 |
| Left/right side-menu keys | 99 / 100 |
| Press / release | 1 / 0 |
| Double press / long press | 2 / 3 |
| Further long-press states | 4 / 5 |

Knowing these codes does not establish that every event reaches the head unit,
nor that the head unit can consume the cluster's local action. `DSIKeyPanel`
listeners observe events; they do not return a consumed flag. Existing raw
roller suppression addresses its later CarPlay `DDS_SELECT` copy, not arbitrary
stock VC behavior. A long-roller shortcut would also need to defer the existing
short-press Road/Trip action; simply reacting to state 3 would be insufficient.

Navigation BAP FctID 54 reports large-map and left/right drawer-open flags.
The stock handler stores/acknowledges these flags and updates map size; this
does not grant a custom panel keyboard ownership. FctID 44 exposes fixed
map-view/orientation options, not an arbitrary menu-entry payload.
The optional `IMMICombiScreenChangeManager` is not a dependable service on this
configuration. `ScreenModule` video/RGI flags alone are not proof of the active
VC tab or input focus.

Do not enable a live shortcut until a parked-car trace and observation establish:

- Which press, release, long-press and roller events actually arrive.
- Whether the native drawer or zoom UI acts before the head unit sees them.
- How to establish and revoke exclusive input ownership without a competing
  display-context writer, fake map option or global button suppression.
- Dismissal on Back, native drawer opening, tab/View changes, camera/parking
  takeover, disconnect, missing renderer acknowledgement and stale generations.
- No underlying map zoom, Road/Trip toggle or main-CarPlay selection while the
  panel owns input; centre-console controls and ordinary right-button behavior
  remain stock.

## Observe-only evidence

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
