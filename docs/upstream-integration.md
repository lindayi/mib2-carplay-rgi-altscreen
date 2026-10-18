# Upstream integration: 2026-10-18

This candidate targets only `MHI2Q_US_AUG22_P5145 / MU1316`.
It replaces the old AltScreen hook and mirror binaries with local source builds.
It is **host-verified, not vehicle-verified**. No SD card was written during this
integration. Earlier vehicle reports do not validate this candidate.

## Source pins and merge decisions

| Source | Pin | Decision |
| --- | --- | --- |
| Local customizations | `90cfa86` | Preserve normal-MMI settings, VC panel, cards, speed badge, mascots, footer, route lifecycle and safe deployment |
| Allemon main | `1289981` | Merge ancestry; adapt placement/view-area work to local ownership and tests |
| Allemon PR 7 | `af3d353983261a12646a87cbe1ff8793aec251ec` | Accept corrected final-script checksum; regenerate it during staging |
| Allemon PR 14 | `3282773e5785c86a8e574d524840689bae76d69f` | Do not merge its night-mode retry implementation |
| yuedizhibo V3.8 | `141e7ed33775af02a5af0b8616ee3c6250eea997` | Import native source; port compatible Java/RGI/telephony fixes instead of importing a second stack |

Allemon's touchpad-bridge removal would delete a local configurable feature.
This fork keeps that bridge. Its MU1003 build variant is not enabled: the exact
MU1316 stock-class guards remain mandatory.

[Allemon/mib2-carplay-rgi-altscreen#7](https://github.com/Allemon/mib2-carplay-rgi-altscreen/pull/7)
fixes real metadata drift. The accepted final-script SHA1 is
`b1fc8feba6e9cd8ca77d32877e4bc46e4436ef8e`; future script changes regenerate it.

[Allemon/mib2-carplay-rgi-altscreen#14](https://github.com/Allemon/mib2-carplay-rgi-altscreen/pull/14)
uses `0x0117` for night mode, but V3.8 assigns that number to telephony.
It also sends an opposite mode, sleeps, then uses the current global session to
send an older captured mode. That can cross a session boundary or overwrite a
new mode. The delayed retry is not included. This is not a claim that its
reported theme issue is fixed.

## One owner for each function

| Function | Owner in this fork |
| --- | --- |
| Native RGI, artwork and iAP2 telephony | `hook/`, built as the existing GPL `libcarplay_hook.so` |
| Type111 transport, stock OMX decoding and decoded SHM | `altscreen_native/hook/`, separate source-available DSO |
| NV12-to-GLES mirror | `altscreen_native/mirror/`, separate source-built executable |
| Contexts 81, 80 and 74 | Java `ScreenModule` only |
| Phone layout and wheel zoom | Existing Java bus commands and `hook/altcluster/` only |
| Phone FULL/SMALL view-area selection | Not wired in `490dce9`; see the functionality audit below |
| Physical displayable-3 placement | Java `AltScreenPlacement` only |
| Mascots, cards, VC panel and speed badge | Existing mirror-only EGL preload |
| Receiver/USB lifecycle | Stock SI and the existing exec wrapper |
| Mirror/renderer recovery | Existing guarded local supervisors |

Receiver preload order is **RGI hook, then AltScreen hook**. The RGI display
dictionary wrapper must see the source hook's result. The mirror does not inherit
either receiver preload.

The source hook's native showUI, stopUI, zoom and competing geometry policy are
disabled. Its stream-supervisor executable mode is not deployed. The old frame-rate
binary patcher is no longer in the build path. The startup logo, transparent
watermark asset and unused Window58 bridge are no longer needed or distributed.
The mirror waits for real video before publishing readiness.
Normal video presentation uses one interposable EGL swap per frame, including
the first frame, so the custom overlay sees the actual presentation.

## Integrated fixes

- Separate lane-event IDs from maneuver IDs. Preserve future lanes and protect
  active/linked entries during cache eviction.
- Mark RGI start only after stock `SendIAP2` returns successfully. Reject queued
  and completed work from an old injection/object generation. One framework
  timer dispatches all module ticks; teardown drains that dispatcher.
- Move bulk artwork writes and worker creation outside receive/queue locks.
  Fence final publication by receiver generation and keep the PNG CRC contract.
- Bound bus reconnect retries and leave fatal-signal handling to the stock
  receiver. A renderer cannot continue without its progress watchdog.
- Replay the latest suppressed OEM BAP fields when ownership is released.
  Retire replaced service wrappers; retry failed cleanup without requiring a
  full MMI reboot.
- Weave the exact stock `DSIResponseContainer.setRgActive` setter. Track genuine
  native updates while CarPlay applies its temporary acceptance flag, rather
  than restoring only the value saved at takeover.
- Refresh RGI service bindings from the resident lifecycle worker. Preserve
  local footer, destination-zone, information-page and Keep behavior.
- Add bounded, generation-gated CarPlay calls, recents, favorites and communication
  data. Restore native Bluetooth state on release and retain OEM emergency-call
  priority. Register the matching iAP2 IDs in both installer paths.
- Install the exact TCP 6031 rule in applicable `pf.conf` and `pf.*.conf` files.
  Back up variants separately, validate candidates before publication, and
  restore original bytes. Do not replace original backups on upgrade.

Startup requires two independent facts: OEM `MapKombi` readiness and a usable
native AirPlay command channel. `CMD_OEM_MAP_READY=0x0119` opens the native OEM
gate. `EVT_ALT_SESSION=0x0031` carries a four-byte, big-endian session epoch;
zero withdraws readiness. A nonzero epoch requires successful Main110 SETUP,
successful SessionStart and an event client. The getter itself sends nothing.
Java queues one cached-URL startup request before video readiness, then coalesces
the first video edge. Later route/View/main-MMI requests keep their existing
generation gates. A successful request is not resent on a timer.
Queue acceptance still does not prove that the phone displayed the requested UI.

Upstream's lower-bar-only install mode and independent `LowerBarKomo` publisher
are not imported. They would add another owner for fields managed by local
information pages. Its approach-only arrow defaults and automatic road/ETA
concatenation also do not replace the owner's chosen local presentation.
This integration does not claim to reproduce every upstream default unchanged.

## Functionality audit: 2026-10-18

Audited runtime source: `490dce9`. Both upstream heads still match the pins above.
This is a source/control-flow audit, not observation of the new package in a car.
No runtime behavior was changed by this audit. The native ownership regression
also confirms that native card, showUI, zoom and view-area commands are blocked.
That test does not prove that Java implements every blocked function.

### Arrival time is not the small maneuver footer

| Surface | Upstream V3.8 | Current fork |
| --- | --- | --- |
| Audi lower information bar, Fct19, Road page | Road/exit plus arrival clock; reserves the suffix on each scrolling fragment and avoids a second supplied arrival clock | Road/exit or selected current road; no appended arrival clock |
| Audi lower information bar, Trip page | Remaining duration plus arrival clock; the OK phase toggle still exists | Large-map presentation: arrival plus duration. Small-map presentation: duration, with arrival as fallback |
| Destination arrival, Fct22 | Separate absolute-arrival field | Separate absolute-arrival field, including local destination-zone handling |
| Custom small maneuver footer | No equivalent local distance/road footer implementation | Next-maneuver distance and next road/exit, independently switchable |
| Passive Trip map card | No equivalent local card | Arrival, remaining duration/distance and estimated route progress |

The Road/Trip switch is not obsolete: even upstream retains it. Adding the
upstream road/arrival suffix would remove the need to switch merely to see the
clock, but not the duration page. The overlay distance/road switches affect a
different surface and must not be removed as if they were ETA controls.
Upstream's suffix could be ported into the existing `BAPBridge`, without importing
`LowerBarKomo` or replacing the local information-page preferences.

Evidence: local `BAPBridge.java:1280-1412,1454-1468,2300-2315`;
upstream `routeguidance/BAPBridge.java:1335-1348,1411-1447,1568-1578` and
`routeguidance/RouteGuidance.java:683-703`.
Upstream paths are below `Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/`;
local bridge paths are below `java_patch/com/luka/carplay/rgd/`.

### Open integration gaps

1. **Phone FULL/SMALL view-area selection has no active owner.**
   The native builder still advertises both areas. Its HMI-layout reader returns
   immediately under `ALTSCREEN_JAVA_OWNS_PLACEMENT`, so initial selection uses
   FULL; `alt_send_cluster_view_area` rejects calls under
   `ALTSCREEN_JAVA_OWNS_UI`. Java's View handler resends `showUI {uuid,url}`, not
   `updateViewArea`. Physical plane movement cannot select the phone's safe area.
   Thus V3.8's dynamic phone-side behavior is not preserved. This is a confirmed
   source gap, not proof of the cause of any earlier Google Maps offset.
   Evidence: `altscreen_native/hook/p1404_airplay.c:503,723-767,973-1008,1259`;
   `hook/altcluster/alt_cluster.c:131-157`;
   `java_patch/com/luka/carplay/cluster/AltScreenCluster.java:302`.

2. **The new phone-drawn maneuver-card mode is not equivalent to old top/right.**
   V3.8 uses `/car/instrumentcluster`, with card placement and a wider FULL safe
   area. This fork disables that card profile and still sends `/map?...` URLs.
   Upstream explicitly notes that `/map` is map-only for some providers.
   The old labels therefore do not establish that the new card feature works.
   V3.8's explicit phone speed-limit/compass URL flags are not sent by the local
   URL selector either; app defaults are not proof of equivalent output.
   Evidence: `altscreen_native/hook/altscreen_core.h:19-33`;
   `altscreen_native/hook/p1404_airplay.c:546-605`;
   `java_patch/com/luka/carplay/cluster/AltScreenCluster.java:351-360`.

3. **Map-only mode does not retain upstream's lower-bar-only output.**
   `SettingsRuntime` disables the RGD module for mode 1, and `CarPlayApp` skips
   its start. Therefore local information-page settings have no CarPlay lower-bar
   output in that mode. The independent passive Trip card can still work, but
   that is not the same placement or behavior as upstream's lower-bar-only mode.
   Evidence: `settings/SettingsRuntime.java:208`;
   `core/CarPlayApp.java:389`; `rgd/MapCards.java:24-28,151-180`,
   all below `java_patch/com/luka/carplay/`.

### Overlap and control inventory

| Area | Assessment and recommendation |
| --- | --- |
| Road text in lower bar and small overlay | Real visual overlap when both show the next road. Keep independent switches: the lower bar can show current road while the overlay retains next road, and their font/occlusion limits differ. |
| ETA in phone video, Audi fields and Trip card | Potential repeated information, not competing writers. Local `No ETA` changes only the phone URL, not Fct19/Fct22 or the Trip card. It is currently mutually exclusive with top/right placement; separate phone-card placement from phone-ETA visibility when adding the new card mode. |
| Phone maneuver card versus custom arrow/lanes | Potential repeated maneuver information if the phone supplies a card. Upstream explicitly offers WITH RGI CARD as well as CARD-only. Do not remove the custom path that also supplies HUD/native BAP guidance. |
| Arrow progress versus Trip progress | Not duplicates. One describes approach to the next maneuver; the other estimates progress from this connection's first route-distance sample. |
| Native phone-map speed-limit sign versus custom badge | Different sources and functions. The badge adds vehicle speed, CAM/MAP selection and a red comparison. Phone and Audi-derived limits can disagree; do not silently treat one as the other. |
| Now Playing card versus Audi media tab | Intentional repeated metadata on a different surface. The optional map card avoids changing tabs; it shares existing media/artwork input. |
| Normal MMI and VC settings | Shared `Setting` and `SettingsRuntime`, not independent preference sets. Their page lists are duplicated and have drifted: normal MMI offers the speed badge but VC Map cards lists only media and Trip (`MenuModel.java:138`, `VcPanel.java:148`). Keep the two interfaces but align their shared feature list. |
| GEM layout and normal-MMI layout | Genuine duplicate controls for the same preference. GEM's script uses the preferences file when present, so these are not two independent active layouts. Prefer normal MMI for everyday changes; keep legacy compatibility separate from visible menu entries. |
| GEM START and START alias | Exact duplicate action. The alias execs the normal START script. Its visible menu row can be removed without inventing another start mode. |
| GEM map-area overrides versus source safe areas | They overlap on the same declaration. `cluster_area.sh:31` writes safe height 542 from y=0, replacing the source's y=72/height=378 inset; `alt_viewarea.c:198-212` edits only area 0. Do not treat these legacy width presets as harmless additions to the new two-area model. |
| GEM Sport test versus automatic Sport placement | The test adds `small_dx=-476` to the stock small-stage offset. On Sport, that can produce -952, not an alternative -476. It is a Classic-only diagnostic by design, but the writer does not enforce the layout. |
| Reset versus GEM geometry | `Preferences.reset()` resets the versioned settings only. It does not clear `cluster_shift.cfg` or `cluster_viewarea.cfg`. Thus not all project display adjustments return to default. Clarify or consolidate this before presenting reset as comprehensive. |
| Presets versus individual appearance switches | Intentional convenience. An individual edit materializes the active preset into Custom; it is not a second rendering policy. |
| Automatic layout reapply versus manual reapply | Same cached session URL, different trigger. Manual reapply remains a useful recovery action; neither applies newly saved reconnect-dependent preferences. |
| Native, mirror and maneuver recovery | Different layers. The source native monitor services decoding/keyframes; the local supervisors manage separate sidecars. The upstream competing supervisor mode is not deployed. Mirror recovery Off is not a global decoder-recovery switch. |
| Multiple RGI observers and snapshot requests | Separate consumers, not multiple native RGI producers. `addObserver` does not replay a cache, so late registration still needs catch-up; do not remove sync requests merely because more than one module requests a snapshot. |
| Verbose diagnostics and speed badge | Already share passive DSI listeners. Normal badge operation does not require enabling verbose numeric logs. |
| Wheel zoom and panel navigation | One BAP detent path. An acknowledged panel consumes the detent before stock and CarPlay zoom; otherwise the normal stock plus CarPlay zoom behavior remains. Native zoom is blocked. |
| Theme handling | PR14's workaround is absent, so it is not a second active theme writer. Its reported problem remains unresolved by that exclusion. |

The largest correction is not deleting a footer switch. First wire equivalent
phone view-area behavior under one owner. Then port the desired road/arrival and
phone-card behavior into that owner, preserving local options. Consolidate actual
duplicate menu definitions and legacy geometry controls afterward.

## License and source limits

The owner accepted **source-available noncommercial** native components.
PolyForm Noncommercial plus upstream's additional terms is not an OSI
open-source license. This also covers the adapted V3.8 firewall helper.
The native source stays separate from the GPL Java/RGI
components. Original notices and licenses are retained and staged with the
native runtime. The paid-install exception is limited to upstream's original
unmodified release; it does not authorize paid installation of this derived
package.

V3.8's vendor notice still lists four unavailable baseline Java sources. This
fork does not import that baseline JAR. It uses local Java source and guarded
weaving against the owner's private stock firmware. Audi/QNX/Apple platform
libraries and existing Toolbox dependencies are not made open source by this
work. Private firmware, captures and mascot artwork stay outside Git.
See [native provenance](../altscreen_native/PROVENANCE.md).

## Build and evidence

Build the additional native image once:

```sh
docker build -t mib2-altscreen-native:local -f altscreen_native/Dockerfile altscreen_native
bash scripts/build_altscreen.sh
```

Then use the documented Java/RGI build and SD staging path. `build_sd.sh` also
builds the new native pair in a normal full build. For `SKIP_BUILD=1`, it verifies
both artifact checksums and `build/altscreen/SOURCES.sha256` against the checkout.
That manifest comes from the same Linux-volume snapshot used for compilation.
Import stubs and toolchain libraries are never staged.

The integration checks cover exact-stock Java compilation/linkage, strict native
menu and woven-setter verification, RGI and telephony lifecycles, native transport,
malformed inputs, real offscreen GLES, source-session fencing, and package
install/upgrade/export/restore failure paths. The install fixture explicitly
stubs the ARM mirror: it cannot establish actual QNX video output. Host previews
are synthetic, not cockpit photographs.

A parked vehicle trial is still required for cold startup, reconnect, native
navigation restoration, Google Maps centering, theme changes, phone/Bluetooth
handoff, VC input and physical layout. Wireless-adapter behavior and sustained
frame rate remain unverified. Do not use older vehicle reports as evidence for
this source-built candidate.
