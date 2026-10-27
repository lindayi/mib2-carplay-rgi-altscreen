# Build and validate the Q5 package

This guide is for the personal **2020 Q5 / MHI2Q_US_AUG22_P5145 / MU1316** fork.
It builds the complete AltScreen + RGI package, not a second standalone installer.
For the on-car sequence, see [Installation](../../README.md#installation).

## Before building

| Input | Requirement |
| --- | --- |
| Docker | Linux containers. Java builds use JDK 8 and target Java 1.4 / class version 48. |
| Native toolchain | QNX 6.5 ARMv7 GCC 8.5 image, available as `qnx65-armv7-toolchain:8.5` and `:latest`. Both tags should identify the validated toolchain. |
| Original HMI | A base conversion of the exact unit's `lsd.jxe`, named `MU1316-P5145-stock.jar` under the tooling directory's `out/`. |
| Java dependencies | OSGi framework 1.10.0 and tracker 1.5.4 under `libs/`; ASM 9.7 and asm-tree 9.7 under `tools/uninline/lib/`. |
| Optional artwork | A locally generated pack selected consistently for Java and SD staging. The default build uses no character artwork. |

Use the [QNX toolchain project's build instructions](https://github.com/luka-dev/qnx65-armv7-toolchain).
The JXE converter requires Python 3.10+; its decompilation-oriented un-inlined JAR
is not the executable ABI reference. Keep the original export and base conversion.

Do not substitute a different firmware, an opaque prepatched HMI JAR or SDK files
for vehicle originals. The native-menu factory weave and performance patches have
exact-input guards; a mismatch is a reason to stop, not to disable validation.
Private firmware, decompilations, logs and artwork stay outside Git.

## Linux build

Run from this repository's root. Paths below are Bash examples; replace the
tooling path with your local directory.

```sh
export TOOLS_DIR=/absolute/path/to/jxe2jar
export STOCK_JAR=MU1316-P5145-stock.jar

docker build -t mib2-altscreen-native:local \
  -f altscreen_native/Dockerfile altscreen_native

# Stage a fresh package, without writing an SD or reusing old binaries.
unset SD SKIP_BUILD OUT
bash scripts/build_sd.sh
```

`build_sd.sh` builds Java, the native RGI hook, renderers, the performance helper,
and the independent source-native AltScreen pair. It then stages `build/sd`,
checks native source provenance, regenerates JAR pins, the final-script checksum
and `SHA256SUMS-SD.txt`.

The result is **a staged package, not an installed vehicle**. Do not swap an
individual JAR or binary into it afterward. Keep the matching originals and use
the [separate backup/copy/installation procedure](../../README.md#installation).
The script's advanced `SD` option is deliberately not used in this guide.

### Windows

Use Git for Windows Bash and Linux containers. The old QNX binutils cannot
reliably build on a Windows bind mount; native compilation needs a Linux Docker
volume, followed by copying the outputs back.

The [verified Windows helpers](../../AGENTS.md#verified-windows-helpers) and
[workspace/build agreements](../../AGENTS.md#builds-use-the-working-path-not-already-failed-approaches)
describe the working local adapter. Those helpers are machine-local files, not
programs shipped in this repository. Recreate the adapter on another machine;
do not disable ELF/export checks to work around filesystem failures.

### Optional mascot packs

Use `tools/prepare_mascot_pack.py`, then set the same `MASCOT_PACK` directory for
the Java build and SD staging. Catalog choices, shell bounds and native atlas
must agree. Keep third-party artwork private unless you have the necessary rights.

See [pack generation, compatibility and licensing](../hmi/carplay-settings.md#optional-map-mascots).
The [README image recipe](../../assets/readme/README.md) uses public synthetic
fixtures and does not require a mascot pack or private firmware.

## Validation

Run the smallest affected checks, followed by the relevant integration suite.
Do not assume a test used the newest binaries simply because `build/` exists.

| Area | Entry point |
| --- | --- |
| Native parsing, transport, protocol and shell behavior | `scripts/run_tests.sh` |
| Exact-stock Java/menu/settings/lifecycle/linkage | `scripts/test_mmi_settings.sh` |
| Source-native AltScreen lifecycle and real host GLES | `altscreen_native/tests/run_docker.sh` |
| Maneuver/label visual fixtures | `scripts/test_route_labels.sh` |
| Passive cards and speed badge visual fixtures | `map_cards/test.sh` inside the host GLES test container |
| VC panel visual fixtures | `scripts/test_vc_panel.sh` inside the host test container |
| Installation, rollback, export, rescue and mirror recovery | `scripts/test_altscreen_e2e.sh` with the inputs below |

The native/menu suites have host compiler, graphics and private-classpath
requirements. See the [tested environment and invocation details](../../AGENTS.md#test-workflow-and-evidence-limits).
Cross-build checks validate ARM ELF, exports and dependencies; they do not run
the resulting program on the head unit.

### Test the actual staged installer package

The installer fixture needs both the unit's original AltScreen backup
(`ORIGINAL/files`, firewall and boot backups) and its exported original MCD/config.
Neither is distributed in Git. Build the ksh-family test image first:

```sh
docker build -t mib2-installer-test:local -f tests/installer/Dockerfile .

INSTALLER_PACKAGE_MODE=staged \
INSTALLER_CASES=all \
SD_DIR="$PWD/build/sd" \
FIXTURE=/absolute/path/to/unit-backup \
PERFORMANCE_FIXTURE=/absolute/path/to/unit-mcd-export \
bash scripts/test_altscreen_e2e.sh
```

Staged mode checks the packaged scripts, pins and ARM payloads without source
overlays. The fixture compiles the real helper source for the host; it does
**not** execute the ARM helper or emulate QNX pdksh/graphics. It requires mksh
rather than silently falling back to Bash.

Host PASS, source-hash equality and an independent review are useful evidence.
They do not establish iOS app support, active daemon reload, physical cockpit
clearance, wireless-adapter behavior or on-car reliability. Those need a separate
parked/vehicle trial with clearly identified installed software.

## Source map

| Path | Role |
| --- | --- |
| `Toolbox/carplay_alt_screen/rgi_native/src` | GPL RGI hook and existing Identify/transport integration |
| `Toolbox/carplay_alt_screen/hmi/src` and `hmi/resources` | One Java implementation, local features and exact-MU1316 adapters |
| `Toolbox/carplay_alt_screen/rgi_renderer` | Maneuver renderer, scene engine and route-label footer |
| `altscreen_native` | Separately licensed AltScreen hook and mirror |
| `map_cards`, `vc_menu`, `mascot` | Mirror overlays and their public host fixtures |
| `deploy/performance` | Exact-file performance adapter and upstream transform oracle |
| `altscreen`, `deploy`, `scripts` | SD tree, runtime integration and build/test entry points |

For ownership, protocol and source-update contracts, use
[architecture](../architecture.md), the [upstream baseline](../upstream-baseline.md)
and [AGENTS.md](../../AGENTS.md).
