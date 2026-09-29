# Project knowledge and working agreements

Maintain this file when discovering a durable project-specific lesson or changing
a build/deployment invariant. Commit the update with the related project changes.
Keep observations, hypotheses, and host versus vehicle validation clearly separate.
Do not turn this into a raw conversation log or store credentials/private captures.

## Scope

- Personal project for a 2020 Audi Q5 with **MHI2Q_US_AUG22_P5145 / MU1316** and an
  iPhone using **iOS/CarPlay**. Do not broaden work to Android Auto, other Audi models,
  MHI2/Harman, MIB3, or a general retrofit product unless explicitly requested.
- The menu name requested by the owner is **Carplay Altscreen**.
- Normal-MMI preferences must never require or trigger a full MMI reboot. Apply
  live when safe; explicitly request a CarPlay reconnect for connection-dependent
  changes. Never silently disconnect the phone to apply a setting.
- Initial installation of a new HMI patch still requires the documented reboot
  sequence. This is different from changing a preference after installation.
- Master Off must disable the extra stream on the next receiver session, not just
  hide the map. Return control to the original Audi behavior; do not force a native
  map or enable dual navigation. Wireless-adapter success must be tested, not assumed.
- Keep everyday controls in normal MMI where practical. Install/uninstall, full
  restore, firmware operations and vehicle coding are not normal-MMI menu actions.
- English documentation only. Ukrainian READMEs were intentionally removed.

## Resume here: workspace and source history

The complete workspace was moved from the Windows profile to these sibling folders:

| Location | Contents |
| --- | --- |
| `E:\Projects\mib2-carplay-rgi-altscreen` | This Git repository, ignored build outputs and package ZIPs |
| `E:\Projects\qnx65-armv7-toolchain` | QNX cross-toolchain source |
| `E:\Projects\carplay-build-inputs` | Private firmware, converter, Windows helpers, SD backups, build logs and records |

The old project folders under `C:\Users\Dayi` no longer exist. Historical logs can
still mention them; use the relocated paths, not those old log strings. Docker's
managed image storage was not moved.
The relocation was verified against hashes for every file, including hidden Git
data. If moving again, preserve the source-equivalent private folder permissions:
the destination drive initially inherited broader access. Robocopy's security-copy
attempt logged `ERROR 5` yet returned zero; inspect its log and verify contents,
not just its exit code. Do not loosen access on the shared workspace root.

- `origin`: `https://github.com/lindayi/mib2-carplay-rgi-altscreen.git`
- `upstream`: `https://github.com/Allemon/mib2-carplay-rgi-altscreen.git`
- `rgi-upstream`: `https://github.com/luka-dev/mib2q-carplay-rgi.git`
- Work so far is on `q5-mu1316-build-fixes`; check the actual branch, worktree and
  remotes before changing anything. Push only to the owner's fork, never upstream.
- Initial review compared Allemon `404ccc2` against RGI upstream `72d321c`:
  17 additional commits, including the AltScreen import at `acfb57f`.

Historical checkpoints, not instructions to reset to an old revision:

| Commit | Change |
| --- | --- |
| `cfae41e` | Configurable Java tools path, missing SD HMI-directory fix, initial owner report |
| `a13ac95` | Restore/cleanup/reconnect fixes and English-only READMEs |
| `5dae369` | Custom distance/road footer and renderer tests |
| `cb64eee` | Top-card default, explicit original-layout preset, upgrade preservation |
| `d3a5086` | Export-only diagnostics and conservative mirror recovery |
| `21d5b20` | Experimental native MMI menu and its runtime controls |

Read `E:\Projects\carplay-build-inputs\build-status.json` for the current built
package, input provenance, checksums and deployment record. It describes a built
artifact, which can lag a documentation-only Git commit. `build-logs\` contains
retained execution logs; `sd-deployment-*.json` records actual card preparation.
Earlier JSON reports are historical, not necessarily the current build.

## Vehicle evidence and the current open issue

### Returned repaired-menu trial: 2026-09-28

- The full export matches installed/card JAR pins `1799993018 324187` from
  `5a3a292`. The owner confirms menu stability, both animated mascots and full
  diagnostic export. This supersedes the unconfirmed status of those repairs,
  not the failed historical `c8ec3e7` trial. Private capture:
  `carplay-build-inputs\vehicle-menu-test-20260928-1913` (139 hash-verified files).
- Remaining owner observations: System status text overlaps the controls;
  permanent Saved text is confusing; mascots are tiny and almost entirely covered
  by Audi's full-width street/Trip bar; occasional Google Maps drift follows a
  View change and route stop/start recenters without reconnect.
- Wheel trace proves roller press/release/long states, rotation, right drawer and
  Back delivery. The owner observed short/long both toggling Road/Trip, rotation
  zooming, and right/Back controlling Audi's drawer, without affecting main CarPlay.
  Every recorded Fct54 drawer flag was false despite the visible drawer: these
  flags cannot establish exclusive ownership or dismissal. That card had no live
  VC panel; the later guarded integration below remains vehicle-unverified.
- New source changes below are not part of the tested card. Status text now has
  an explicit native-node clip; successful saves name the setting and expire after
  five seconds without erasing Last result, failures or the reconnect requirement.
  Double-size mascots reserve the lower fifth of the video plus four pixels as
  trial clearance. This is not a measured Audi-bar boundary or proven vehicle fix.

### Failed mascot/native-menu trial: 2026-09-27 evening

- The owner reported no mascot, `CONTROL_ERROR`, and the whole main MMI including
  physical buttons freezing; an attempted diagnostic export also froze. Do not
  describe the mascot release as vehicle-working or recommend another trial
  without repairing the failures below. Mascot Off does not fix the menu repaint bug.
- The returned card still matched all 60 `c8ec3e7` package hashes. No export folder
  or summary was present, but automatic boot logs captured the failures. A private
  hash-verified copy of 126 log files is in
  `carplay-build-inputs\vehicle-mascot-failure-20260927-2235`.
  Boot names/timestamps reset to 1970 and some names were reused across boots;
  compare content to the pre-vehicle manifest rather than sorting by timestamp.
- Java logged `Cannot publish map mascot control`: `File.renameTo()` failed for
  the `/tmp` control snapshot. The mirror worker ran on the HU but logged
  `CONTROL_STALE` and repeated `STATUS_WRITE_ERROR`, not a rendered mascot.
  `/tmp` is QNX shared-memory-backed, not Linux `/tmp`; host rename success is not
  proof that the HU supports the snapshot publication mechanism. The native
  status error lacks errno/stage detail, so do not invent its exact syscall cause.
- In boot `boot_19700101_000006_380970`, the J9 process logged eight
  `EGL_BAD_CONTEXT` events on TID 97, independently named `carplay-settings` by
  heartbeat logs, and a `ScreenWidget#paint` illegal-state error. This is strong
  evidence for the whole-MMI freeze, not merely a mirror-side drawing failure.
- Exact-stock bytecode confirms `triggerRepaint()` -> `doCheckedRepaint()` ->
  `RootWindowQNX.repaint()` -> `paintGUI()` is synchronous. Never call widget
  repaint/rebuild from settings/action workers. Dispatch coalesced updates onto
  Audi's HMI event thread, gate them by the connected menu generation, and keep
  widget-tree mutation out of an active paint traversal.
- The action helper logged exit 1, but its detailed output was logged at INFO and
  then deleted; normal verbosity did not retain it. Preserve bounded failure
  diagnostics at error severity and include them in export coverage.
- Existing host tests missed both the HU filesystem behavior and native graphics
  thread affinity. Add explicit regression coverage before rebuilding. The exact
  installed JAR build ID was not captured; installed mascot artifacts and execution
  are established, while the card package identity is independently verified.

### Platform-contract corrections after that trial

- The `5a3a292` trial above confirms the menu/mascot/export repairs for the reported
  use. The failed `c8ec3e7` card package must not be confused with corrected source.
- Native menu notifications use stock `RunnableEvent` and the HMI
  `EventDispatcher`; coalesce per connected-menu generation. Never rebuild in
  `managePaint`, or repaint during a row's failure callback. Queue cleanup too.
  Strict native-constructor tests alone do not establish thread affinity.
- The vehicle has an existing `/ramdisk` QNX4 RAM filesystem. Atomic volatile
  snapshots belong there: mascot control/status, supervisor owner, receiver
  session, renderer PID, mirror health and operation-lock boot token. Keep all
  producers, readers, status/export and cleanup paths aligned. Do not fall back
  to `/tmp` rename, delete-before-rename, persistent flash, or runtime remounts.
- Ordinary `/tmp` logs use bounded copy/truncate rotation, not rename; archive
  failures must leave an explicit diagnostic rather than allow unbounded growth.
  Persistent preferences and cover-art publication are on regular filesystems
  and retain their atomic rename contracts.
- Mascot status-write diagnostics include stage/errno/path and throttle identical
  failures to 30 seconds. A blocked control worker must lose its render-side
  lease after one second; a live video stream cannot keep a stale selection alive.
- Retain failed action output at ERROR and in a bounded private failure log,
  including timeouts/nonzero exits. Full export includes it and rotated logs;
  summary export must not leak that detail.
- Regression fixtures explicitly reject `/tmp` rename/move, exercise actual
  stock `RunnableEvent`s, stale queued generations and a stalled control reader.
  Linux `/tmp` success is not evidence of the QNX shared-memory contract.

As reported by the owner on **2026-09-27**:

- Navigation text and next-turn distance **work in the car**.
- Google Maps centering is **intermittent**: the first one or few navigations can
  be centered, but subsequent navigation can become off-center again. The exact
  trigger/pattern is unknown. Earlier feedback said Apple Maps did not have this
  offset problem.
- The last SD package prepared before this test was `cb64eee`. The installed build
  ID was not independently read back in the report. Do not attribute this feedback
  to the later native-menu/recovery builds.
- `21d5b20` was built and pushed, but was **not copied to the SD or tested in the
  car** during the initial implementation session. Native MMI drawing, knob/Back
  behavior, QNX settings persistence and wireless-adapter behavior remain unverified.

### Layout request lifetime correction: vehicle confirmation still required

The earlier implementation sent `CMD_ALT_UICTX`/AirPlay `showUI` only on a
video-ready edge. `AltScreenCluster` now observes live RGI independently of the
primary `RouteGuidance` listener, so map-only mode also gets route reapplication.
The existing screen worker drains coalesced video/route/View requests, gated by
receiver connection and module generation; callbacks do not read files or write
sockets. Keep this separate from `ScreenModule.setNavActive`: that is the BAP
presentation latch, not route identity, and can stay active across route changes.

Reapply the session's selected URL on entry into settled route states 1/6,
changed `route_generation` while settled (native debounce can hide route end),
or a changed reported source while settled. Do not treat distance, maneuver,
`visible_in_app` or duplicate replay as new routes. No successful request is
resent by a timer. Cache the URL per receiver connection so new preferences
still require reconnect, including across video/module restarts.

The repaired-menu export contains View-size edges without layout requests, matching
the owner's remaining drift trigger. Source now records actual Fct54 size changes
after the stock callback, ignores the initial/duplicate state, and waits 350 ms
after the latest edge before draining one coalesced request. This is an event
settling delay, not periodic reassertion. Its centering effect remains unverified.

`AltScreenLayoutLifecycleTest` uses real bus frames and delayed settings reads;
`AltScreenContextTest` exercises the actual context worker. Host verification
does not establish Google Maps behavior. The request-lifetime gap is real;
whether it caused the observed offset remains a **vehicle-unproven hypothesis**.
See `docs\input\steering-wheel.md` for the parked acceptance sequence and logging.

Compare a centered first route and off-center later route in the same connection:
app/iOS versions, route changes, video readiness, `showUI` events and raw cluster
video versus final crop. Capture while parked and retain private data locally.
Do not assume every app/route change tears down Type 111. Do not spam `showUI` on
a timer without evidence, or globally shift/crop the map and spoil Apple Maps.

The top preset requests
`maps:/car/instrumentcluster/map?maneuverLayout=topaligned`; it is not a direct
vehicle-marker positioning API. Preserve explicit right/no-ETA/original preferences.
The original AltScreen preset explicitly stores the base `/map` URL.

## Architecture and invariants

- Java HMI is long-lived; `CarplayBus` is the server on loopback **19810** and the
  native hook is its client. `RendererServer` is the Java server on **19800**;
  `maneuver_render` is a restartable native client.
- Renderer commands are fixed **48-byte packets**. Update both Java/C definitions
  and `scripts\check_local_protocols.py` together.
- `ScreenModule` is the sole cluster-context writer:
  **81** = custom maneuver/backings over CarPlay video,
  **80** = custom maneuver/backings over the Audi map,
  **74** = released/stock context. Do not add a competing `dmdt` context writer.
- Custom displayable **98** is shared with stock backings **101/102**. Restore the
  stock backing state when releasing control.
- Renderer source is **328x181**, including the final ECC row. Default popup crop
  is **59,27,210x153**; Sport in-tube can use **328x180**. Read live layout/stage
  through `ClusterLayerController`, not guessed offsets.
- VC FctID **54** is authoritative for stage and **44** for visibility. The Audi
  View button does not reliably emit every model event on Classic.
- The Sport map offset **-476,0** applies to map planes, **not** the KDK panel.
  See `docs\cluster\kdk-geometry.md`; do not compensate for stale state with offsets.
- `smartphone_integrator` owns the receiver PID/USB lifecycle. The wrapper must
  **exec** `dio_manager`, not leave it behind a shell parent. Renderer monitoring
  must not reset USB/OTG, kill the main receiver, or shorten SI's stock retry policy.
- Gate asynchronous work by connection/lifecycle generation. Do not perform
  blocking socket/file/process work on stock HMI/BAP callbacks.
- Accepted maneuver/CLEAR commands are ordering barriers. Coalesce progress/labels
  only within their maneuver; replay independent settings after reconnect. Only
  cache successful enqueue operations.

### Distance/road overlay

- Numeric distance is not part of the old 3D arrow renderer. Native BAP distance
  labels can be hidden by cockpit policy. The custom footer avoids that dependency.
- Use the current maneuver's distance and stock `BAPDistanceFormatter`, including
  imperial and quarter-mile encoding; do not substitute destination distance.
- Text priority is exit/signpost, next road, then maneuver name. Never substitute
  the current road for absent next-road text in this footer.
- `CMD_ROUTE_LABELS` is `0x0f`: one atomic distance/road snapshot. `CMD_DISPLAY_OPTIONS`
  is `0x10`: live distance/road/lane/progress visibility, large text and scrolling.
- UTF-8/NFC and grapheme-safe clipping happen before transport. Road text is bounded
  to **32 UTF-8 bytes**. Scrolling displays that bounded label, not an unlimited name.
- Embedded font: **999 DejaVu-derived glyphs** (Latin/Greek/Cyrillic/punctuation).
  Unsupported names are omitted with a diagnostic, not fabricated or transliterated.
  The richer native BAP text path remains intact.
- Retain `maneuver_render\LICENSE.DEJAVU` with renderer distributions. No runtime
  font library or extra font asset is needed on the HU.
- Reserve footer/lane space and invalidate framing caches when visibility/layout
  changes. For previews, invalidate maneuver masks after changing maneuver geometry;
  otherwise different test cases misleadingly show the previous arrow.

### Native MMI menu

- Entry: **NAV -> right drawer -> Navigation settings -> Carplay Altscreen**.
- The experimental VC panel now links through the mirror-only overlay library
  (`libcarplay_mascot.so`) and uses the shared SettingsRuntime save path. Per the
  owner's explicit choice, long-press is enabled by default, not session-opt-in.
  It requires large-map View, live CarPlay video and a BAP zoom detent after each
  session/drawer/tab takeover to establish eligibility. It is not an OEM drawer
  entry, supports no Audi-map-only surface, and is not vehicle-confirmed.
- `VcPanel` arbitrates press/hold/release, uses BAP scale as the sole movement
  source and restores parent focus. Ordinary Road/Trip now toggles on release.
  The opening hold/release must never select a row. Native drawer/tab/View,
  parking intent, lifecycle changes and renderer failure withdraw input ownership.
  Closing/hidden/unacknowledged panels must never accept a new setting change.
  Filter the owned wheel Back gesture's normalized main-CarPlay copy through
  release, even if Back closed the panel. Never suppress the centre-console Back.
- Panel control/status use strict `VCPANEL1` snapshots under `/ramdisk`, tied to
  mirror PID, opening epoch, revision and receiver connection. Control lasts
  600 ms; the native control-worker lease is at most 400 ms. A 300 ms presentation
  lease advances only after a successful real EGL swap. Select requires the
  exact displayed revision; a worker/status heartbeat is not presentation.
- Native CPU painting happens outside the render mutex; metadata exchange does
  no I/O. Repaint on a readiness re-entry even when epoch/revision are unchanged:
  otherwise a transparent initial buffer can be incorrectly acknowledged.
  Validate a fresh texture allocation before acknowledging changed UI pixels.
  Test the real 1440x455 EGL composite, not only model geometry or status strings.
- MU1316 raw MFW keys: roller 40, cancel 41, side-menu left/right 99/100.
  Raw state 2 is **double press**, 3 is long press (4/5 further long states).
  DSI observation and BAP drawer-open flags do not grant exclusive wheel input.
  Existing MFW DDS_SELECT suppression protects CarPlay only, not native VC UI.
  The local router gates project actions; native coexistence still requires
  parked verification. See `docs/hmi/vc-quick-settings.md`.
- Road/Trip and CarPlay zoom are project-controlled actions, not evidence that
  arbitrary VC-local input must first be globally grabbed. Raw state 1 calls
  `ScreenModule.onSteeringWheelOkPressed` -> `RouteGuidance.requestInfoModeToggle`;
  it previously fired before a long hold was recognized. The router now defers
  the short action until release and suppresses the opening gesture's release.
- Zoom enters our `ScreenCombiBAPListener.setMapScale`: it calls the CarPlay zoom
  handler and then stock zoom. Exact MU1316 stock code mutates map context 400476/
  auto-zoom before protected `updateMapScale()` reports the scale. A local menu
  router can bypass both zoom actions but retain the current-scale status reply.
  An exact-stock host regression exercises this seam without a MapManager,
  proving no stock zoom delegation while still sending the scale status.
  This is not yet a vehicle-tested menu interception.
  Use one movement source: raw encoder and BAP scale duplicate detents with
  opposite signs in the returned trace. Native drawer/focus and dismissal are
  separate remaining gates, not proof that these two project actions cannot be gated.
- Screen **400102**, factory method
  `NaviScreenBag8.mAPOPTNAVIGENERALSETTINGSMAIN`.
- `tools\PatchNavigationSettings.java` validates the exact private stock class hash,
  then makes four constructor/type substitutions. It does not rebuild a decompiled
  OEM factory. Wrong firmware/input must fail, not bypass the guard.
- Private references/decompiled classes live in
  `E:\Projects\carplay-build-inputs\mmi-reference`, outside this Git repository.
  The woven OEM class goes only into ignored build output.
- Reuse native widgets/fonts/focus and preserve OEM rows/model bindings. Hide those
  rows only while our submenu is open. Keep action labels and reconnect state truthful.
- Main-MMI `MenuModel.Page` separates titles/information from actionable rows.
  `CarplayMenuChrome` uses the existing title bar and a non-menu native label;
  restore the original title models/bounds on exit and hide/reuse the label on disconnect.
  Stock `AbstractWidget.propagateDisconnecting()` caches the child count; removing a
  sibling during teardown can throw or skip OEM widgets. Attach sibling widgets only
  in queued HMI refresh, never during stock tree connection.
- Stock `MultiLineLabelRendererHigh` defaults to no wrapping; select
  `StringUtility.WRAP_MODE_STANDARD`. It applies `maxLines` before `firstVisibleLine`.
  For complete status paging, keep `maxLines=0` and window the wrapped lines afterward;
  use native font/row measurements and an explicit native-node clip. Stock drawing
  tests each line's origin, not its full glyph bounds. Clip the label subtree to
  its own rectangle while inheriting ancestor clipping; a widget-height assertion
  alone did not catch the vehicle's System-status overflow.
- Native radio rows compare their model value with their widget ID, not Boolean 1.
  Preserve focus by action/setting identity, not by the switch's changing value.
  Presentation tests use simulated font metrics; they are not Audi graphics validation.
- Preferences: `/mnt/persist/var/app/carplay_altscreen/preferences`, strict complete
  versioned schema shared by `Preferences.java` and `carplay_settings.sh`.
  Save atomically with sync; invalid data takes the safe disabled path.
- New saves use format 3 (21 settings); accept complete formats 1 (16 settings)
  and 2 (20 settings) with explicit new defaults, including mascot Off, but never
  mixed/incomplete schemas. Reads do not migrate
  files on disk. Presets compute effective values without overwriting Custom;
  an individual appearance edit materializes the active preset into new Custom.
- The lower VC information bar is FctID 19, not the small renderer footer. Its
  current-road choice must not alter the footer's next-road-only policy. Timeouts
  apply to the nondefault page after successful publication; Keep survives View,
  but route/session boundaries reset to the configured default.
- Destination-zone data uses native/Java bit 22 and signed minutes; unknown is
  32767, never -1. Reset on route generation, destination replacement without a
  new zone, hard clear and disconnect. Only arrival wall clocks are shifted;
  duration stays UTC-based. The two-byte BE interpretation is host-tested, not
  phone-capture-confirmed; see `docs\rgd\rgd-tlv.md`.
- Manual layout reapply uses only the current connection's cached URL and the
  generation-checked bus queue. No preference lookup, shell helper, video restart
  or reconnect; queued is not phone-confirmed and does not prove recentering.
- Production preference singleton construction is non-I/O; the worker loads it.
  UI/model callbacks read snapshots and queue changes, never wait for persistence.
- Separate preference and action workers: an export/restart must not stall preference
  updates. Bound queue/process waits and surface failure. Busy includes queued work;
  dequeue and mark-busy happen under the same lock, without a false-idle gap.
- Master Off releases Java cockpit modules live and clears project preloads/skips
  the monitor on the **next CarPlay session**. It is not an uninstall.
- Audi-map mode's next session retains RGI but omits the AltScreen preload.
  Switching back from such a session needs reconnect.
- `/ramdisk/carplay_menu_session` and `/ramdisk/carplay_supervisor.owner` distinguish the
  requested preference from the actual receiver session. Do not report a stale
  process/session record as active.
- Reset preserves master enable state, Audi settings, pairing and installation.
- Summary export is separate from confirmed private full-log export. Actions are a
  fixed allowlist, not arbitrary shell commands. Missing runtime helpers are errors.
- Full design/limitations: `docs\hmi\carplay-settings.md`.

### Optional map mascots

- Optional map mascots belong inside displayable 3's existing video canvas, not
  in FctID 19's text-only bar or displayable 98's small maneuver panel. Do not
  invent displayable IDs. `libcarplay_mascot.so` interposes EGL only in the mirror
  sidecar; preserve its explicit isolation from receiver preloads.
- Mascot control is a worker-written `MASCOT2 <selection> <pid> <expiry-ms>` RAM
  lease, bound to the current mirror and video-ready marker. Asset/control/status
  file I/O stays on workers, not HMI or GL callbacks; no extra swaps/telemetry or stall masking.
  Keep Off a no-GL path, restore caller state/alpha, and clear cached GL names on
  context destruction (EGL handles can be reused).
- Build the bounded runtime atlas from private local GIFs with
  `tools/build_mascot_assets.py`; keep artwork/derived PNGs out of Git. Native
  build output and generated atlas are both required at SD staging, included in
  both checksum manifests and the installer's explicit mirror copy list.
  Retained local inputs are `carplay-build-inputs\mascot-assets\raccoon.gif` and
  `nian.gif`; source URLs/hashes are recorded in that private directory.
- `scripts/test_mascots.sh` covers real host GLES/state/failure/interposition
  behavior. View its previews. This is not verification of QNX EGL interposition,
  vehicle placement, or full binary-only mirror source.

### Diagnostics and recovery

- **EXPORT DIAGNOSTICS ONLY (no restore)** must write only a new SD export directory,
  not stop/restart/uninstall/remount/change the installed system.
- Export folder: `MMI-Cockpit-Carplay\logs\exports\export_<timestamp>_<pid>`.
  Selected summary fields are separate from raw `private\` log tails, capped at
  **256 KiB per file**. Raw logs are **not anonymized**. Never upload automatically.
- **STORE LOGS + RESTORE** really restores; do not use it to gather logs while
  preserving the installation.
- Mirror startup waits asynchronously for Java context readiness; it must not delay
  the main receiver. Initial delayed launch uses guarded same-session recovery so
  an earlier stream request is not missed.
- Freeze detection requires advancing input counters and calibrated presentation
  telemetry. No input, missing telemetry, or a stationary map is not proof of a hang.
- Identity-check a specific PID before signaling; bound `pidin`. Respect explicit
  stop, newer generations and retry limits. Exhaustion must withdraw stale readiness.
- Keep recovery preferences out of the initial-launch gate: disabling automatic
  recovery must not prevent normal startup.
- Restore launcher configuration and verify it **before deleting files it references**.
  Keep recovery scripts/pending state on cleanup failure. Sync/remount failures must
  not produce a successful installation result.

## Builds: use the working path, not already-failed approaches

### Private inputs

- The real stock `lsd.jxe` is required; the old AltScreen patch JAR is not a substitute.
- Existing durable input: `carplay-build-inputs\MU1316-stock\lsd.jxe` and
  `MU1316-P5145-stock.jar`. The conversion contained **30,543 classes**.
- Converter: `carplay-build-inputs\jxe2jar`. Build classpath uses its `out\` JAR,
  OSGi framework **1.10.0**, tracker **1.5.4**, and ASM **9.7** jars under
  `tools\uninline\lib`.
- Use the base conversion for executable ABI checks. The un-inlined `*-final.jar`
  pipeline is intended for decompilation and can alter synthetic/private accesses.
- Converter needs Python **3.10+** (`match` syntax); the local converter image uses
  **3.11** plus its declared `bitstring` dependency. QNX image Python 3.9 is insufficient.
- Do not change sparse-checkout configuration while conversion writes inside the
  checkout: it removed the ignored `out\` directory during an earlier conversion.
  Keep the durable converted JAR outside the converter checkout, then copy it to `out\`.
- If inputs are missing, ask for the owner's export. Existing Toolbox export:
  **MQBCoding -> Dump -> Dump lsd.jxe file to SD-card**, producing
  `H:\Dump\<firmware>\<unit-id>\LSD\lsd.jxe`. Do not select the import/link action.

### Verified Windows helpers

Run from PowerShell, using Git for Windows Bash:

```powershell
& 'C:\Program Files\Git\bin\bash.exe' 'E:\Projects\carplay-build-inputs\build-native-windows.sh'
& 'C:\Program Files\Git\bin\bash.exe' 'E:\Projects\carplay-build-inputs\build-java-sd-windows.sh'
```

The first builds native artifacts; the second builds Java and stages `build\sd`.
Neither writes the SD. Helpers resolve sibling workspace paths, so keep that layout.
They are machine-local helpers; on another machine use the repository scripts with
the documented toolchain/private inputs and recreate the Windows adapter if needed.

- Java source/target is **1.4**, class version **48.0**, built in JDK 8. Modern Java
  syntax belongs only in host tools/tests, not `java_patch`.
- QNX image is `qnx65-armv7-toolchain:8.5` (also tagged `:latest` locally), ARMv7
  QNX 6.5, EABI5/softfp. The C/C++-only image is enough; Go/Rust are unnecessary.
- The native helper compiles in a temporary **Linux Docker volume** and copies
  artifacts back. Old 32-bit QNX binutils fail on Windows bind-mount inode numbers:
  `Value too large for defined data type`. Do not disable ELF/export checks to bypass it.
- QNX toolchain source must retain Linux symlinks. For image builds on Windows,
  use a Git archive with `core.autocrlf=false`, not a checkout whose symlinks may be
  materialized as text. The build target is `base-env`, `BASE=base-8.5`.
- Persist `core.autocrlf=false` locally. New Windows-created shell files can still
  contain CRLF; normalize them before Docker execution/checksumming. A Bash error
  about an invalid `pipefail` option has been caused by CRLF, not unsupported Bash.
- `windows-docker.sh` disables MSYS argument rewriting and explicitly converts only
  bind-mount host paths. Blind automatic conversion corrupts container paths inside
  `bash -c`. With conversion disabled, use `cygpath` for native Git/Docker host paths.
- Retain checks for the hook export allowlist, no emutls/eager RGD initialization,
  ARM ELF and no C++ runtime dependency. Generated Screen/EGL/GLES import stubs are
  link-time aids only; never deploy them over the real vehicle libraries.
- QNX's GLES header sequence can leave compiler `stddef.h` unable to expose
  `size_t` again. The panel public header uses libc `stdlib.h` for that type;
  retain the cross-build check, rather than assuming host header ordering matches.
- `build_sd.sh` regenerates JAR size/POSIX cksum pins in INSTALL/START/STATUS and the
  package SHA256 manifest. Never swap a JAR alone into an already-staged package.
- Changing mirror scripts requires updating their release `SHA256SUMS` before SD
  staging. The 30-fps binary patcher checks exact upstream binary identities.

If the QNX image must be rebuilt, the tested Windows recipe is:

```powershell
$env:MSYS_NO_PATHCONV='1'
$env:MSYS2_ARG_CONV_EXCL='*'
Set-Location 'E:\Projects\qnx65-armv7-toolchain'
& 'C:\Program Files\Git\bin\bash.exe' -c 'set -o pipefail; git -c core.autocrlf=false archive HEAD | docker build --platform=linux/amd64 --target base-env --build-arg BASE=base-8.5 -t qnx65-armv7-toolchain:8.5 -t qnx65-armv7-toolchain:latest -'
```

## Test workflow and evidence limits

Run the smallest affected checks, then the relevant integration suites. Keep logs
in private `carplay-build-inputs\build-logs`; don't infer success from an output file.

Verified native-menu suite invocation on this machine:

```powershell
& 'C:\Program Files\Git\bin\bash.exe' -c 'set -e; source /e/Projects/carplay-build-inputs/windows-docker.sh; export TOOLS_DIR=/e/Projects/carplay-build-inputs/jxe2jar STOCK_JAR=MU1316-P5145-stock.jar; bash /e/Projects/mib2-carplay-rgi-altscreen/scripts/test_mmi_settings.sh'
& 'C:\Program Files\Git\bin\bash.exe' 'E:\Projects\carplay-build-inputs\verify-sd-windows.sh'
```

The `/e/...` paths above are Git Bash paths; host PowerShell paths use `E:\...`.

- `test_mmi_settings.sh`: preferences, exact native-factory hook equivalence,
  strict JVM verification/native constructors, workers/timeouts, receiver bypass,
  action allowlist, supervisor, lifecycle, then the full Java route/input/PDC suites.
- `test_altscreen_e2e.sh` / `tests\altscreen_e2e.sh`: exact-stock fixture install,
  upgrade preservation, strict START exit codes, restore fault injection, immutable
  export-only behavior, summary export, and preference-aware mirror recovery.
- `run_tests.sh`: host C/shell tests, including malformed inputs, transport and
  flat/tree installation. In the Linux test image put `/usr/bin` before QNX tools.
  A working host wrapper is `cc(){ command cc -D_GNU_SOURCE "$@" -ldl; }; export -f cc`
  before `bash scripts/run_tests.sh`. `zlib1g-dev` is included in the test image.
  Otherwise strict C99 hides clock APIs, old glibc needs explicit `libdl`, or the
  QNX assembler is accidentally invoked with `--64`.
- `test_route_labels.sh`: native sanitizers and real offscreen GLES rendering.
  PNGs are in `build\route-label-previews`; actually view them. They are not photos
  of the cockpit or native MMI page.
- `TOOLS_DIR`, `JAVA_HOME` and explicit `SKIP_BUILD=1` support testing a known built
  artifact in Docker. Do not silently test stale binaries.
- Use the stock linkage audit; don't let the host JDK silently stand in for APIs
  absent from the HU. Some isolated reconstructed-J9 probes require `-Xverify:none`;
  that does not justify disabling strict verification of our native-menu hook.
- The old e2e test falsely passed when a child printed `START=PASS` but the overall
  command failed. The current suite checks return codes and final integrated result,
  and explicitly stubs the unavailable ARM mirror only inside disposable fixtures.
- Native HMI constructors and model tests do not emulate full Audi graphics/input.
  No coverage percentage or exhaustive hardware-failure claim has been established.
  Never deliberately interrupt vehicle power to test fault recovery.

## SD preparation and safe deployment

- Write the SD only after an explicit request and after verifying the mounted card,
  package checksums and matching unit/export. Do not assume a remembered drive is it.
- Before each update, snapshot all data files, including hidden files, except
  Windows `System Volume Information`; hash-verify the copy. This is a file-level
  backup, not a sector image. Preserve earlier backup sets.
- Merge the **contents** of `build\sd` into the SD root. Replace matching package
  files, add new ones, and **delete nothing else**. Do not format or use mirror/delete
  synchronization. Preserve `MMI-Cockpit-Carplay\backup`, logs/state and `Dump`.
- Old Ukrainian README files may remain on an existing SD because the no-deletion
  rule takes precedence over source cleanup.
- Verify every copied file against the source, every untouched file against the
  pre-update manifest, and the deployed `SHA256SUMS-SD.txt`.
- Existing Toolbox upgrade: parked, stable power, iPhone disconnected ->
  **Update Toolbox -> INSTALL -> full MMI reboot -> START -> full MMI reboot**.
  Reconnect afterward. Stop on FAIL; never bypass firmware checks.
- Do not use the red software-update menu for this existing-Toolbox overlay upgrade.
  "RESTORE ORIGINAL" restores stock configuration; it is not a downgrade to the
  previous AltScreen release.
- Do not assume copying a package to SD means it was installed in the car. Track
  built, card-prepared, and owner-confirmed vehicle states separately.

## Git, privacy, and research

- Git Credential Manager device login worked when run in a normal PowerShell
  terminal: `git credential-manager github login --device --username lindayi`.
  The agent-runner attempt hid the prompt and expired. Do not ask for pasted tokens
  or print the credential returned by `git credential fill`.
- Push committed source to the owner's branch without force. Generated ZIPs,
  stock JXE/JARs, private decompilation, SD backups and raw logs are not source commits.
- Keep third-party licenses. Some imported AltScreen runtime components are
  binary-only; do not claim complete source audits or reproducibility for them.
- Online search summaries have fabricated issue descriptions and repeated obsolete
  claims that MHI2Q CarPlay cluster integration is impossible. Read actual repository
  code, issue bodies/comments and seller pages before relying on a claim.
- Commercial advertising is not independent verification, proof of code resale, or
  evidence that a feature works on this exact Q5.
- For Google Maps centering, a relevant corroborating owner report is upstream
  AltScreen issue 11, comment `5849186130`; the later intermittent behavior above is
  this owner's direct feedback. Keep that distinction in future reports.
