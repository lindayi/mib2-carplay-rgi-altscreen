# Upstream-first integration checklist

Scope: MHI2Q_US_AUG22_P5145 / MU1316 and iOS CarPlay. This checklist follows the
audit in `upstream-integration.md`. A checked item requires implementation and
the relevant evidence, not just imported source or a passing unrelated test.
Built, SD-prepared and vehicle-confirmed states remain separate.

The later [RGI source-baseline migration](upstream-baseline.md) moves the actual
build inputs to upstream component paths and the `routeguidance` package, and
adds a complete pinned source/adapter map plus tested three-way update planning.
The artifact and test record below remains the earlier `f8c78e6` correction;
it does not identify the later baseline build.

Current evidence: the corrected Java candidate, including context rebinding and
legacy-control isolation, passes the full exact-stock integration suite. Native
RGI, real GLES overlays, source-native UI fixtures and QNX cross-builds also pass.
The clean-commit `f8c78e6` package passes all transaction/fault and retained mirror
acceptance tests with its real payloads. All 75 package hashes pass, the shipping
JAR is unchanged after testing, and all 101 native source-manifest entries match
committed bytes. Source is pushed to the owner's integration branch; private build
records identify the separate corrected package. The held `490dce9` SD package
has not been replaced or approved. No SD was written and no vehicle was tested.

## Agreed behavior

- [x] Keep local features as additions to upstream implementation, not blanket
  replacements that silently omit upstream fixes.
- [x] Keep always-real arrows as the default; offer upstream approach mode.
- [x] Apply phone-card position live; require reconnect for connection-dependent
  mode/geometry changes. Never disconnect the phone silently.
- [x] Target one-step installation with integrated recovery and rescue.
- [x] Use one speed-limit display selector in both menus: Off, our camera-first
  badge, or the phone's own sign.
- [x] Our badge retains Audi-map fallback. Phone mode leaves the number and
  appearance to the navigation app. No numeric phone feed, camera injection into
  phone pixels, OCR, or replacement artwork is assumed.
- [x] Add the existing badge control to VC Map cards (`140c304`). The new
  three-way selector below supersedes that simple switch.

## Baseline and update path

- [x] Record canonical source pins and component path/package mappings.
- [x] Prefer upstream implementations with narrow, documented MU1316/local
  adapters. Make future upstream comparison reproducible.
- [x] Keep one native RGI producer, one Java publisher per output and one
  supervisor per process. Do not deploy a parallel upstream RGI stack.
- [x] Preserve license notices and GPL/source-available separation; do not
  import the incomplete upstream baseline JAR or private firmware into Git.
- [x] Keep unrelated audio upmix and other vehicle/firmware variants out of scope.

## RGI correctness and recovery

- [x] Accept a valid typed maneuver list when the optional maneuver count is zero.
  Cover BAP output, renderer updates, progress and the local footer.
- [x] Restore missing-junction fallback without accepting explicit unsupported
  junction values as valid turns.
- [x] Add upstream approach timing as a shared HUD/renderer policy, retaining the
  always-real default and arrival behavior.
- [x] Integrate compatible current Luka active-lane selection and cache
  eviction/cleanup. Cover precache-before-activation, reroute and missing events.
- [x] Restore data-bus bind retry and worker-failure recovery.
- [x] Restore renderer accept/write-worker retirement and restart.
- [x] Keep retries bounded/spaced, wakeups generation-safe, and failed work
  distinguishable from successful publication.
- [x] Retain native navigation-state tracking, accepted status replay, service
  rebinding and deferred cleanup without losing local subscribers.

## Information and speed-limit presentation

- [x] Add upstream Road ETA, preserving the suffix on every scrolling fragment
  and avoiding duplicate arrival clocks.
- [x] Preserve current/next-road choice, destination timezone, unit formatting,
  information-page defaults/timeouts and local footer independence.
- [x] Keep lower-bar output available without the custom maneuver renderer.
- [x] Adapt KOMO/model updates and their BAP side effects through coherent
  ownership, with failed-clear retry and old-owner fencing.
- [x] Migrate the speed-display preference safely; align Java and shell schemas.
- [x] Expose Off / our badge / phone sign identically in MMI and VC.
- [x] Publish our badge only in its selected mode; retain its numeric expiry,
  camera ambiguity rules, map fallback and visible-integer comparison.
- [x] Request the phone sign only in phone mode; request it hidden in other modes.
  Keep the phone request connection-cached and the reconnect notice truthful.
- [x] Preserve pending-choice behavior across route/View/manual reapply and
  module restart. Do not silently display both implementations during a switch.

## Native UI, contexts and geometry

- [x] Restore upstream UI submission/completion under a single command owner.
  Queue acceptance must not be treated as a phone acknowledgement.
- [x] Wire actual FULL/SMALL `updateViewArea` commands from accepted View state,
  including startup replay. Plane translation and `showUI` are not substitutes.
- [x] Restore session-safe `stopUI` before teardown.
- [x] Restore bounded no-frame/startup keyframe recovery. A request is not proof
  of recovery, and an idle map is not proof of a hang.
- [x] Implement actual upstream phone map/card URLs and card-sized safe areas.
- [x] Add live card position and separate phone ETA visibility while preserving
  connection-dependent mode changes and existing layout choices.
- [x] Retain coalesced route, View and main-MMI-return reapplication without
  periodic resends of successful UI requests.
- [x] Reconcile context roles before adapting map-preserving refresh/fallback.
  Upstream and local context 81 do not have the same meaning.
- [x] Preserve local cards, VC panel, speed badge, mascots, footer and input gates;
  keep the normal one-swap mirror presentation path.
- [x] Migrate or isolate legacy safe-area/shift overrides; prevent double Sport
  offsets and define their reset behavior.

## Timing, controls and diagnostics

- [x] Use 64-bit renderer elapsed arithmetic.
- [x] Bound renderer output independently of a supervisor that can exit first.
- [x] Audit elapsed timers separately from wall-clock timestamps and
  cross-process leases. Do not blindly substitute upstream's wall-delta clock.
- [x] Keep shared menu membership aligned and preserve focus after changes.
- [x] Consolidate duplicate START menu entries without breaking compatibility
  callers.
- [x] Route GEM and normal-menu persistence through a coherent update mechanism.
  GEM directs users to normal MMI; obsolete setting scripts fail without writing.
- [x] Add missing firewall-source diagnostics while keeping summaries free of
  private detail and full export explicit.
- [x] Investigate the theme issue behind Allemon PR14. Do not reuse its
  conflicting opcode or delayed captured-mode sends across sessions.
- [x] Mark any theme/app behavior that requires a vehicle capture as unproven,
  rather than inventing a fix or silently dropping the issue.

## Installation, rollback and rescue

- [x] Integrate one-step install only after the full transaction is coherent.
- [x] Validate firmware, inputs and runtime prerequisites before mutation.
- [x] Preserve complete pre-operation state and original backups separately.
- [x] Recover stale transactions safely and retain evidence/recovery files on
  incomplete cleanup.
- [x] Roll back partial installation, autostart and final-verification failures.
- [x] Integrate guarded manual rescue and reversible restore behavior.
- [x] Preserve preferences, pairing, diagnostics and unrelated startup content.
- [x] Verify applicable firewall variants and distinguish installed rules from
  live loaded rules.
- [x] Keep export-only immutable; retain truthful failure/progress reporting.
- [x] Update deployment instructions only when the replacement workflow passes
  its integration and fault tests.

## Review follow-up: cards, replay and presentation

- [x] Preserve early track/playback events until the matching session is ready.
- [x] Announce repeated artwork receipt without decoding or writing twice.
- [x] Clear absent native travel fields on destination/source replacement.
- [x] Preserve duration receipt time through native cache and Java restart.
- [x] Order deferred route-end publication before newer active evidence.
- [x] Redraw idle-video overlays through the existing mirror owner; keep source
  health counters separate and require successful swaps for cleanup/acknowledgement.
- [x] Add a pinned, SIL-OFL Chinese card fallback with Simplified Chinese
  letterforms, mixed-language scrolling and visible missing-glyph markers.
- [x] Flatten the left frame corner from 32.2 to 26.1 degrees based on the owner
  photo/native right-card reference, without moving its body or the RGI planes.
- [x] Exercise native/Java cross-layer replay, concurrent route publication,
  actual GLES pixels, CJK text, corner geometry, idle leases and QNX builds.
- [x] Stage and verify a clean-source review-fix package and retain private build
  records. Source `bab78ab` is staged at `build/review-fixes-sd`, with 76 verified
  manifest entries and JAR pins `1382479218 430207`. Exact-stock Java and all
  staged install/rollback/rescue/export/recovery fixtures passed. No SD write.
- [ ] Vehicle acceptance: the new font, slope, idle overlays and replay fixes
  still need parked checks. Do not reproduce a reboot while driving.

## Original integration verification and delivery

- [x] Add focused regressions that fail on the audited omissions.
- [x] Run exact-stock Java 1.4 compilation, linkage and strict native-menu/weave
  checks, plus relevant lifecycle, transport, RGI, settings and input suites.
- [x] Run native malformed-input/session/cache tests and relevant sanitizers.
- [x] Run and view real GLES previews for affected compositing/UI behavior.
- [x] Cross-build QNX artifacts with ELF/export/dependency checks intact.
- [x] Verify a source-matched staged package and install/upgrade/export/restore/
  rescue fault paths; do not test silently stale binaries.
- [x] Update source provenance, AGENTS.md, audit resolution status and private
  build records; commit and push to the owner's branch without force.
- [x] List remaining vehicle-only acceptance checks explicitly. No SD write,
  vehicle installation or vehicle validation is authorized by this checklist.
