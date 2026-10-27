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

### 2026-10-27 compatible Luka ports

The owner approved including and enabling all three performance features in the
next candidate, with exact-file checks and full rollback/restore support. This
supersedes the earlier hold for the new candidate only. The reported working
`4f24de2e` vehicle build is unchanged until a separate SD preparation/installation.

The source reference is Luka
`a63d54741839c7d30d32dd4e149f693856bcb0db`; yuedizhibo's canonical RGI baseline
remains Fix1 `652448d`. These are additive ports, not a second RGI/AltScreen stack.

| Port | Integration |
| --- | --- |
| Maps30 PPID | Upstream selector in the existing GPL `altcluster` module. RGD and Maps30 share the bounded NmeArray growth helper. The wrapper's video-enabled map/card profile gates the addition; existing PPIDs pass through. The native UI/context/placement owners do not change. |
| Decoder queue depth | Exact P5145 full-file SHA-256 guards before and after the two-byte upstream transformation. Both video paths use the shared stock decoder; no runtime mprotect fallback is introduced. |
| MCD pacing and priority | Exact exported daemon/config hashes; five-byte and 13-line upstream transformations. Keep the device list, original modes and stock copies. No live daemon restart; activation is after the documented installation reboot. |
| Error names | Upstream CoreUtils table and suffix decoder supplement actual AirPlay zoom error numbers. Unknown codes remain numeric; no error value is reinterpreted as success. |
| iAP2 declarations | The active RGI companion repairs sent/received direction and declares complete groups used by our current modules. It preserves unrelated IDs, rejects duplicate lists/IDs and does not advertise an absent vehicle-status module. |
| UTF-8 builds | Explicit source encoding for the Java runtime and host weave tools. The generated classes/resources remain byte-identical after normalizing the inlined build-ID constants. |

`deploy/performance/UPSTREAM-NOTICE.txt` maps the imported sources.
The unchanged upstream `binpatch.py` remains the host transform oracle. The
Toolbox has no MicroPython runtime, so `firmware_patch.c` is a small native
adapter with exact stock/optimized SHA-256 guards and the same output bytes.
It does not embed or ship vehicle firmware. It creates a new file exclusively,
verifies its transformed bytes before writing and flushes before success.
The outer installer publishes that file by rename within its existing transaction.

#### Stock-file ownership and recovery

The sole INSTALL now checks all performance inputs before mutation. Original
performance files and modes live in `backup/performance-original` and are never
replaced by optimized files. The existing firmware/unit checks still apply.
The old blanket native-equality check has one narrow exception: the exact known
decoder stock/optimized pair, with valid original backups. Unrecognized or
partially changed binaries are still refused.

The transaction includes MCD/config in its pre-operation snapshot. A failed
install or upgrade restores that snapshot, not an arbitrary stock reset.
RESTORE ORIGINAL restores all three original files; stock rescue can repair a
damaged managed file and its undo restores the exact pre-rescue contents.
Unpublished performance files are retained in the transaction's residue on
rollback. The candidate must be restored with its matching/newer SD; an older
card has no knowledge of the approved binary transformations and may correctly
refuse them. Do not bypass an older card's original-file check.

The helper is read from SD, byte-checked into an executable `/ramdisk` cache and
self-tested before PREPARED. The installer does not depend on FAT execute bits.
Legacy restore and path-only rollback without performance originals do not need
the helper. Decoder publication uses `/mnt/app/eso/lib/libairplay.so` on the
remounted application filesystem; preflight requires it and `/eso/lib/libairplay.so`
to identify the same single-link inode. Unknown alias layouts fail before mutation.
Stock rescue preserves the recorded decoder mode, including non-0755 originals.

Because MCD participates in SD discovery, INSTALL also stages a persistent
unit-bound recovery kit under `/mnt/app/root/carplay-performance-rescue` before
any performance write. The explicit Green Menu **MMI-Cockpit-Carplay ->
RESCUE: restore performance originals (no SD)** action restores only these three
stock files and modes. It checks kit payloads, original hashes, firmware and unit
identity without an SD, preserves interrupted staging as evidence, and retains
the kit for retries. It does not restart MCD, remove CarPlay or complete a failed
installation. After PASS, perform one full MMI reboot. It still requires a working
OS/HMI/Green Menu; it is not protection against arbitrary boot failure. Retain the
matching SD for full restore and transaction recovery.

The kit and its swap directories participate in the ordinary transaction snapshot.
Full stock restore removes the kit; rollback and rescue undo restore its exact
pre-operation state. SD transactions and the internal action share a bounded-wait,
non-listening loopback token on TCP 19804. The descriptor survives exec and remains
held by surviving installer children after parent termination. No network service
is started. Re-entry checks the actual bound descriptor, not just its PID marker,
so unsupported shell inheritance fails before APPLY. STATUS distinguishes stored
priority configuration from `/etc/mcd.conf`;
neither a matching file nor a helper self-test proves the running daemon or
decoder has reloaded it.

These are installation-level, HU-wide changes. Turning Master Off still
withdraws CarPlay modules/extra video, but does not restore stock binaries.
There is no new reboot-dependent switch in the normal-MMI settings. Settings,
the VC long-press entry, high-resolution mascots and the one-installer UX remain.

#### Validation and remaining limits

The native adapter is compared byte-for-byte against upstream's transforms on
the owner's private inputs, including idempotence, corrupt/truncated/partial
inputs and refusing an existing output. Native selector tests cover its wire
bytes and failed/moving growth; the actual `altcluster` callback verifies the
session gate and preserved RGI data. A separate native wire-handoff fixture
checks PPID coexistence with observe/ThemeAssets framing without linking the
two production license domains together. It does not enable ThemeAssets.

Installer fixtures exercise success, repeat upgrade, unknown inputs, failure
after the first decoder write, failure after all performance changes, rollback
to an already optimized upgrade baseline, exact stock restoration, corruption
refusal, rescue and reversible undo. They use the actual helper source and
private exact-unit files; host execution is not on-vehicle ARM validation.
They require mksh (build `tests/installer/Dockerfile` as
`mib2-installer-test:local`), not a silent Bash fallback. Additional cases cover
non-0755 decoder modes, readable/non-executable helper payloads, alias refusal,
invalid helpers before PREPARED, legacy helper-free restoration, SD-unavailable
recovery, corrupt kit/wrong-unit refusal, repeated recovery and inherited lock
lifetime. This is ksh-family host coverage, not execution of the QNX shell.

Maps30 selects a phone policy, not a guaranteed panel rate. Reduced queues can
trade latency for less burst tolerance. MCD polling/priority can change media
discovery timing. Keep the known-working package and compare both displays,
reconnects, media insertion/removal and CPU behavior in a separate parked trial.

The open alternative layout/mode/context PRs are not merged wholesale: their
separate settings stores, long-roller gesture and context writers would replace
our supported UX/ownership. Their relevant existing capabilities are already
provided by our settings/accepted Fct54 path. This is not a claim of parity with
every proposed reroute/approach policy in unmerged PR32; those behavior changes
need a separate selective port, not silent replacement of our controller.

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
instead of submitting that stale snapshot. A failed second capture retains its
original error and diagnostic stage; only a successful but different snapshot
is reported as a concurrent reload. `-R` is still a replacement of the
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
| Stock `libairplay.so` queue depth 4 to 2 | Both unique upstream signatures match the owner's retained P5145 library, at `0x8c694` and `0x8c6f4`. In-memory tests change exactly two bytes and reject a half-patched input. This is byte compatibility only. The October 26 trace below corrects the earlier main-screen-only assessment: our stock-OMX cluster path uses this initializer too. Transactional install/restore integration and parked stability testing are still required. |
| Stock `mcd` patch and detector priority | The original investigation lacked the unit files. The October 26 export now supplies exact originals, with verified source/copy checksums and byte identity to upstream's stock references. See the updated compatibility findings below. Do not distribute those private binaries. |

The local compatibility checks do not alter the backed-up library, a vehicle,
or the SD. These findings are not blanket approval for stock-library or daemon
modifications. Other Luka changes target its different wireless, vehicle-data,
decoder, audio and M.I.B. installation stack, not components active in this
Q5 deployment.

#### 2026-10-26 feasibility update

The owner reports: **"The car is currently running our latest build fine."**
The last prepared card contains `4f24de2e`, merged into Q5 at `7fc31c21`.
This is owner confirmation of the reported use, not independent installed-build
readback, an exhaustive hardware trial, or validation of the proposed changes.
The failed October 18 installation and this later report are separate events.

Fresh fetches show no further main-branch commits since the October 25 review:
Allemon `1289981`, yuedizhibo `652448d`, and Luka `a63d5474`. The remaining work is
mainly the Luka features already investigated, not another unmerged Fix1 release.
No runtime source, installed files, SD contents or feature defaults were changed
in this feasibility pass.

| Candidate | Exact evidence | Integration assessment |
| --- | --- | --- |
| Maps 30-FPS profile selector | Upstream wire tests pass, including existing PPIDs, malformed input, size limits and a moved allocation. Our `rgd_resize_identify_buffer` body already matches upstream `hook_identify_grow` after the limit-constant rename. Exact retained NME Encode at `0x11ffe8` uses libc `realloc` and the same base/length/capacity layout. | Feasible without adding another AltScreen controller. Extract the shared helper and attach the upstream selector to the existing GPL Identify dispatch. Preserve RGI and the native ThemeAssets mutation, existing PPIDs and session gating. Combined-chain tests are still required. |
| Decoder queues 4 to 2 | Both exact P5145 signatures match. In-memory transformation changes two bytes; corrupted, ambiguous and partial inputs are rejected, and already-patched input is detected. | Feasible, but not a drop-in module toggle. The persistent library patch must be a declared, reversible installation change. It can affect both screen streams in this implementation. |
| MCD scan pacing | Exported daemon is 47,039 bytes, SHA-256 `f11d13ffec1322aefcfb431966180cff46c314320eeef7ed12470bfc441dd593`. Applying upstream's unique site at file offset `0x7730` changes five bytes and exactly reproduces its `mcd.custom` hash. | The missing-file blocker is resolved. Preserve the upstream transform, but integrate it into our unit-bound transaction and stock-rescue paths. This changes HU-wide media detection, not just CarPlay. |
| MCD detector priority | Active and stored configurations are identical, 2,750 bytes, SHA-256 `c653517d19107e6594c175d4a6553f1c57d81c1c6982e22b0f4964bf74272092`. All 13 `11,10` to `11,3` edits exactly reproduce the upstream reference, with other lines unchanged. | Technically compatible, but assess separately from scan pacing. Lower priority trades media-response scheduling for less competition with other work. Keep every device/watch section, including CD. |
| Named CoreUtils status diagnostics | Upstream's 76-entry status-decoder test passes, including unknown values and integer limits. | A small additive diagnostic port is reasonable. Keep numeric statuses and unknown fallback; it changes neither decoder scheduling nor phone policy. |
| iAP2 message-list repair | The exact retained P5145 config plus our current installer additions contains all relevant sent/received IDs in the proper lists. No missing or misdirected ID was found for currently enabled features. | Useful hardening for damaged/modified configs, not a demonstrated defect on this working unit. Port only groups used by our modules, with direction checks and original-config restoration; do not enable Luka's vehicle-status publisher merely by adding its IDs. |

The MCD candidate SHA-256 is
`e18f3055f633c99b3f10022c1f0a9c61a9e597d8b9a95831e5578a2e5c5387ee`;
the priority-config candidate is
`d0a33ae25acd0af1fd2f0fe84637c41d230d2b1a2933209e8bd800f0868a8033`.
These candidates were computed in memory, not installed or saved as deployable
stock binaries. Exact reverse edits restore the original bytes in memory; this
is **not** a test of the on-unit installer, reboot, rollback or media behavior.

**Decoder-scope correction.** Luka's performance report describes its own
separate AltScreen decoder. Our yuedizhibo-derived private111 backend instead
starts a stock screen stream and taps its decoded NV12. The local
`p1404_private111_backend.c` and `ScreenStreamStart` wrapper forward to stock.
In the exact retained library, `ScreenStreamCreate` at `0x85ce4` calls
`IVideoAbstraction::createVideoImpl` at `0x85f44`, which constructs
`dio::OmxVideoImpl`; its virtual initializer is `0x8c638`, containing both patch
sites. Thus "main screen only" is not a valid limitation for our integration.
Test both displays, reconnect and stream restart before claiming a benefit.

**Implementation prerequisites.** Our current `verify_backup_identity` requires
live native binaries to equal their original backups. `validate_snapshot` also
rejects changed native binaries, and the transaction's allowed paths do not
include `mcd` or its config. A direct patch would conflict with these protections.
Add explicit stock/known-patched identities, immutable originals, exact
pre-operation snapshots and matching install/restore/rescue/undo handling.
Unknown or partly changed firmware must still fail closed. Do not copy Luka's
installer's permissive warning-and-continue behavior over these checks.
Do not kill/restart MCD on the live unit just to apply a preference. It starts
at boot; any approved installation must use the documented reboot procedure.

**Limits and rollout.** Maps30 adds a 21-byte PPID selector, not a direct FPS
command. Its current upstream phone-asset row contains only `mapsRefreshRate=30`,
but future iOS assets and existing identities can change the result. Test the
complete RGI/PPID/ThemeAssets chain, including the native 1,024-byte mutable-frame
limit and allocation failures, rather than only the isolated selector.
MCD scan pacing replaces an event wait with a 200 ms periodic wait. It bounds
bursty scan frequency, but is not guaranteed to reduce idle work; scan time and
thread scheduling add to detection latency. The priority edit is another
independent variable. Upstream's short near-30-FPS result enabled all three
features under a different workload, so it is not controlled attribution.
Preserve this known-working baseline and compare changes one at a time.

Other released Luka changes concern its own wireless/audio/vehicle-data stack,
firmware coverage outside P5145 or withdrawn experiments. We already leave
stock NAVSD initialization untouched, consistent with its final removal of
the workaround. Do not reintroduce the intermediate suppression patch.

Open proposals were assessed separately from released fixes:

| Proposal | Assessment for this Q5 |
| --- | --- |
| `Allemon/mib2-carplay-rgi-altscreen#7` / `Allemon/mib2-carplay-rgi-altscreen#14` | Heads unchanged: checksum repair already incorporated; night command still conflicts with telephony and lacks our session contract. |
| `yuedizhibo/MHI2Q-CarPlay-AltScreen#77` | Optional SSH/internal-volume deployment, not a runtime fix. It adds a second deployment route and flash/staging policy; do not silently replace the working SD transaction. |
| `yuedizhibo/MHI2Q-CarPlay-AltScreen#76` | Another owner's P4246/MU1320 vehicle report, not evidence for this unit or a source fix. |
| `luka-dev/mib2q-carplay-rgi#57` | Map modes overlap ours. Its long-roller mode cycle conflicts with our VC settings entry, and its context table/persistence differ. Keep our normal-MMI/VC controls. |
| `luka-dev/mib2q-carplay-rgi#58` | Layout-file/Q7/view-source proposal overlaps our settings and Fct54-based handling. Do not add a competing geometry/settings writer or broaden vehicle scope. |
| `luka-dev/mib2q-carplay-rgi#51` | Build/test portability: explicit `javac -encoding UTF-8` is relevant hardening because the main build currently relies on its container default, unlike several focused tests. Host suites already run in Linux containers. Select relevant tooling changes rather than importing unrelated audio tests or suppressing warnings globally. |
| `luka-dev/mib2q-carplay-rgi#32` | A large alternate route/context design, not a small released fix. Its ScreenModule worker and 72-to-80 switching cannot replace our adopted controller wholesale. Specific reroute/approach tests may inform later ports, but full behavior parity was not established here. |
| `luka-dev/mib2q-carplay-rgi#33` | Android Auto/MU0918 is outside the agreed vehicle/phone scope. |

Private originals and disassembly remain under `carplay-build-inputs`.
`build-logs/upstream-feasibility-20261026-*` records the in-memory transforms,
upstream host probes, exact NME/decoder ABI trace and message-list comparison.
No new optimization or package has been built for deployment.

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
report PASS. The checksum command is explicitly chained to its directory change:
`set -e` alone does not protect a subshell used in an OR list. A failed `cd`
must not create a checksum file in the caller's directory.
The exporter writes only to the SD; if needed it remounts only
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
