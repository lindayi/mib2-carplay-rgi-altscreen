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
