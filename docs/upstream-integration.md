# Upstream integration: 2026-10-18

This candidate targets only `MHI2Q_US_AUG22_P5145 / MU1316`.
It replaces the old AltScreen hook and mirror binaries with local source builds.
It is **host-verified, not vehicle-verified**. No SD card was written during this
integration. Earlier vehicle reports do not validate this candidate.
**The earlier `490dce9` package remains on hold.** Its follow-up audit found
missing upstream fixes and features. The audit tables below describe that
package; the corrected source and separate `build/upstream-corrected-sd`
candidate must not be confused with it. Final clean-commit packaging is tracked
in the checklist.

The first follow-up (`140c304`) added the missing VC badge switch. The corrective
work below supersedes it with the shared three-way selector and addresses the
other audited omissions.

## Corrective source status

The corrective work is tracked in [the integration checklist](upstream-integration-checklist.md).
It now includes count-zero typed lists, missing-junction fallback, shared optional
approach timing, current Luka lane-cache fixes, transport worker recovery, Road ETA,
renderer-independent Fct19/20 output, and coordinated native model/KOMO updates.
Both menus use **Off / Our camera-first badge / Phone sign**. The phone sign is
app-rendered content, not numeric speed-limit data. Our badge keeps Audi-map fallback.

The wrapper captures map/card layout, phone sign and ETA in one preference read,
before receiver exec. Java matches that snapshot to the native receiver PID and
keeps it across module restarts. Card position can change live; profile, sign and
ETA changes still require reconnect. Native FULL/SMALL selection uses accepted
layout/View data and a 1500 ms `/ramdisk` HMI lease. Native UI completion, stopUI
and bounded no-frame recovery share one native owner.

Local context **82** retains `{101,102,3}` while rebinding renderer 98, then
returns to verified **81**. It is the role adapter for upstream refresh context
81, not another context writer or a new displayable. Rejected/unconfirmed refresh
uses the established 72 fallback. Final readback, receiver lifecycle and renderer
peer revision fence acknowledgement. A successful bind is not resent on a timer.

Full-mode native model writes use the existing stock formatting but bypass stock
BAP-listener mutation. Native callbacks retain independent latest values.
Release restores those values, not a stale takeover snapshot or project data
returned by `updateAll()`. NavigationJobs owns model writes; a blocked setter
cannot hold the route callback's pending-work lock. Partial failures retain
cleanup responsibility, and old owners/services cannot clear a replacement.
Lower-bar-only mode does not touch these models, native route guidance or renderer
contexts. Its wheel Road/Trip toggle remains available.
The receiver-scoped renderer monitor remains available for a live return to full
guidance. Text-only output never waits for it; renderer-independent output does
not mean the persistent renderer process must be stopped.

GEM now directs preferences to normal MMI. It has one START entry. Compatibility
START scripts remain, but obsolete layout/shift/safe-area scripts fail explicitly
without changing files. Existing legacy layouts remain readable before a normal
preference file exists. Reset writes normal preferences; it does not delete old
expert files, backups or private data.

These are **source/host results**, not confirmation of phone rendering, physical
clearance, QNX runtime behavior or installation. Final source-matched packaging,
fault paths and remaining checklist items still gate delivery.

## Source pins and merge decisions

| Source | Pin | Decision |
| --- | --- | --- |
| Local customizations | `90cfa86` | Preserve normal-MMI settings, VC panel, cards, speed badge, mascots, footer, route lifecycle and safe deployment |
| Allemon main | `1289981` | Merge ancestry; adapt placement/view-area work to local ownership and tests |
| Allemon PR 7 | `af3d353983261a12646a87cbe1ff8793aec251ec` | Accept corrected final-script checksum; regenerate it during staging |
| Allemon PR 14 | `3282773e5785c86a8e574d524840689bae76d69f` | Do not merge its night-mode retry implementation |
| Luka main | `ab3317c65cedd0d323da71192d60f627d6f13246` | Reviewed current lane selection/cache fixes; retain the single local RGI stack |
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

The underlying report is
[Allemon/mib2-carplay-rgi-altscreen#13](https://github.com/Allemon/mib2-carplay-rgi-altscreen/issues/13).
It reports success on **MU1329**, wired, with iOS 27; it is not a MU1316 or
source-built V3.8 result. Its server-level `nightMode` observation and claimed
Google Maps behavior are useful evidence, but an opposite-mode pulse is an
active phone-wide change, not passive synchronization. No such pulse is enabled
here. A parked trial must compare Google/Apple Maps, app appearance settings,
connect/reconnect and genuine day/night edges. Retain local logs and raw/final
cluster images if the symptom occurs. Do not call this issue fixed without that
evidence or reuse its conflicting command number.

### Repeatable source comparison

The three upstream HEADs were rechecked against the full pins above. Allemon PRs
7 and 14 were still open. Recheck pins and PR bodies on the next integration; do
not interpret a fetched branch as merged ancestry.

| Upstream component | Local adapter |
| --- | --- |
| V3.8 `hmi/src/com/luka/carplay/routeguidance` | `java_patch/com/luka/carplay/rgd`, including the shared policy and model owner |
| V3.8 cluster controller | `ScreenModule`, `ClusterLayerController`, `AltScreenCluster` and `AltScreenPlacement`; map context roles before porting |
| V3.8 native hook/mirror | `altscreen_native`; original path/hash mapping is in its provenance and build source manifest |
| Luka native lane/cache implementation | Existing `hook/routeguidance`, with local timezone bit 22 retained |
| V3.8 install/recovery scripts | Existing `altscreen/Toolbox/scripts` transaction and restore paths, not a second installer |

For a fresh comparison, clone the public source into ignored build space and
check out the full pin, rather than comparing only similar basenames:

```powershell
git clone --no-checkout https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen.git build\upstream-reference
git -C build\upstream-reference checkout --detach 141e7ed33775af02a5af0b8616ee3c6250eea997
git diff --no-index -- build\upstream-reference\Toolbox\carplay_alt_screen\hmi\src\com\luka\carplay\routeguidance\BAPBridge.java java_patch\com\luka\carplay\rgd\BAPBridge.java
```

The diff command returns 1 when files differ. Review each difference against the
checklist and its regression, not as a command failure or an automatic merge.

## One owner for each function

| Function | Owner in this fork |
| --- | --- |
| Native RGI, artwork and iAP2 telephony | `hook/`, built as the existing GPL `libcarplay_hook.so` |
| Type111 transport, stock OMX decoding and decoded SHM | `altscreen_native/hook/`, separate source-available DSO |
| NV12-to-GLES mirror | `altscreen_native/mirror/`, separate source-built executable |
| Contexts 81, 80, 74 and transient refresh 82 | Java `ScreenModule` only |
| Phone layout and wheel zoom | Existing Java bus commands and `hook/altcluster/` only |
| Phone FULL/SMALL view-area selection | One native UI queue, using Java's accepted HMI snapshot |
| Physical displayable-3 placement | Java `AltScreenPlacement` only |
| Mascots, cards, VC panel and speed badge | Existing mirror-only EGL preload |
| Receiver/USB lifecycle | Stock SI and the existing exec wrapper |
| Mirror/renderer recovery | Existing guarded local supervisors |

Receiver preload order is **RGI hook, then AltScreen hook**. The RGI display
dictionary wrapper must see the source hook's result. The mirror does not inherit
either receiver preload.

The source hook's UI queue handles showUI, stopUI and view-area completion.
Competing native zoom and physical-plane writers remain disabled.
Its stream-supervisor executable mode is not deployed. The old frame-rate
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
gate. `EVT_ALT_SESSION=0x0031` now carries eight big-endian bytes: epoch followed
by receiver PID. Epoch zero withdraws readiness. A nonzero epoch requires successful Main110 SETUP,
successful SessionStart and an event client. The getter itself sends nothing.
Java queues one cached-URL startup request before video readiness, then coalesces
the first video edge. Later route/View/main-MMI requests keep their existing
generation gates. A successful request is not resent on a timer.
Queue acceptance still does not prove that the phone displayed the requested UI.

The first integration excluded upstream's lower-bar-only mode, `LowerBarKomo`,
approach-only arrow policy and road/ETA suffix to preserve local presentation.
That was too broad an interpretation of the request. The suffix and renderer-free
lower-bar mode are compatible additions. `LowerBarKomo` also updates OEM models
and KOMO, not just the fields sent by our direct BAP publisher. It needs an
ownership/formatting adapter, not rejection as an inherently redundant component.
Arrow timing is a real policy choice; it need not require deleting either option.

### Timing and output audit

| Domain | Clock and behavior |
| --- | --- |
| Native frame pacing | `CLOCK_MONOTONIC`, 64-bit nanosecond subtraction; multi-second delays cannot wrap a QNX 32-bit `long` |
| Java lifecycle waits, collapsed wheel events and KOMO retries | Shared positive-wall-delta `ElapsedClock`; backward jumps do not strand waits or prolong input markers; forward jumps still advance it |
| RGI presentation retries, text scrolling and page timeout | Existing bounded/rebased wall-time handling remains; retry waits are capped and stale page deadlines reset rather than waiting for the old clock |
| Native BAP-gate installation/replay | Existing backward-jump/expiry guards retire old queued tickets; native values remain cached for retry |
| Receiver, HMI, panel, mascot and speed leases | Keep realtime timestamps shared with native readers; enforce identity, expiry and maximum future lease bounds |
| ETA and destination zone | Keep UTC/wall-clock conversion and the explicit destination offset; elapsed clocks must never become arrival timestamps |
| Renderer output | Check its append-mode regular stderr file each monotonic second; reset at the 1 MiB threshold without replacing the inode; leave pipes/TTYs alone |

The log threshold is not a zero-overshoot hard cap. A cap failure is explicit and
stops the renderer with a failure exit status. The renderer's check remains active
after its receiver monitor exits. Host fixtures cover multi-second timing,
same-inode reset, non-owned output and truncate failure; QNX cross-compilation
is not proof of every target filesystem failure mode.

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

Origin labels describe the two features being compared, not their authorship.
An inherited upstream feature can also have local modifications.

| Area | Origin | Assessment and recommendation |
| --- | --- | --- |
| Road text in lower bar and small overlay | Local / upstream | Visual overlap when both show next road. Keep independent switches: current-road choice, fonts and occlusion differ. |
| ETA in phone video, Audi fields and Trip card | Local / upstream | Repeated information, not competing writers. `No ETA` affects the phone only and shares an exclusive selector with placement. Separate these dimensions. |
| Phone maneuver card versus custom arrow/lanes | Upstream / upstream, with local renderer additions | Upstream itself offers WITH RGI CARD. Keep the custom footer/HUD/lanes; do not treat all maneuver output as a local duplicate. |
| Arrow progress versus Trip progress | Local / upstream; not equivalent | Approach to a turn versus estimated journey progress. |
| Phone-map speed-limit sign versus custom badge | Local / upstream; not equivalent | The local badge adds vehicle speed, Audi-derived limits and warning color. Sources can disagree. |
| Now Playing card versus Audi media tab | Local / stock | Optional repeated metadata on another surface; shared media/artwork input. |
| Normal MMI versus VC settings | Local / local | Shared settings/runtime, duplicated page membership. VC omits the speed badge. Align lists without removing either interface. |
| GEM layout versus normal-MMI layout | Local / upstream | Same preference. Keep compatibility, but route writes through one coherent persistence path. GEM directly edits the file; simultaneous-write loss is a risk to test, not a reproduced failure. |
| GEM START versus its alias | Inherited upstream / inherited upstream | Exact same action: alias execs START. No second start mode. Consolidating visible rows does not require deleting compatibility scripts. |
| Legacy GEM map-area overrides versus V3.8 safe areas | Older upstream / newer upstream, retained locally | Old preset writes y=0/height=542 over the new y=72/height=378 inset and changes only area 0. Migrate or isolate these controls; do not apply them blindly. |
| GEM Sport test versus automatic Sport placement | Upstream diagnostic / locally integrated upstream placement | Unguarded extra -476 can add to automatic -476 and yield -952 on Sport. A diagnostic offset is not an alternative stage transform. |
| Reset versus separate GEM geometry files | Local / upstream | Reset does not clear `cluster_shift.cfg` or `cluster_viewarea.cfg`. Define reset scope and migrate legacy state. |
| Presets versus individual switches | Local / local | Intentional convenience. Edits materialize the active preset into Custom, not a second active policy. |
| Automatic versus manual layout reapply | Local / local | Same cached URL, different trigger. Manual recovery is not a way to apply pending reconnect-dependent settings. |
| Native recovery versus sidecar supervisors | Local / upstream; different layers | Local native monitor retains startup/consumer keyframes, but bypasses upstream no-frame recovery. Sidecar restart is not an equivalent replacement. Keep one supervisor per process. |
| RGI observers and sticky-sync requests | Local / upstream; separate consumers | Only one native RGI producer. Registration does not replay the bus cache, so late subscribers still need catch-up. |
| Speed diagnostics versus badge | Local / local | Already share passive DSI listeners; Normal logging can run the badge. |
| Wheel zoom versus panel navigation | Local / upstream | One BAP input path. Owned panel consumes before stock/phone zoom. Do not enable a second native wheel writer. |
| Theme handling | Local / upstream proposal | PR14 is not active, so no duplicate writer. The reported theme issue remains open. |

The correction is not deleting a footer switch. Restore upstream functionality
under coherent owners, then layer local display and menu choices on it.

## Deeper RGI and maintenance audit

### What was actually retained

The deployed RGI is this fork's **Luka/Allemon-derived implementation**, not a
wholesale adoption of yuedizhibo's current RGI. The source migration selectively
ported fixes into `hook/`, `java_patch/com/luka/carplay/rgd/` and
`maneuver_render/`. It did not deploy a second `libcarplay_rgi_meta.so`.

Allemon `1289981` is a Git ancestor of this branch. Yuedizhibo `141e7ed` is not:
its source was imported and adapted, not merged with matching component paths.
Its Java RGI uses `com.luka.carplay.routeguidance`; ours still uses
`com.luka.carplay.rgd`. Pinning provenance does not make future merges automatic.

Normal Allemon merges remain possible, but shared files can conflict with local
changes. Top-upstream updates are currently harder: moved paths, package names,
selective ports and disabled control paths require semantic comparison. A clean
text merge would not establish feature parity.

**Repository direction:** prefer upstream implementation, with local additions
and narrow adapters. Do not exclude a fix merely because a local implementation
exists. Discuss true behavior/ownership conflicts before choosing a policy.
Keep one deployed RGI producer and one owner per output, not two parallel stacks.

For the next implementation, use a pinned top-upstream component baseline with
stable source paths/packages and a small, documented local patch layer. Retain
MU1316 source adapters for unavailable upstream baseline classes and exact-stock
weaves; do not import the incomplete baseline JAR. Maintain an explicit mapping
and fix-coverage matrix for upstream changes. Further Luka fixes belong in that
same stack, not in another runtime or a blind whole-repository merge.

### Fix-by-fix findings

These classifications concern source behavior at `490dce9`, not vehicle results.
`Missing` means the upstream mechanism is absent or bypassed. It does not mean
the corresponding symptom has been observed in this car.

| Fix or feature | Upstream evidence | Current status and reason |
| --- | --- | --- |
| Valid maneuver list while optional count stays zero | `0c33fe6`, `BAPBridge` | **Missing; integration mistake.** Local `hasAnyManeuver` uses count alone; renderer rejects count zero. Custom labels have the same assumption. No local feature requires this rejection. |
| Absent junction type falls back to maneuver type | `0c33fe6`, `ManeuverMapper` | **Missing; integration mistake.** Local mapper returns NO_INFO for an ordinary turn with absent junction geometry. |
| Lane IDs independent of maneuver IDs; preserve future cached events | `5f17bd5`, native `rgd_hook` | **Ported.** Do not confuse this V3.8 fix with the newer Luka lane simplification below. |
| RGI start acknowledged after actual iAP2 send; session fencing | `ebe0f5c`, framework and `rgd_hook` | **Ported**, with local post-send generation revalidation and shared tick dispatcher. Queued is not sent. |
| Artwork write/worker ordering and session isolation | `5ea24e9`, `f9e737b`, native cover art | **Adapted/retained.** Bulk work stays outside callback locks; keep local PNG CRC and metadata clearing. |
| Accepted Fct44/Fct54 forwarding and initial replay | `fe15cc8`, gates | **Ported/adapted.** Includes local VC-panel and speed-badge notifications. |
| Genuine native navigation state tracked during takeover | `5ea24e9`, `f9e737b`, native-state overlay | **Ported/adapted** through exact-stock setter weaving, not a saved Boolean alone. |
| Replaced BAP service cleanup/replay and retry | `f9e737b`, `ebe0f5c`, gates | **Ported/adapted.** Existing lifecycle maintenance retries service bindings and deferred cleanup. |
| RGI presentation-worker revival | `ebe0f5c`, `RouteGuidance` | **Ported**, with retry spacing. This is not all Java worker recovery. |
| Data-bus bind retry and dead-worker revival | `ebe0f5c`, `CarplayBus` | **Missing; integration mistake.** Bind failure disables the bus; `start()` returns when `running` is true even if the worker died. Local maintenance does not perform upstream's bus restart. |
| Renderer accept/write worker failure cleanup and revival | `ebe0f5c`, `RendererServer` | **Missing; integration mistake.** An open listening socket makes `connect()` return true without live workers. Socket reconnect tests did not cover worker death. |
| Renderer cold-start/process retry | Fix4 runtime supervision | **Different local mechanism.** Native watchdog exit plus local supervisor retry (five-second restart delay) remain. This does not repair dead Java server workers. |
| Map-preserving RGI refresh context, then verified fallback | `121200d`, `351e7ec`, `ClusterStateController` | **Not ported; real context-role conflict.** Upstream 81 is a refresh context; local 81 is the active CarPlay composition. Local 72 bounce is not the new map-preserving path. Map roles before adapting one controller. |
| Renderer multi-second frame timing uses 64-bit arithmetic | `fc9a8d3`, renderer `main.c` | **Missing; integration mistake.** Local elapsed nanoseconds still use 32-bit `long` on QNX and can overflow during multi-second delays. |
| Backward-clock-safe Java elapsed timers | `fc9a8d3`, `ElapsedClock` | **Partial/not ported as a shared mechanism.** Local retry code has some backward-jump guards but still uses wall time. Upstream's clock accumulates positive wall deltas; it is not a true monotonic clock and still follows forward jumps. Do not use it blindly for cross-process lease timestamps. |
| Bounded renderer output | `fc9a8d3`, `bound_private_output` | **Partial.** Java/hook logs and imported pair have bounded paths. Local maneuver log cap runs every 150 two-second monitor ticks; the renderer can outlive that monitor. This is not upstream's per-second self-cap. |
| Atomic QNX volatile snapshots and complete-record validation | Fix1/Fix2 and later session fixes | **Existing local mechanisms plus imported native helpers.** `/ramdisk` publication is retained; this does not prove the disabled native HMI-state consumer is wired. |
| JAR pins and staged checksums | Fix1; Allemon PR7 | **Retained/ported.** Build regenerates pins/manifests. |
| OEM map gate, native session lifetime and first-video startup | `f0bf661`, V3.8 | **Adapted, not identical upstream policy.** OEM/native-channel gates exist, but Java replaces upstream UI submission; missing view/card paths prevent a full first-connection parity claim. |
| `stopUI` before teardown | Fix4, native cockpit/AirPlay | **Disabled; integration mistake.** Broad Java-owner flag returns before stop and rejects the event. No Java replacement sends it. Session fencing should adapt this operation, not remove it. |
| Bounded no-frame keyframe recovery and startup probe | Fix3/Fix4, `native_check_stream_recovery` | **Bypassed.** Local transport monitor never calls it. Initial/consumer-requested keyframes remain, but are not equivalent. Restore its completion/session gates; do not treat idle maps as proven hangs. |
| FULL/SMALL phone safe-area updates | Native AirPlay/view monitor | **Disabled without replacement; integration mistake.** `showUI` and physical translation are different operations. |
| Phone card mode, left/top/right and card-sized safe area | V3.8 | **Missing feature.** Legacy `/map` choices are not equivalent. Card availability and live position policy are separate questions. |
| Road ETA suffix, fragment reservation and duplicate-clock avoidance | V3.8, `BAPBridge` | **Avoidable omission.** Add through the existing page formatter, retaining local road choice and destination-zone handling. |
| Lower-bar-only operation without custom RGI card | V3.8 modes | **Avoidable omission.** Map-only currently skips the RGD module. Decouple information publication from renderer ownership. |
| Native KOMO/model route information and its deferred cleanup | `fc9a8d3`, `ebe0f5c`, `LowerBarKomo` | **Missing adaptation, not automatically a duplicate.** Local direct BAP output does not update all these OEM models/KOMO fields. Gate any BAP side effects and preserve local formatting. |
| CarPlay telephony with Bluetooth restoration | V3.8 | **Ported/adapted** into the one existing bus/framework. |
| Fixed TCP6031 and applicable PF variant backup/restore | Fix5/Fix6 | **Ported/adapted.** Syntax/candidate checks and exact variant backups exist. Installed configuration is not proof of live loaded rules. |
| New transaction recovery/rescue and one-step installer | Fix2/Fix4 and later scripts | **Not adopted wholesale.** Local preflight, pending journals, rollback and restore checks remain; they are not the same full pre-operation snapshot/stale-transaction/rescue mechanism. One-step UX is a policy conflict, but missing resilience is not justified by that alone. |
| Dedicated firewall-source diagnostics | Later upstream scripts | **Not the same report.** Local boot/collection paths capture `pfctl -sr`; upstream's dedicated source report is absent. Do not claim either total diagnostic loss or exact parity. |
| Q7/CN/other-firmware profiles | Upstream variants | **Outside approved scope**, not a Q5 omission. Keep MU1316 guards. |
| Removed STATUS/restore-and-log entries, logos and Window58 path | Later upstream packaging | **Deliberate packaging divergence.** Keep useful local diagnostics and truthful restore labels; unused runtime paths/branding are not needed for feature parity. Keep license notices. |

Key local evidence:
`rgd/BAPBridge.java:928-930,2172,2304`;
`rgd/ManeuverMapper.java:239`;
`bus/CarplayBus.java:118-125,430-449`;
`rgd/RendererServer.java:138-169`;
`rgd/RouteGuidance.java:448-489`;
`core/CarPlayApp.java:263-272`;
`core/ScreenModule.java:40-44,392-434`;
`maneuver_render/main.c:1035-1064`;
`deploy/smartphone_integrator/carplay_monitor.sh:61-69,108-120`;
`altscreen_native/hook/p1404_cockpit_native.c:1305-1375,1385-1442,2688-2702`;
`altscreen_native/hook/p1404_airplay.c:1153-1155,1259`.
Java paths without a root are below `java_patch/com/luka/carplay/`.

`LowerBarKomo` calls `ClusterService.updateCurrentStreet`,
`updateDistanceToDestination`, `updateArrivalTime` and `updateKOMOFollowInfo`.
Our `java_patch/de/audi/tghu/navi/app/cluster/ClusterService.java:263-278,456-476,495-526`
shows model and KOMO updates **and** BAP-listener side effects. Therefore importing
it unchanged could interfere with direct BAP publication, but deleting it also
loses another output path. Reconcile that fan-out under the existing gate.

### Additional Luka changes discovered

The audit fetched `rgi-upstream/main` at
`ab3317c65cedd0d323da71192d60f627d6f13246`; it did not merge it.
Allemon and yuedizhibo remote HEADs still matched the original pins.
The integration must not be described as "latest Luka RGI".

Four newer lane commits were inspected: `8818733`, `6f2a5dc`, `d3fcbd7`,
`0f3c0c0`. They simplify lane selection to the active lane-event cache, remove
legacy per-maneuver lane bookkeeping, and use insertion-order eviction plus
passed-event removal on the activating 0x5201 update. This fork and pinned V3.8
retain the older linked/LRU design. Our existing active-ID guards already prevent
some aliasing, so absence of the simplification is not proof of every claimed
wrong-lane symptom. The latest cache policy is nevertheless **not integrated**.

This was a bounded inspection of those RGI commits, not a full audit of Luka's
new snapshot/audio changes. Do not silently add unrelated audio upmix.

### New host evidence and limits

A temporary probe compiled the actual local mapper, data bus and renderer server,
plus the pinned upstream mapper. It reproduced:

```text
MISSING_JUNCTION local=NO_INFO upstream=TURN defect_reproduced=1
RENDERER_DEAD_WORKER connect=true worker_alive=false defect_reproduced=1
BUS_DEAD_WORKER start_returned=true worker_alive=false defect_reproduced=1
```

The worker probes construct the retained state after worker exit, using a real
terminated thread and an isolated listening socket. They do not simulate an
Audi failure or prove a vehicle symptom. The count-zero, teardown, recovery,
clock, logging and installer findings above are source/control-flow comparisons.
Earlier passing suites did not test every upstream regression. No runtime,
package or SD changes were made during this audit.

### Agreed policies and remaining integration constraints

The owner selected the arrow, live-position and installation policies below.
These apply to the next implementation, not to the unchanged `490dce9` package.

| Real conflict | Direction |
| --- | --- |
| Native versus Java UI/geometry/context ownership | Use upstream implementation with narrow MU1316/local adapters. Preserve explicit single owners, map context roles, and route local triggers through the chosen queue. Do not merely flip both native flags on. |
| Upstream approach-only arrow versus current always-real arrow | **Agreed:** keep always-real as the default and add upstream approach mode as an option. Upstream uses 350 m normally and 1000 m for long/highway steps. |
| Upstream live phone-card position versus reconnect-cached local layout | **Agreed:** allow live card-position changes; keep connection-dependent mode/geometry changes on reconnect. Never disconnect the phone silently. |
| One-step upstream installation versus INSTALL/reboot/START/reboot | **Agreed target:** upstream one-step installation with integrated recovery and rescue, after transaction/restore integration and fault testing. Until then the existing deployment sequence remains authoritative. |
| Legacy offsets/safe-area files versus the new geometry model | Migrate or isolate legacy expert overrides and define Reset. Never add an old Sport correction to an automatic Sport transform. |

No policy decision is needed to retain the two basic maneuver fixes, transport
worker recovery, correct timing arithmetic, Road ETA, lower-bar-only capability,
or the missing VC speed-badge option. Those are corrections/additions, not reasons
to discard local features. ABI restrictions, licenses and one-owner rules remain
constraints, not optional preferences.

## Installation and rescue

The corrected package uses **Update Toolbox -> INSTALL -> wait for PASS -> one
full MMI reboot**, while parked with stable power and the phone disconnected.
Reconnect afterward. INSTALL includes boot-only START arming inside its
transaction; the public START entry remains compatible but is not a second
required installation step. This sequence passed host transaction/fault tests.
Its QNX execution and vehicle startup still need a separate trial.

Preflight validates firmware, unit identity, payload and runtime prerequisites.
The transaction preserves the complete pre-operation state separately from the
original stock backup. Pending state keeps boot activation inert. Failures at
mutation, arming, verification, sync/remount or activation roll back; incomplete
cleanup retains recovery files and reports failure. Stale transactions are
verified before recovery, not overwritten. Export-only does not mutate them.

With the matching SD inserted, guarded manual recovery uses the installed helper:

```sh
/bin/sh /mnt/app/root/carplay-altscreen/bin/altscreen_rescue.sh recover
/bin/sh /mnt/app/root/carplay-altscreen/bin/altscreen_rescue.sh restore-original --confirm-stock-rescue
```

The second command is an explicit stock rescue, not diagnostics. Successful
stock rescue removes that installed directory. To reverse it, use the helper
on the matching SD (`<SD>` means its actual mounted root):

```sh
/bin/sh <SD>/Toolbox/scripts/altscreen_rescue.sh undo --confirm-pre-rescue
```

Undo restores the pre-rescue state, including any prior damage; it does not
repair that state. Do not delete retained transactions, substitute backups from
another unit, or bypass identity/checksum guards. Rescue and installation are
not normal-MMI preference actions. No SD write is part of host validation.

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

Run the source-native host suite with
`bash altscreen_native/tests/run_docker.sh`. Its Linux volume keeps concurrent
rename/read fixtures off Windows bind mounts. The ownership fixture publishes
geometry before starting its concurrent worker, then exercises lease changes;
it does not relax PID/epoch/freshness checks to hide scheduling races.

The integration checks cover exact-stock Java compilation/linkage, strict native
menu and woven-setter verification, RGI and telephony lifecycles, native transport,
malformed inputs, real offscreen GLES, source-session fencing, and package
install/upgrade/export/restore/rescue failure paths. Final package checks use
`INSTALLER_PACKAGE_MODE=staged` with an explicit `SD_DIR`; development
source-overlay mode is not evidence for the packaged scripts or pins. Staged
mode verifies manifest coverage, hashes, real ARM ELF payloads and their fixture
copies. Only runtime execution is explicitly stubbed in disposable fixtures.
The local installer image uses a labelled Bash fallback, not QNX pdksh.
Neither it nor synthetic GLES previews establishes QNX video output.

A parked vehicle trial is still required for cold startup, reconnect, native
navigation restoration, Google Maps centering, theme changes, phone-card/sign/ETA
support, phone/Bluetooth handoff, VC input and physical layout. Compare live card
position changes with reconnect-only profile/sign/ETA changes; queue completion
does not prove that the phone applied them. Wireless-adapter behavior and sustained
frame rate remain unverified. Do not use older vehicle reports as evidence for
this source-built candidate.
