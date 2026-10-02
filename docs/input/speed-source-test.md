# Speed/road-limit source test: MU1316 Q5

This is a **passive diagnostic**, not the speed badge. Nothing new is drawn over
the map and no overspeed warning is enabled. It measures the existing Audi data
before choosing a display source. Subscription-independent limits, camera
equipment, callback cadence and agreement with the cockpit are still unverified.

## Before testing

Use the newly built package containing `SpeedSourceDiagnostics`, not a previously
prepared card. Building a package does not update the SD or install it in the car.
Card preparation still needs explicit authorization, card identification, a
complete hash-verified backup and the documented non-destructive merge.

For the existing Toolbox installation: park with stable power, disconnect the
iPhone, then **Update Toolbox -> INSTALL -> full MMI reboot -> START -> full MMI
reboot**. Stop on FAIL. Do not use the red firmware-update menu. These reboots
install the patch; changing diagnostic logging afterward only needs a CarPlay
reconnect, not an MMI reboot.

Keep current Audi connect subscriptions, privacy choices, driver-assistance
settings, coding and units unchanged. Do not buy a subscription, enable a
previously disabled recognition feature or start native Audi route guidance for
this test. Record whether Audi already displays a speed-limit sign; absence is
a useful observation, not an instruction to enable it.

## Parked check (about 3 minutes)

1. Connect CarPlay. Open **NAV -> right drawer -> Navigation settings -> Carplay
   Altscreen -> Status & diagnostics**. Read **System status -> Build** and record
   it. With **Diagnostic logging = Normal**, the speed source probe should be
   **OFF** and existing CarPlay/menu/knob behavior should be unchanged.
2. Select **Diagnostic logging -> Verbose next session**, wait for the save,
   then disconnect/reconnect CarPlay yourself. Do not reboot the MMI. After about
   10 seconds, System status should show **Speed source probe: Subscribed N/3
   (not data proof)**. Record N. Fewer than three services is a diagnostic result,
   not proof of a broken car or a reason to change coding.
3. While still parked, leave the main MMI on CarPlay for 30 seconds with all Car
   menus closed. Note the cockpit speed/unit and any native sign. Then visit the
   ordinary Audi Car screen for 20 seconds, return to CarPlay for 30 seconds.
   Note the approximate times/order. This checks whether speed delivery depends
   on Car-menu visibility; the probe never forces that state.
4. While parked, check small-dial and large-dial View, then return to the preferred
   View. Start an ordinary Google Maps route while parked. Confirm existing map,
   music, audio, inputs and settings remain responsive. **No speed tile should
   appear.** Placement and dial clearance are not being tested yet.

If there is a freeze, a new error, abnormal audio/input behavior or broken
CarPlay, do not proceed to the driving segment. Capture diagnostics while
parked if the normal interface remains usable; do not use restore to collect
logs. Never interrupt vehicle power as a test.

## Short normal drive (5-10 minutes)

Drive lawfully on familiar roads, ideally passing two ordinary posted speed-limit
changes. Leave the MMI on CarPlay, with Car menus closed; logging is unattended.
The driver must not operate menus, take photographs or watch diagnostic status.
Do not deliberately speed, chase a warning, seek bad weather or make unusual
maneuvers. The future red-number rule will be tested synthetically.

A passenger may note a few normal steady-speed observations: approximate time,
the cockpit's current speed and unit, the posted limit and any Audi/Google limit
shown. A safely mounted recording arranged **before departure** is optional.
Keep any recording private. Do not rely on memory of a posted sign as proof of
the live source's correctness.

An unchanged road limit is useful too. We need to distinguish a change-driven
sign feed from a periodically refreshed speed feed; silence alone does not prove
a stale sign. Do not start a second native route or change subscription/privacy
settings midway through the test.

## Park, export and disable

1. Park safely, leave power on and keep the SD inserted. Export **Status &
   diagnostics -> Export full diagnostics to SD**, confirm the privacy warning,
   and wait for success. Do this promptly, before rebooting or shutting down:
   these are bounded volatile log tails, not a complete trip recorder.
2. Do **not** choose **STORE LOGS + RESTORE**. The GEM alternative is
   **EXPORT DIAGNOSTICS ONLY (no restore)**. A summary-only export omits the
   speed/sign samples and is insufficient for this investigation.
3. Set **Diagnostic logging -> Normal**, wait for the save and manually reconnect
   CarPlay. Confirm the probe becomes **OFF**. No full MMI reboot is needed.
   Previous samples remain only in the bounded logs/export; Normal stops new
   subscriptions and collection rather than deleting evidence.
4. Retain the new `MMI-Cockpit-Carplay/logs/exports/export_*` directory locally.
   Read `SUMMARY.txt`: check `JAR_MATCHES_CARD=YES` and the installed/card checksum
   pair. Provide the private export locally with the recorded build, test phase
   order, units, pre-existing sign-display availability and any observations.
   Do not post the raw export to GitHub; other logs can contain routes and IDs.

## What the diagnostic actually records

Only instance 0 is eligible. Missing/unknown instance properties are not guessed.
Each source has its own DSI listener; existing Audi observers are untouched:

| Source | Subscribed attributes | Captured fields |
| --- | --- | --- |
| `DSICarVehicleStates` | 14, 12 | `vehicleSpeed` and `realVehicleSpeed`: value, unit, validity, finite/nonnegative usable flag; both view-option state/reason pairs |
| `DSITrafficRegulation` | 3 | Highest-priority limit value/type/unit; priorities, all three signs, sources, additional/warning signs, variant and information enum |
| `DSICarDriverAssistance` | 35, 36, 38, 39, 40, 77, 78, 64 | Configuration/view options, system on/off, five signs with value/additional-sign enum, effective/unit/camera/database/fusion flags, area/roadwork flags and system messages |

`[SpeedProbe]` records go to the existing bounded `carplay_java.log` and `.1`;
the existing **full** export includes their final 256 KiB each. The probe does
not subscribe to position, road-name or route data. Conditional-sign text and
free-form DSI error strings are not copied. Other verbose logs are still private.

Speed snapshots are emitted at most once per two seconds. Sign/control
transitions have a 64-entry queue drained at most 16 entries per second;
overflow emits `dropped_transitions`. Ten-second snapshots retain callback counts
and ages even when values do not change. `count` includes every received callback;
`invalid` counts rejected callback status/null DTOs/async errors, not a verdict on
legal applicability. Speed's independent inner validity is in its `usable` flag.
`at_ms` is callback wall time, not export/write time. `age_ms=-1` means no callback
or the wall clock moved backward; this is not a freshness guarantee.

Rebinding resets the affected snapshots and advances the source `epoch`. Late
callbacks from old listeners, stop or disconnect are ignored. Invalid callbacks
replace the previous payload instead of silently retaining it. Source removal,
partial subscription failure, async errors and missing sources are reported.
Normal mode/unknown receiver state/Master Off/disconnect withdraw subscriptions.
Registration success and a worker heartbeat are never reported as valid data.

For interpretation: speed unit 0=km/h, 1=mph and value-state 1=valid. The limit
tuple is value/type/unit; type 1=conventional, 2=advisory, 0=undefined. Traffic
source 1=map database, 2=`VZO_OFFLINE`, 3=`VZO_ONLINE`; these enums are **not**
subscription-entitlement checks. TSD's sign enums differ from TrafficRegulation's.
A traffic-light green-wave recommendation is not a legal limit. No source is
chosen for the badge, no freshness timeout is inferred and no units are silently
converted by this diagnostic.

## Decision after the capture

We need evidence of speed availability with Car menus closed, its units and
relationship to the cockpit, plus valid/effective current-limit transitions and
their camera/map/online origin. We also need negative cases: no sign, invalid
status, service/session loss and conditional/advisory limits must not leave an
old number eligible for a warning. Host regressions cover these control paths,
not actual sensor behavior or source accuracy.

If only speed works, the design must not invent a limit. If limits are available
only from a connected source, report that dependency rather than claiming they
are subscription-free. Badge rendering, overspeed comparison and View-dependent
positioning follow only after the source-selection behavior is justified.

## October 1 returned capture

The installed/card checksum pair confirms `60eb66c`. The owner reports CarPlay
foreground with Car menus closed, metric units and no issues. The retained
diagnostic tail establishes live delivery from all three candidate services:
numeric speed, conventional map-derived limits and camera/database-marked TSD
signs. It does not independently establish agreement with the physical cockpit
number or posted signs.

Two source-policy cautions are now vehicle-observed. An accepted TrafficRegulation
callback can contain `limit=-1` and no sign; this must clear the prior limit even
though callback validity is accepted. TSD can later supply a camera-marked value
while TrafficRegulation remains unknown, but `signEffective` was false throughout
and the owner saw no native limit signs. Do not silently use TSD as an applicable
limit or bypass its unresolved flag. Additional-sign enum 256 is NA/initial/error,
not explicit empty 257.

Moving speed updates were frequent, but stationary speed gaps exceeded ten
seconds and unchanged map-limit intervals exceeded six minutes. Measure data
semantics separately from worker liveness; neither one shared short timeout nor
indefinite retention is justified. The export begins mid-log, so initial parked
steps and post-export disable behavior are not established by this capture.
