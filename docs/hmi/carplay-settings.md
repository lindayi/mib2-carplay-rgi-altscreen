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
- Normal pages have no selectable Back row: use the physical Back button,
  including to leave the root for Audi's Navigation Settings. Confirmation pages
  retain an explicit Cancel row and initially focus it.
- Every option has an explanation in Audi's native focused-item infoline. Reconnect instructions
  are help/notice text, not suffixes appended to selectable labels.
- A separate, non-menu native label below the list shows save/action feedback and
  any pending CarPlay reconnect. Successful saves name the setting/value and
  disappear after five seconds; failures and reconnect requirements do not expire.
  A long result directs the owner to
  **Status & diagnostics -> Last result**, where the complete message remains
  available, including failures.
- **System status** and **Last result** use a read-only text area above their
  Previous page/Next page controls. Physical Back returns. Page numbers appear in the title bar.
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
| Map cards | Now Playing; Trip progress; experimental speed/limit badge | Independent, default Off, live on CarPlay video. Music/Trip stay fixed left; the speed badge uses a View-dependent top-right inset. |
| Guidance | Overlay distance, road/exit, lanes, arrow progress fill | Live. This controls our overlay, not the HUD's BAP data. |
| VC information bar | Default Road/exit or Trip summary; next-road/exit or current-road text; timed return or keep selection | Live, in the existing lower VC bar. No new overlay on the main MMI map or additional content in the maneuver box. |
| Appearance | Custom / Minimal / Standard / Large text presets; individual text size, scrolling and backing controls | Live. Road transport remains bounded to 32 UTF-8 bytes, with grapheme-safe ellipsis; scrolling is of that bounded label, not unlimited text. |
| Appearance | Map mascot: Off / local pack choices (up to 16) | Default Off. Live on the CarPlay cluster map only; animated focused previews in the VC menu, not main MMI. |
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

### Optional map cards

**Carplay Altscreen -> Map cards** offers independent **Now Playing** and **Trip
progress** switches. In the VC panel use **More settings -> Map cards**. Both
default Off and apply live, without reconnecting or rebooting. They occupy the
left of displayable 3's existing CarPlay video, not the small right-side maneuver
box, the lower information bar, or a new Audi displayable.

Now Playing shows the phone's supplied title, artist and known playback state,
with artwork when a fresh cover event follows the current track. A track change
clears the preceding cover; missing artwork does not hide the text. Paused media
remains labelled Paused. Missing media metadata hides its card. This is not a
new audio source, playback control, or replacement for Audi's Media screen.
The additive `png_crc` cover event field identifies the actual generated PNG
bytes; the older `crc` identifies the original input image and must not be used
to validate that PNG. Missing/mismatched PNG identity omits artwork. Cover events
have no track ID, and a phone that does not resend art after a track change may
leave the card text-only; exact title/art association is not guaranteed.
Text is bounded to 128 UTF-8 bytes per field, with grapheme-safe truncation.
Overflowing title and artist lines scroll independently at 20 video pixels/second:
pause 1.8 seconds at the start, reveal the end, pause there 1.8 seconds, then repeat.
Short text stays stationary. Scrolling reveals only the bounded transported text,
not an unlimited title. Each line is clipped to its own rectangle above the black
lower base. An observed title/artist/album/duration change resets both lines; playback,
artwork and Trip updates do not. Re-enabling media or recovering a lost native
worker/video lease starts the lines again. Metadata has no true track identifier:
indistinguishable repeated tracks cannot reliably be detected.
The embedded font covers Latin/Greek/Cyrillic and punctuation;
unsupported fields are omitted with a diagnostic, not transliterated.

Trip shows ETA, remaining time and distance, using the existing HU unit/time and
destination-zone formatting. Unknown values are omitted. **Estimated progress**
is `1 - remaining distance / first observed positive remaining distance`, clamped
to 0..100%. It is not the fraction of the original journey completed, GPS history,
or traffic delay. Joining mid-route starts a new baseline; additional distance
can move the bar backward. Unknown distance or a zero initial distance produces
no bar. Receiver reconnect, a new route generation, changed destination/source
and route end reset it. Toggling a card, changing View, or visiting Audi main MMI
does not reset the baseline. The card disappears outside active route states.
The Trip heading is separate from the smaller, muted **Estimated progress**
caption directly above the bar. ETA, remaining time, caption and bar share a left
edge; distance is right-aligned on the normal compact row. Exceptionally long
values use two smaller rows above the caption. Unknown progress hides both its
caption and bar.

Cards share one fixed RGI-inspired frame. At 1440x455 its bounds are
`(119,70), 230x256`, measured from the top-left. The extra 20 pixels of width
extend left, keeping the preceding frame's map-facing edge at x349. Its upper
outer shoulder slopes inward over 70 pixels to a 44-pixel-high cap. The title's
actual glyph bounds are centered within that flat cap, including Paused/Stopped
and the Trip-only heading.

The content body retains alpha 220/255. The shaded cap, bevel and rectangular
black lower base are opaque; there is **no software-drawn circular cutout**.
The base extends to y326, while content stays above the lower divider at y270;
Audi's own dial remains in front. A thin top/left highlight, darker inner and
right/bottom edges, paired rules and a small soft shadow approximate the RGI
style. Lighting is not mirrored with the outline. This is procedural artwork,
not an extracted OEM skin.

The owner's September 30 photo showed the preceding curved version in the car
and exposed its mismatched cutout. It did not independently read back a build ID
or validate all behavior. The earlier 210-pixel width came from the known right
KDK **content crop**, not a measurement of the complete visible Audi frame.
The wider geometry, height and skin are photo-derived candidates, not calibrated
vehicle masks; the new result still needs parked confirmation.

Both on gives compact music and Trip sections. Music-only uses larger artwork
(64 rather than 44 video pixels) and full-width title/artist rows; without artwork
the text moves higher. Trip-only gives ETA, remaining time and distance separate,
larger rows. Both Off removes the entire pocket. Missing media/route data uses
the same single-section layouts; neither leaves an empty disabled section.
The outer position and silhouette stay unchanged between these states. Both temporarily hide
while the VC settings panel is actually drawn, so the expanded mascot chooser
cannot cover only part of a card.
The moving mascot remains underneath the cards and can pass behind them.
They do **not** slide inward or auto-reposition on View changes: downstream Audi
dials are intended to cover them in small-map View. The placement is provisional,
not a measured dial mask. Parked photos of both Views must establish whether both
cards are revealed/covered as intended on this car; host previews cannot prove it.
Master Off, Audi-map-only mode, unavailable video, disconnect and expired control
withdraw the cards. They never own steering-wheel input.

The component's existing media-cache listener and an independent RGI observer
capture bounded data even in map-only mode and while the toggles are Off. Only
the settings worker formats text and atomically publishes a four-second
`CARDS3` snapshot at `/ramdisk/carplay_cards.control`, bound to mirror PID and
receiver generation, with a monotonically assigned observed-track revision.
Java and native must be upgraded together; old/mixed versions are rejected.
Arrival validation accepts the formatter's 24-hour and AM/PM clocks, including
its ` dest` suffix. The native worker reads/paints and advances scrolling from
monotonic time; unchanged offsets reuse the existing texture, including endpoint
pauses and short labels. The frame and shadow are generated once per painter and
copied from a cached premultiplied RGBA image while text scrolls. A six-pixel pad
keeps the shadow inside a 242x268 texture drawn at `(113,64)` without shifting or
scaling the visible frame. Painter allocation failure withdraws the overlay and
logs an explicit error; there is no partial-success fallback. GL callbacks do no file
I/O. The control file contains media metadata: diagnostics summary reports only
its presence, not its contents. **CONTROL_PUBLISHED is not presentation proof.**
The native worker's render lease is at most one second; a stalled worker cannot
keep cards alive merely because video continues. No additional EGL swaps or
mirror telemetry are generated. Renderer errors are throttled in the existing
mirror log, included only in the private full export.

For parked acceptance, enable each card separately and then both. Check play,
pause, track/artwork changes, route start/stop and receiver reconnect. Check long
title/artist endpoints, a short track afterward, and the HU's 12/24-hour time setting.
Photograph wide and small-map Views without moving the cards; check the wider
frame, rectangular base, centered headers, caption clearance and title/artist readability,
Audi-dial coverage, right-side guidance and the lower Audi bar. Open the ordinary
VC menu and mascot chooser, then close them to confirm card restoration. Toggle
both Off and confirm the original map presentation returns without reconnecting.

### Experimental speed/limit badge

**Map cards -> Speed / limit badge (experimental)** defaults Off and applies live.
It is separate from the small maneuver box and the fixed left music/Trip frame.
It uses the mirror-only canvas and the same worker/control path; it never changes
Audi coding, subscriptions, sign-recognition settings or OEM observers.

The provisional selection is **camera first, map fallback**. Camera candidates
must have accepted callback validity, an explicit camera-only source and a
positive North American conventional/variable speed-limit value. No-sign,
cancellation, invalid values, conditional additional signs/text and ambiguous
camera/database/fusion flags withdraw that slot. Different simultaneous camera
limits are ambiguous, not a reason to guess slot priority. Explicit TSD Off or
camera-blind messages clear camera candidates. Otherwise the fallback is
TrafficRegulation's highest-priority **conventional**, not advisory, limit.
The fallback does not reinterpret an unavailable additional-sign enum as proof
that the road is unconditional. These are experimental source choices, not a
guarantee of the legally applicable limit.

`signEffective=false` is deliberately **not** a veto: the exact Java trace found
no such rule, and the owner approved trying camera-first despite its unresolved
native meaning. A small **CAM/MAP** label identifies the selected source. A
camera/map disagreement keeps the camera choice and is recorded in Verbose logs.
Missing limits show `--`; they do not become zero or retain a cleared value.

Current speed uses `vehicleSpeed`, not `realVehicleSpeed`. It must have valid
outer and inner status, known units and a finite nonnegative value. The shared
unit label follows the current vehicle-speed unit; a differing limit unit is
converted before integer rounding. With no current speed, the selected limit's
unit is used. Red `#ff6262` means the displayed integer speed is strictly greater
than the displayed integer limit, for **either** source. Equality, unknown speed
or unknown limit never produces red. This is a visual comparison, not an audible
warning, enforcement tolerance or substitute for posted signs.

Moving samples expire after two seconds; exact zero may remain for at most
15 seconds because the retained parked feed paused over ten seconds. Invalid
callbacks clear immediately. Speed expiry travels with the control snapshot, so
a still-readable old file cannot renew it. Signs are change-driven: an arbitrary
two-second timeout would erase valid unchanged limits. They remain until a
replacement/clearing/invalid event, service loss or session reset. That does not
prove freshness if an upstream sign source silently stalls.

The settings worker publishes at approximately 5 Hz while the badge is enabled,
without increasing the one-second preference/session file refresh cadence.
`CARDS3` includes a strict, bounded `SPEED` record. The native worker paints only
changed pixels; GL performs no file I/O or CPU painting and adds no swaps.
Master Off, Audi-map mode, receiver loss, unavailable video, invalid controls
and expired worker leases withdraw the badge. The VC settings panel temporarily
suppresses it. Normal logging still supplies badge data, but does not record
numeric/sign samples; badge Off plus Normal withdraws the passive subscriptions.

At 1440x455 the badge is **138x72** at **(920,115)** for large-map/small-dial View,
or **(805,115)** for small-map/large-dial View. Placement follows accepted Fct54
Status, including stock startup replay; unknown presentation uses the inward
position. No right RGI plane is moved. These positions come from the approved
photo mock, **not** a measured Audi occlusion mask. Real host EGL pixels establish
composition, not vehicle clearance or speed-limit accuracy. Follow the
[camera-first car trial](../input/speed-source-test.md#camera-first-badge-trial).

### Optional map mascots

**Overlay appearance -> Map mascot** selects Off or one of the locally configured
animations. Public builds default to Off only. The owner's private pack preserves
Raccoon, Nian, Capybara and Lizard in their original order.
The VC panel's **Map mascot** page offers the same choices, with four choices per
page plus Previous/Next links when needed. Its right-hand animated preview follows
focus without saving or changing the mascot on the map. OK applies the choice;
Back leaves it unchanged. Off previews no animation. The preview page is 640x288,
centered, while ordinary pages remain 420x288. Missing art shows
**Preview unavailable** rather than hiding the menu. A transparent,
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
a decoder stall by advancing mirror telemetry. Off performs no map-mascot GL work;
an open preview still uses the existing VC panel's texture.
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
reported renderer state (`ACTIVE` for any selected animation), not proof of visible vehicle pixels. Asset/control/GL
failures are logged; asset failure disables only the mascot for that mirror
process, and GL failures retry at most once every five seconds. A stalled control
worker loses its render-side lease after one second. Native status-write errors
include operation, errno and path, with repeated identical errors limited to once
per 30 seconds. `CONTROL_ERROR` and `STATUS_ERROR` distinguish Java publication
from status-reading failures.

GIF decoding and background removal happen on the host, not the HU. Create a
local JSON file (paths are relative to it):

```json
{
  "format": 1,
  "mascots": [
    {"name": "My mascot", "file": "my-animation.gif", "facing": "left", "background": "transparent"}
  ]
}
```

Use unique printable ASCII names of 1..24 characters, excluding Off. `facing`
is `left` or `right` and must match the original animation; it controls initial
travel direction, not an initial artwork flip. Background is `transparent` or
`exterior-white` (remove edge-connected near-white, preserving enclosed white).
The array may contain 0..16 entries. Each GIF needs 2..32 frames with 20..2000 ms
delays; conversion normalizes the common animation canvas to height 40, width
at most 128. Final atlas size is capped at 16 MiB.

```sh
python3 tools/prepare_mascot_pack.py --config /local/art/pack.json --output build/my-pack
export MASCOT_PACK="$PWD/build/my-pack"
./scripts/build_java.sh
# Build native artifacts as usual, then stage with the same MASCOT_PACK:
SKIP_BUILD=1 ./scripts/build_sd.sh
```

With Docker, explicitly mount the generated pack and set `MASCOT_PACK` to its
**container** path for staging. `build_java.sh` mounts the host pack itself.
There is no runtime GIF browser or catalog file I/O on HMI callbacks. The generated
Java choices, embedded catalog, shell preference bounds and native `MASCOT02`
atlas form one package; staging rejects mixed JAR/catalog/atlas inputs.
Do not copy only the atlas or JAR into an already-staged package.
Without `MASCOT_PACK`, Java builds Off-only choices and staging creates a valid
empty atlas, retaining the mirror library preload and the complete VC panel.
The legacy four-input `build_mascot_assets.py` remains for historical fixtures;
its `MASCOT01` output alone is not a current SD pack.

For an original example requiring no third-party artwork:

```sh
python3 tools/create_example_mascot.py --output build/example-art
python3 tools/prepare_mascot_pack.py --config build/example-art/pack.json --output build/example-pack
```

The generated geometric robot artwork is dedicated under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/); this does not change
the licenses of the code, fonts or any supplied third-party art.

Array position is the persistent ID (Off=0, first entry=1). Preserve existing
entry order and append new entries when retaining saved selections. The owner's
private manifest keeps Raccoon=1, Nian=2, Capybara=3, Lizard=4. Format 3 preferences
are unchanged. **Choose Off before replacing/reordering/removing a pack, switching
to no artwork, or downgrading.** A removed out-of-range selection fails strict
preference validation and takes the existing disabled safe path, with an error;
an in-range reordered ID otherwise refers to the new artwork at that position.
Source GIFs, derived artwork and SHA256 provenance stay in
private inputs/ignored build output, not Git. No redistribution license for the
current modified atlas has been established. The binary-only mirror is not rebuilt or
represented as fully source-audited.

**Artwork redistribution review, 2026-09-29**

| Reference | Verified evidence | Public package status |
| --- | --- | --- |
| Raccoon | Supplied file is hosted by Dribbble. A search suggested "baby raccoon" by Dina Mae, but the exact asset-to-shot match and artist-specific permission could not be verified; the shot pages returned access challenges. [Dribbble terms, sections 9-11](https://dribbble.com/terms) do not provide a general third-party asset redistribution license. | Not cleared; do not present the suggested artist as verified attribution. |
| Nian / lion dance | The supplied Pinterest CDN image could not be traced to an original creator or asset-specific license. [Pinterest's terms](https://policy.pinterest.com/en/terms-of-service) contain user-content grants, but the original uploader's authority over this particular repost is unverified. | Not cleared; neither a confirmed prohibition nor a confirmed redistribution grant was found for the exact artwork. |
| Capybara | Exact source confirmed as [Capybara run by EiBBiT](https://www.deviantart.com/eibbit/art/Capybara-run-933711073). The page references the supplied GIF filename and explicitly labels it [CC BY-NC-ND 3.0](https://creativecommons.org/licenses/by-nc-nd/3.0/). | The unchanged original may be shared noncommercially with the required attribution/license notices. The current background-edited sprite is not cleared by the NoDerivatives license; seek separate permission before publishing it. |
| Lizard | The supplied [Instagram reel](https://www.instagram.com/reel/DVCmwa1jR8D/) identifies uploader `igreenscreenthings` in its public embed. A related [CreatorSet Tom the Lizard walking template](https://creatorset.com/collections/all-products/products/tom-the-lizard-walking-meme-hoppers-blue-screen-green-screen) states personal use only and prohibits redistribution; it also disclaims commercial rights to the underlying material. | No redistribution grant verified for the supplied clip. The related product references a different reel, so its terms are supporting evidence, not proof of this MP4's exact provenance or license. |

The [CC BY-NC-ND 3.0 legal code, section 3](https://creativecommons.org/licenses/by-nc-nd/3.0/legalcode.en)
allows technically necessary format changes; do not claim that merely changing
GIF to RGBA always creates a prohibited adaptation. Background editing and the
intended modified presentation are the unresolved permissions here. An unchanged
licensed original is distinct from the processed atlas. A public post, download
button, attribution or ownership disclaimer alone does not establish the needed
rights. This review records evidence and release restrictions, not a definitive
legal opinion about exceptions or every possible use. No artwork was published.

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
`format=5` (24 settings). Complete `format=1` files (16 settings) are accepted with
Road/exit, next-road text, 20-second return, Custom and mascot Off defaults.
Complete `format=2` files (20 settings) retain their choices and default mascot Off.
Complete `format=3` files (21 settings) retain their mascot selection.
Formats 1-3 default both map cards Off. Complete `format=4` files (23 settings)
retain both card choices. All older formats default the speed badge Off. Reads do not
rewrite old files; the next save migrates them. Incomplete or mixed-version files
are rejected rather than filled with silent defaults. Save uses a
flushed/synced temporary file and rename. Invalid settings select the safe disabled
path and report an error; preference reset can repair them without enabling the
master switch. The old `cluster_ui.url` is imported only when no new preference
file exists. The GEM layout picker updates the new setting once it exists.

Older builds cannot read format 5, even when the badge is Off. An intentional
downgrade needs a complete preference file supported by that build or its Reset
preferences action; unsupported data takes the disabled path, never a partially
interpreted configuration.

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

### Passive speed-source diagnostic

**Diagnostic logging -> Verbose next session** now also subscribes to the
existing vehicle-speed, TrafficRegulation and TSD notifications while CarPlay
integration is active. It uses the verified receiver-session verbosity, not
merely a newly requested preference. Manually reconnect to apply either Verbose
or Normal. **System status -> Speed source probe** reports how many of the three
services are subscribed, explicitly not whether they supply usable data. These
same independent listeners also serve the badge when it is enabled in Normal
logging; turning off the badge does not stop an active Verbose diagnostic.

The probe never enables traffic-sign services, changes Audi settings/subscriptions
or takes over an OEM observer. Numeric/enum samples and source validity go only
to the existing bounded private Java logs. No GPS position or conditional-sign
text is recorded. Full diagnostic export includes these logs; summary export
does not. Enabling diagnostics alone does not enable the optional badge.
Follow the [parked and lawful-driving test plan](../input/speed-source-test.md).

## Tests and limitations

`TOOLS_DIR=... STOCK_JAR=MU1316-P5145-stock.jar ./scripts/test_mmi_settings.sh`
checks every preference choice, v1-v4 migration/v5 strictness, preset preservation,
malformed files, atomic-save failure, reset,
guarded factory patch equivalence, strict class verification, real native widget
constructors, OEM-row retention, nonblocking action handling/timeouts, live
lifecycle gates, and launcher preload behavior. Regressions exercise actual
stock `RunnableEvent` dispatch, worker-thread isolation, coalescing, close and
reconnect generations, queue rejection, and prohibit tree updates inside paint.
A filesystem fault fixture rejects `/tmp` rename while allowing ordinary
read/write. Java logger rotation and production shell snapshot paths are tested
against that contract; nonzero/timeout action output must remain diagnosable.
`SpeedSourceDiagnosticsTest` uses the exact stock interfaces/DTOs with strict
verification, asserting notification-only calls, synchronous replay, rejected
payloads, bounded transitions, no free-form text, independent instance-0
subscriptions, service rebind/partial failure cleanup, stale callback rejection,
Normal/Verbose session gates and nonblocking stop during a blocked DSI call.
This does not establish J9/DSI delivery, fitted hardware or on-car accuracy.

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

`./scripts/test_mascots.sh` generates synthetic packs and uses host EGL/GLES2.
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
