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

The pin file `Toolbox/carplay_alt_screen/UPSTREAM.json` accounts for every file
in the five upstream source trees, including host tools and build files. At the
initial migration, 47 files match after CRLF/LF normalization, 47 have local
deltas, and 12 are explicitly replaced integration files. These counts are
source comparisons, not a feature-coverage percentage.

Existing native/Java RGI and renderer code already shared much of upstream's
implementation. Do not discard later fixes just to obtain identical files.
The retained deltas include the later Luka lane-event cache correction, local
destination-zone and duration-sample fields, fresh artwork receipt, display
preferences, custom footer, cards, settings and lifecycle fencing.
The status gate now starts from the upstream implementation and API, with
retirement, NavigationJobs ownership and VC-panel/speed-badge notifications added.

## Explicit integration adapters

The file-level map names replacements and their reasons. The principal contracts
are:

| Upstream integration | Local contract retained |
| --- | --- |
| `CarPlayHook` and legacy framework bus facade | Source-built MU1316 module lifecycle and one real bus; no opaque baseline JAR or parallel activation actor |
| Context controller, geometry snapshots and refresh weave | One context writer: 80 is Audi-map composition, 81 is CarPlay composition, 82 is map-preserving renderer refresh |
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
an upstream refresh. These exceptions mean this is a component baseline with
local patches, not a wholesale copy of the upstream application.

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
