---
title: Distance progress - BAP bargraph & arrow fill
tags: [rgd, bap, cluster, renderer, verified]
status: verified-source
sources:
  - code: java_patch/com/luka/carplay/rgd/BAPBridge.java
  - code: java_patch/com/luka/carplay/rgd/RendererServer.java
  - code: maneuver_render/protocol.h
  - code: maneuver_render/arrow_progress.h
  - test: tests/DistanceBargraphChainTest.java
reconciles:
  - docs/reference/NAVSD_FCTID_MATRIX.md
  - mib2q-carplay-rgi-next docs/cluster-and-rgi/DISTANCE_BARGRAPH.md
---

# Distance progress - BAP bargraph & arrow fill

The next-maneuver distance progress has two outputs from one `BAPBridge` decision: the BAP
**bargraph** in FctID 18 (drawn by the HUD / VC firmware) and the **fill of the maneuver arrow** in
`maneuver_render`. The renderer no longer draws a separate bargraph column. iOS sends distance only
every ~1-3 s, so the blink runs on its own 600 ms worker, shared by both outputs.

## 📋 Context

> [rgd-activation](rgd-activation.md) - active -> [bap-fctids](bap-fctids.md) - FctID 18 -> **bargraph-sync** - bar + arrow fill ->
> HUD / [maneuver-renderer](../cluster/maneuver-renderer.md)

## ⚙️ Denominator & fill

`percent = distM * 100 / denominator` while `0 < distM <= denominator` (then `bargraphOn = true`).

- **Prepare threshold** - city 1500 m, highway 3000 m; a step longer than 2000 m counts as highway.
- **Denominator** - the step length `mDistance[idx]` (0x5202 TLV 0x05), **capped at 15 %** of the
  prepare threshold (225 / 450 m). An unknown step length (`-1`/`0`, e.g. Google Maps never sends
  TLV 0x05) or one above the cap uses the cap, so the bar still runs.
- **Blink** - below 20 % the blink worker takes over.

| Constant | Value | Meaning |
|---|---:|---|
| `CITY_PREPARE_THRESHOLD_M` | 1500 m | prepare threshold, city |
| `HIGHWAY_PREPARE_THRESHOLD_M` | 3000 m | prepare threshold, highway |
| `HIGHWAY_STEP_THRESHOLD_M` | 2000 m | step length above which a maneuver is highway-class |
| `BARGRAPH_ACTION_PERCENT_OF_PREPARE` | 15 % | denominator cap (action zone) |
| `BARGRAPH_BLINK_PERCENT` | 20 % | blink below this fill |
| `ACTION_BLINK_INTERVAL_MS` | 600 ms | blink phase (50 % duty) |

## 🧭 One send, two outputs

`sendDistanceToManeuverRaw` formats the distance with the stock `BAPDistanceFormatter`, calls
`AppConnectorNavi.updateDistanceToNextManeuver(value, unit, bargraphOn, percent)`, and only after BAP
returned sends the renderer `CMD_PROGRESS` with `level = percent * 16 / 100`, `mode = bargraphOn`, and
an explicit progress state. It is skipped while `rendererManeuverPending`, so progress for a new
maneuver never lands on the old arrow. Lock order is `this -> distanceToManeuverLock -> renderer
queue`, never inverted.

## 🔄 Blink worker

`BAPActionBlink` (only while a route is active, invalidated by a `generation` counter on every
start/stop) toggles percent 100 <-> 0 every 600 ms while the fill is below 20 %. Both phases keep
`bargraphOn = true` and the positive distance. The renderer receives the **same phase**
(`PROGRESS_BLINK_HIGH` / `PROGRESS_BLINK_LOW`), so HUD bar and arrow blink in lock-step independent of
iAP2 cadence.

## ⚙️ Renderer side

`CMD_PROGRESS` (0x06): `payload[0]` remaining level 0-16 (16 = empty, 0 = full), `[1]` mode, `[2]`
state `0 off / 1 fill / 2 blink low / 3 blink high` - the state byte is honoured only with packet flag
**0x20**; without it mode 1 fills and anything else is off. The same fields ride on `CMD_MANEUVER`
(`MAN_FLAG_PROGRESS` 0x02 -> `[44..45]`, flag 0x20 -> state in `[42]`). The wire clock owns the blink;
`arrow_progress.h` only eases toward the received target (0.40 s retract). Blink low and off have exactly
zero brightness. See [maneuver-renderer](../cluster/maneuver-renderer.md).

## 🔧 FSG-sync workaround

`sendStatusIfChanged` drops a BAP update when nothing in `{FctID 23, 18, 49}` changed. On every
descriptor send `BAPBridge` toggles the ExitView variant EU <-> NAR on FctID 49 with `exitViewNum = 0`
(never 1 - that paints a highway-exit glyph over the icon), forcing a transmission so the FctSync
window closes. See [bap-fctids](bap-fctids.md).

## 💡 VC shows the bar, not the number

Host-proven (`tests/DistanceBargraphChainTest.java`, real `BAPBridge` + stock `AppConnectorNavi` +
serializer): the FctID 18 payload carries a valid distance **and** the bargraph together. Whether the VC
shows both is decided by the VC firmware, not the HU; the patched `ClusterService` only keeps the HU
Java distance model (64) valid alongside the bargraph model while CarPlay owns the cluster
(`!showBargraph || ScreenModule.isConnected()`). (!) What the installed VC displays with the bar on is
not re-verified on this branch.

The custom renderer now has an independent next-turn distance/road footer, fed by
the same stock distance formatter, so its number does not depend on the native
VC distance-widget visibility. See [route-label footer](../cluster/maneuver-renderer.md#route-label-footer).
The BAP bargraph and arrow-fill behavior described above is unchanged.
