# Yuedizhibo RGI component baseline

The primary RGI source reference is
`yuedizhibo/MHI2Q-CarPlay-AltScreen` at
`652448ddc1d5c9dd2f4a673b9950cf3c6a9195a4` (V3.8 Fix1).
This migration does not change the GitHub fork network, `main`, vehicle scope,
preferences, on-vehicle paths or installation procedure.

## What the build uses

The canonical GPL source directories now match upstream:

| Component | Source |
| --- | --- |
| Native metadata, artwork, bus and telephony | `Toolbox/carplay_alt_screen/rgi_native/src` |
| Java RGI and HMI integration | `Toolbox/carplay_alt_screen/hmi/src` |
| Java resources | `Toolbox/carplay_alt_screen/hmi/resources` |
| Renderer and scene engine | `Toolbox/carplay_alt_screen/rgi_renderer/src` |
| Renderer helpers and ABI headers | `Toolbox/carplay_alt_screen/rgi_renderer/common` and `toolchain` |

These are the build inputs, not a spare vendor tree. There is no second compiled
RGI stack. The Java package is upstream's `com.luka.carplay.routeguidance`; all
source callers, reflection/weave names, tests and resources use it.
The independent source-available AltScreen transport/mirror remains in
`altscreen_native`, under its existing license boundary.

The pin file `Toolbox/carplay_alt_screen/UPSTREAM.json` accounts for all 110 files
in the five source trees, `hmi/resources`, and the runtime flag atlas from
`rgi_renderer/release`. It includes the Unicode license. It also lists excluded
vendor/stub/release trees and the separately tracked AltScreen native components.
At this checkpoint, 51 files match, 50 have local deltas, and 9 have explicit
replacement mappings. Text comparisons normalize CRLF/LF; binary resource
comparisons use exact bytes. These counts are not a feature-coverage percentage.

Existing native/Java RGI and renderer code already shared much of upstream's
implementation. Do not discard later fixes just to obtain identical files.
The retained deltas include the later Luka lane-event cache correction, local
destination-zone and duration-sample fields, fresh artwork receipt, display
preferences, custom footer, cards, settings and lifecycle fencing.
The status gate now starts from the upstream implementation and API, with
retirement, NavigationJobs ownership and VC-panel/speed-badge notifications added.

## Running upstream control layer

The first review correctly found that moving paths and keeping the old
controllers did not meet the upstream-first goal. The next implementation
replaces that orchestration:

- `CarPlayHook` retains the queued lifecycle actor, separate resident monitor,
  retry worker and their serialized RGI work. It runs RGI discovery and
  service/device registration. `LifecycleFeatures` supplies the local
  ordered feature hooks. `CarPlayApp` is an API/build-ID facade; `RgdModule`
  and the old application actor are removed.
- `ClusterStateController` runs context acquisition, readback, reconciliation,
  renderer-window rebinding and the circuit breaker. `ScreenModule` supplies
  worker-free MU1316 video, placement, view and input hooks.
- `DisplayStateSnapshot` is the unmodified upstream diagnostic decoder.
  The source-built DisplayManager calls `configureRgiRefreshContext` before
  declaring its context table.

The context state machine has a necessary local extension: choose 80 for the
Audi map or 81 for live CarPlay video, rather than assuming one composite.
Video rebind uses 82; native-map rebind retains the existing 72 bounce.
Session generations and target checks reject stale completion and retry charges.
Only verified readback acknowledges a renderer binding. Stock release restores
the 30 Hz update rate and preserves a different current OEM context.

Lifecycle hooks retain Master Off without phone disconnection, always-on
transport, settings-backed guidance mode, G24 exclusion and local takeover.
They must not start another lifecycle actor or context loop.
The first lifecycle draft still folded monitor/retry into the old local polling
loop. The follow-up restores upstream's three-worker structure and method flow.
The initialization/retirement hooks remain substantial adaptations for ordered
local modules and retryable cleanup. OEM callbacks take only the publication
lock; monitor/retry work must reject pending or stale lifecycle generations.

The upstream geometry/ownership observer runs only with the existing
session-verified Verbose setting. Its diagnostic snapshots have separate
`/ramdisk/carplay_upstream_*` names. They never replace the native HMI lease or
authorize video/input ownership. Full private export includes them; summary
export does not include raw observer data. Atomic snapshots do not use `/tmp`
rename or delete-before-rename fallback.

An "adapted" count does not imply a small delta or automatic behavior parity.
The integrated checks and independent review still determine release readiness.

## Remaining integration boundaries

The file-level map names replacements and their reasons. The principal contracts
are:

| Upstream integration | Local contract retained |
| --- | --- |
| Legacy framework bus facade | One real always-on bus; no opaque baseline JAR |
| Binary refresh weave | Source-built DisplayManager calls the upstream controller's refresh-table hook |
| Wheel zoom and OEM startup module | Existing generation/PID-bound bus bridge and VC-input arbitration, not two senders |
| Native navigation-state patch tool | Exact-stock source weave and ABI guards |
| Renderer supervisor lease/clock entry points | Existing SI receiver/renderer supervision and monotonic renderer timing |

`LowerBarKomo` is also an explicit source-level MU1316 adapter. It retains
NavigationJobs dispatch, per-field native-value restoration, destination-timezone
formatting and stale-task fencing. Direct reflection calls to the generic stock
setters would reintroduce BAP side effects and thread-affinity failures.

The upstream FD-9/port-19801 supervisor lease is **not enabled**. The existing
supervisor is not claimed to be that implementation. Changing these integration
contracts requires a separate reviewed adaptation, not silent replacement during
an upstream refresh.
The upstream `/tmp/mmi-rgi.disabled` / `mmi-rgi.stopped` restore handshake is
also not used; the local transaction and SI cleanup retain their existing
contract. RGI start/stop follows the local physical-session and preference gates,
not upstream's `carplayPresent` / `carplayRunning` demand. Parking-related
inactive/unselected callbacks must not stop RGI. The device running flag is
observational, not a second lifecycle owner.

The local tests cover async lifecycle intent, duplicate/stale activation, late
navigation services and service replacement, Master Off, one context writer,
context readback, RGI window rebinding, OEM field restoration and wheel ownership.
The actor and context tests execute the adopted implementations, not a second
local controller. They do not prove vehicle behavior, generic geometry-probe
support on every firmware or FD-9 supervisor-lease parity. The planner merges
changes to the adopted controller paths and explicitly reports changes to
the remaining replaced files for manual review.

## Updating the baseline

### 2026-10-25 synchronization

Allemon `main` is unchanged at `1289981`. Allemon PR 7 remains included; PR 14
is unchanged at `3282773` and remains excluded because its night opcode collides
with telephony and its delayed sends lack session fencing.

| Yuedizhibo Fix1 change | Q5 integration |
| --- | --- |
| Duplicate steering-wheel phone action | Upstream policy through a hash-guarded MU1316 getter weave. It wraps the controller value read by stock, never overwrites an OEM assignment or restores an obsolete controller. Call suppression requires current receiver/call eligibility and no OEM emergency takeover. |
| UTC recent-call times | Use the HU's UTC-to-local conversion, then split in GMT. Missing conversion produces an explicit UTC warning. |
| Arrow fade and hide-before-move | Upstream ramp/fader retained; preserve Solid/Reduced backing preferences and stock restoration rather than forcing both backings transparent. A replaced DisplayManager cannot receive old fade ticks. |
| Sport map transition | Bounded black hold and fade in the mirror's existing loop. Observe the PID/epoch/lease-bound Java geometry; never move the plane from native code. Cached fade swaps use the overlay counter, not source-progress telemetry. |
| Phone-card SMALL geometry | Owner approved the normal SMALL `(490,72,460,378)` area and stock Sport `-476` pan for both profiles. The card FULL area stays `(300,72,840,378)`. |
| QNX FAT parent-entry errors | File-by-file transaction snapshots tolerate only the known parent-entry diagnostic. Hash/byte comparisons, empty directories, real I/O errors and special-node refusal remain enforced for install and restore. |
| Generic upstream config-layout fallback | Not copied: our installed runtime and unit-bound snapshot/restore contract are different. Exact original-backup checks remain; no global whitespace/comment/ID stripping is added to weaken identity checks. |
| Crash breadcrumbs | No new synchronous Java crash writer is present in this source release. Existing bounded Java/native logs and private export remain authoritative; no core dump payloads are collected. |

#### Review corrections and the returned installation failure

Independent review found three runtime defects in the initial Fix1 integration:
the upstream filter's non-atomic controller replacement could overwrite a stock
device change; cancelling a map veil could leave black pixels while video was
idle; a failed backing write could leave an incorrect maneuver-opacity cache.
The repairs use the exact-stock getter weave, preserve a fade through a
same-session geometry gap and require a successful cached redraw on cancellation,
and invalidate uncertain opacity state so a later hide cannot be skipped.

`tools/PatchPhoneKeyController.java` hash-guards the private MU1316
`DSISmartphoneManagerProxy` and intercepts only its `access$900` getter. The
original `access$902` writer and its volatile-field semantics remain unchanged.
The generated OEM class is class48 and stays in ignored build output. This is a
necessary local deviation from the upstream reflection-based install/re-arm;
additional comparisons around a reflective write would not fix that race.

The owner also returned the **58c7ea0 October 18 card** after INSTALL failed:
`pfctl: SIOCGIFMTU: No such device or address`, then
`FAIL: firewall syntax rejected: /mnt/system/etc/pf.ppp0.conf`.
The card's 76 package hashes match that build. Its transaction log records two
failed attempts and successful pre-operation rollback, not a successful new
vehicle installation. All firewall candidates were validated before publication,
so no candidate firewall variant had been published at the failure point.

The earlier installer parsed each complete variant with `pfctl -n -f`.
`-n` prevents loading, but ALTQ evaluation can still request an interface MTU.
The PPP variant contains an ALTQ definition for `ppp0`; requiring this during
offline installation was wrong. The returned capture does not include a
simultaneous interface inventory, so it does not independently prove which
interface existed at that instant.

The corrected installer validates only its self-contained literal filter rule.
It separately reconstructs and byte-compares the unchanged OEM remainder,
preserving CRLF and a missing final newline. It does not remove ALTQ, change
NAT/options, ignore validator failures, or activate an interface to pass.
Testing mode no longer bypasses this branch: a controlled validator models
the absent-PPP failure and rejects unexpected input. That fixture is not a
substitute for QNX execution of the parser.

Review also found an independent earlier native fallback bug: `pfctl -sr`
was reloaded with unrestricted `-f`, although the snapshot contains no NAT,
ALTQ or global options. The fallback now uses documented filter-only `-R -f`,
preserves scrub and queue references, and refuses a detected intervening reload
instead of submitting that stale snapshot. `-R` is still a replacement of the
filter domain, not a kernel-level atomic rule insertion; the project's lock
does not serialize an unrelated OEM loader. Do not claim that the extra read
eliminates every external-loader race or that host tests prove vehicle behavior.
See [QNX 6.5 pfctl options](https://www.qnx.com/developers/docs/6.5.0SP1.update/com.qnx.doc.neutrino_utilities/p/pfctl.html).

The Q7 fallback is retained in the upstream controller source, but the project
scope and platform gates are not broadened and there is no Q7 validation claim.
Yuedizhibo explicitly withdrew PR 62's faster startup/frame-reuse changes from
Fix1. The net source comparison, not the intermediate merge title or stale
BUILD_INFO fields, determines this update. No opaque upstream JAR or binary is
used to obtain a feature missing from the released source.

#### Separate Luka performance investigation

Luka was inspected at `a63d54741839c7d30d32dd4e149f693856bcb0db`.
These three new features are **not enabled by this sync**:
They are on hold at the owner's request; the read-only exporter below does not
implement or enable any of them.

| Feature | Evidence and remaining requirement |
| --- | --- |
| Maps 30-FPS PPID selector | Upstream's real wire-format tests pass with sanitizers, including existing-identity preservation and moving-buffer growth. Our older Identify dispatcher has no `hook_identify_grow` contract. A proper port must reconcile the GPL dispatcher with the existing AltScreen Identify hook; copying only the header would not be a safe integration. A phone policy target is not guaranteed on-car FPS. |
| Stock `libairplay.so` queue depth 4 to 2 | Both unique upstream signatures match the owner's retained P5145 library, at `0x8c694` and `0x8c6f4`. In-memory tests change exactly two bytes and reject a half-patched input. This is byte compatibility only. It affects the stock main-screen decoder, not our separate AltScreen decoder. Transactional install/restore integration and parked stability testing are still required before enabling it. |
| Stock `mcd` patch and detector priority | The retained unit export does not include `mcd` or `mcd.conf`. Upstream's P5087/P5092/K1004 equivalence claim is not exact P5145 evidence. Obtain the unit's originals privately before checking signatures, priority settings and rollback; do not distribute upstream stock binaries. |

The local compatibility checks do not alter the backed-up library, a vehicle,
or the SD. These findings are not blanket approval for stock-library or daemon
modifications. Other Luka changes target its different wireless, vehicle-data,
decoder, audio and M.I.B. installation stack, not components active in this
Q5 deployment.

#### Exporting the unit's originals

The versioned exporter is
`altscreen/Toolbox/scripts/export_mcd_originals.sh`. New staged packages include
it at `Toolbox/scripts/export_mcd_originals.sh`, covered by the SD manifest.
It is not part of the normal log export and is never run by INSTALL.

To use the existing Toolbox without reinstalling or updating it:

1. Back up the SD, then copy the exporter to its root as `test.sh`. Preserve an
   existing `test.sh` instead of silently replacing it.
2. Park with MMI on and put the Toolbox card in SD slot 1.
3. Select **Green menu -> MQBCoding -> Customization -> Various ->
   DEV: copy testfiles**. This existing action runs `/net/mmx/fs/sda0/test.sh`.
4. Wait for **MCD_EXPORT=PASS**, then return the card to the computer.
   On FAIL, retain the partial output and the message; do not use a restore action.

Each run creates a new `Dump/mcd-original-<date>-<pid>` directory. It contains
the unit's `mcd`, active `/etc/mcd.conf`, stored `/mnt/system/etc/mcd.conf`,
and source/export POSIX checksums. Source checksums before and after each copy
must agree with the copy. A read, copy, comparison or flush failure cannot
report PASS. The exporter writes only to the SD; if needed it remounts only
that SD writable. It does not alter installed files, restart a process, run
a patch, or request a reboot. Keep the export private.

Old process logs name the daemon and configuration but do not contain their
bytes. The SDK's `mcd` and `mcd.conf` are not unit backups and must not substitute
for this export. Host fixture success is not QNX/vehicle execution evidence.

### Procedure

Use a temporary branch from Q5. Fetch the public upstream before checking its
recorded Git blobs. The remote name is `altscreen-upstream`; its URL is
`https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen.git`.

```powershell
git fetch --no-tags altscreen-upstream main
python tools\yuedizhibo_baseline.py check
python tools\yuedizhibo_baseline.py plan --revision altscreen-upstream/main --output build\rgi-update-review
```

Use Python 3.9+ (`python3` on Linux) and Git. On this Windows host the system
Python command is a Store placeholder; the tool can run in a local Python/Git
container with the repository mounted, using container-native paths. Do not
install dependencies or access a vehicle merely to make an update plan.

The tool compares each changed file using **old upstream / current local /
new upstream**, rather than treating our last Q5 commit as the upstream base.
It preserves disjoint local changes and emits normal diff3 conflicts for
overlapping changes. It never edits current sources, deletes them, updates the
active pin, commits, pushes or touches an SD card.
Binary data updates retain exact bytes. An unchanged local binary can accept an
upstream candidate; divergent local/upstream binary edits require manual review
and produce no replacement file. Review resource and reader changes together.

`PLAN.json` lists candidate source writes, upstream removals and required review.
New upstream files and changes to replaced components require explicit
classification; they are not automatically ignored or installed. An exit status
of 2 means the plan needs review. An invalid/missing source pin or unsafe output
path fails with status 1. An existing output directory is never overwritten.
Even a conflict-free plan is not behavioral approval.

Review and copy only the intended candidate changes to the feature branch.
Resolve all review entries, account for new/removed files and update the pin from
`UPSTREAM.candidate.json` only with the accepted source changes. Then rerun
`check`, the affected behavioral tests and exact-stock/build checks. Use a PR
into Q5; do not force-push a baseline replacement onto Q5 or `main`.

## Verification boundaries

`tests/test_yuedizhibo_baseline.py` uses real temporary Git repositories to check
disjoint updates, conflicting edits, new upstream files, changed adapters,
upstream deletions, bad hashes/inventories and unsafe output paths. It is part of
`scripts/run_tests.sh`.

The existing exact-stock Java, native transport/lane, real GLES and QNX build
suites remain the behavioral checks. Package fixtures still stub ARM execution.
Neither source matching nor these host checks establish vehicle behavior.
Historical integration and vehicle evidence remains in
`upstream-integration.md` and the private build/deployment records.
