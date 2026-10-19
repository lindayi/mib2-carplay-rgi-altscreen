# Yuedizhibo RGI component baseline

The primary RGI source reference is
`yuedizhibo/MHI2Q-CarPlay-AltScreen` at
`141e7ed33775af02a5af0b8616ee3c6250eea997` (V3.8).
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

The pin file `Toolbox/carplay_alt_screen/UPSTREAM.json` accounts for all 109 files
in the five source trees, `hmi/resources`, and the runtime flag atlas from
`rgi_renderer/release`. It includes the Unicode license. It also lists excluded
vendor/stub/release trees and the separately tracked AltScreen native components.
At this checkpoint, 51 files match, 49 have local deltas, and 9 have explicit
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

- `CarPlayHook` runs the lifecycle actor, RGI discovery, service/device
  registration, retry and maintenance. `LifecycleFeatures` supplies the local
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

The local tests cover async lifecycle intent, duplicate/stale activation, late
navigation services and service replacement, Master Off, one context writer,
context readback, RGI window rebinding, OEM field restoration and wheel ownership.
The actor and context tests execute the adopted implementations, not a second
local controller. They do not prove vehicle behavior, generic geometry-probe
support on every firmware or FD-9 supervisor-lease parity. The planner merges
changes to the adopted controller paths and explicitly reports changes to
the remaining replaced files for manual review.

## Updating the baseline

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
