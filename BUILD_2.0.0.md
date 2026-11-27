# Moto Flasher 2.0.0 — BUILD NOTE (Penumbra remake)

## What this is

The Penumbra remake Kyle ordered 2026-11-14 ("remake moto flasher
with new gui with it instead"). New line, version 2.0.0, package
`com.cronch.motoflasher` (same as 1.1.21, new versionCode). Built +
verified on disk only — nothing flashed. The 1.1.21 tree at
`~/workspace/moto-flasher-app/` is untouched (manifest still
versionCode 29 / 1.1.21; `MotoFlasher-1.1.21.apk` SHA still
`50b4099c17…`, re-verified after this build).

## Deliverable

- APK: `~/workspace/moto-flasher-remake/MotoFlasher-2.0.0.apk`
- **10,679,796 bytes**, SHA-256
  `b74d1ce5740c7cafec120eece6964d8035c8d004e2344a7cb40cdfc15ffddcac`
- versionCode **30**, versionName **2.0.0**, minSdk **28**, targetSdk **34**,
  only permission `WAKE_LOCK`, launchable `MainActivity`.
- Signing cert SHA-256
  `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (same keystore/cert as 1.1.21 — `motoflasher.keystore`, pass cronch123).
- Build: hand-rolled pipeline (`build_motoflasher.sh`: aapt2 + kotlinc
  2.4.20 + d8), same recipe as 1.1.x. kotlinc warnings only
  (pre-existing valproto ones + one Fastboot `!!` warning).

## Integration route: (a) — bundle the static Antumbra binary, drive its CLI

Penumbra/Antumbra **v2.0.0** (commit `f721520`), per
`~/workspace/penumbra-eval/EVAL.md`. Route (b) (Rust library/JNI) is
blocked in stock v2.0.0 (nusb `list_devices` cfg'd out on Android);
route (c) (Kotlin port) was rejected — the point of the remake is to
stop maintaining a port. The binary ships **unmodified** as
`assets/antumbra`:

- 6,304,752 bytes, SHA-256
  `11d2617dc97d4d6970a121b0cd9b7fa293bbb01a4941c98033ed6bbb460a9842`
  — verified at build time (the build script refuses a mismatch)
  AND re-verified after extracting it from the finished APK
  (same SHA; `file` confirms static aarch64 ELF, no INTERP).
- Runtime: extracted to `filesDir/penumbra/antumbra` on first use
  (size + SHA re-checked there; a mismatch refuses to run it), run
  as root via `su -c` from the fixed work dir `filesDir/penumbra`
  (the `.antumbra_state` file lives in the cwd and caches
  soc_id/meid/hw_code/hw_subcode/target_config between runs), with
  `HOME` set to that dir, backend `-b usb` (nusb/usbfs, same
  `/dev/bus/usb` path as the fastboot/rootusb helpers), `-v` always
  (pgpt/storage-info print via `info!` logs), DA + auth passed as
  `-d`/`-a`. Every invocation does its own full connect
  (handshake → send_auth → DA upload) inside the binary.

### Deliberate deviation from the eval: PID is NOT pinned

The eval recommended explicit `--vid/--pid`. The driver passes
`--vid 0x0E8D` only. Reason: the phone re-enumerates between
preloader (0x2000) and BROM (0x0003) every ~2.5 s, so a pinned PID
misses the other mode; VID-only grabs whichever MTK device is
present (normally one on the OTG bus) and the connect loop retries.
Documented here so it is a decision, not an oversight.

### Command map (PenumbraSession.kt)

| App op | Antumbra command |
|---|---|
| Connect probe / storage info | `storage-info` |
| Device partitions (GPT) | `pgpt` (parsed: `Name: … Addr: 0x… Size: 0x…`) |
| Read partition by name | `read-flash <name> <out>` |
| Read raw offset | `read-offset <addr> <len> <out>` |
| Read sectors | `read-offset` (addr/count × sector size; size from storage-info Block Size, else 512, logged) |
| Write partition by name | `write-flash <name> <file>` (DA routes the section, incl. BOOT1) |
| Write scatter entry | `write-offset <scatter addr> <len> <file>` (1.1.18 style; preloader rerouted to `write-flash` — write-offset only reaches the USER section) |
| Write raw offset | `write-offset <addr> <len> <file>` (addr 0 refused — that is the GPT) |
| Erase partition | `erase <name>` |
| Blankflash, all 11 selected | `scatter <scatter file>` (Flash Upgrade; DA decides what lands) — on failure, automatic fallback to per-entry write-offset |
| Blankflash, subset | per-entry scatter-address writes |
| GPT (optional, blankflash only) | `GptBuilder` bytes via `write-offset` (primary at 0x0, backup at end), verified by read-back re-parse (CRC) |
| Reset | `reboot fastboot` |

### Deleted (old mtkclient Kotlin port)

`brom/BromCrypto.kt`, `BromDa.kt`, `BromDaLoader.kt`,
`BromHandshake.kt`, `BromPreloader.kt`, `BromSessionImpl.kt`,
`BromSla.kt`, `BromUsb.kt`, `RootUsb.kt`. Dex check confirms none
of their classes/strings remain. Kept: `BromScatter.kt` (scatter
parser + plan), `BromGpt.kt` (GPT parse/build — `GptReader` takes
lambdas, no session dependency), `BromDownloads.kt`,
`BromLogFile.kt`, and a slimmed `BromApi.kt` (BromResult,
GptPartition, BromConstants, DocStatus).

### Hard rules carried over (verified in the new code)

- No gates anywhere in the BROM flow; blankflash/write buttons never
  greyed out; any DA/auth accepted, no checks (the handshake + DA
  upload inside Antumbra are the check).
- Battery check stays removed from the BROM half; the fastboot-half
  battery check is UNCHANGED (still pending Kyle's answer).
- Root re-checked FRESH on every write (`Penumbra.hasRootFresh`,
  1.1.19 lesson — never a cached verdict).
- 1.1.21 fastboot "waiting for any device" abort + guidance intact
  (Fastboot.kt untouched; string present in dex).
- Confirm before every write, wake lock during writes, stop on first
  failure, no auto-retry, loud failure with the real reason.
- Antumbra has no flash timeout, so the driver kills hung runs
  itself: no output for 120 s, or total over 300 s (600 s for
  scatter), destroys the child forcibly and reports it
  (watchdog thread + AtomicLong/AtomicBoolean).
- DA hw_subcode must equal 0x8A00 at runtime (Penumbra's
  `get_da_entry` match). The driver logs the subcode when the binary
  reports it; it does NOT block on it — Antumbra errors
  `NoCompatibleDa` itself, surfaced verbatim.
- Partitions come FROM the device (pgpt), never the scatter.
- Finished files → `/sdcard/Download/MotoFlasher/` (BromDownloads).
- Waiting states show 🐔, no generic spinners.
- No "made by Muse/AI" branding; real credits only.

## New GUI (2.0.0)

Rebuilt from scratch in `UiStyle.kt` + the onCreate/BromUi layouts
(neon LK theme):

- **Banner:** magenta→orange→yellow sun gradient, rainbow Exo 2
  title "MOTO FLASHER 2.0.0", cyan subtitle "Penumbra engine 🐔".
- **Background:** near-black violet `#0D0714`; **cards:** violet
  `#1A0F2E` with purple hairline border; **section titles:** rainbow
  Exo 2 with a cyan divider.
- **Font:** Exo 2 (variable font, OFL) bundled at
  `assets/fonts/Exo2.ttf` (303,940 B, downloaded from google/fonts),
  loaded via `Typeface.createFromAsset` with a system-sans fallback.
- **Buttons by role:** magenta = primary actions (flash/pick/boot),
  red = destructive (erase/format/unlock/relock/disable-verity),
  dark violet = queries (refresh/getvar/reboot/slot), cyan = BROM
  connect / blankflash runs.
- **Log:** dark violet background, green text, Exo 2, follow toggle.
- **Layout (MainActivity, same sections, new cards):** Status →
  Device → Flash one partition → Flash all → Reboot/slot/boot →
  Bootloader unlock → Custom AVB key → More fastboot → Flash
  partitions (one tap each) → TWRP → Format → vbmeta disabler →
  Val Protocol → **BROM / Penumbra** (BromUi) → Puller → Erase →
  Raw fastboot → Log → **About / Credits**.
- **BROM section (BromUi, same features, new cards):** 🐔 Test
  Antumbra binary → DA + auth + Connect → Detect BROM device →
  Check GPT (advisory) → Device partitions (from phone's GPT) →
  BROM read → BROM write → Raw flash → Blankflash → BROM reset.
  Every 1.1.21 feature maps across: device detect, getvar,
  single/flash-all, reboot/slot/boot/erase, raw fastboot console,
  BROM connect, DA+auth load, scatter-address writes
  (Nevada lk_a 0x4C400000), GPT read/optional write, root-USB
  pattern (usbfs via root — the binary does it itself), Downloads
  output. Fastboot/adb binaries and their flow are untouched —
  Penumbra replaces only the BROM/mtkclient half.
- Startup status line now also reports the Antumbra install state
  ("antumbra 2.0.0 ready (BROM engine)").

## AGPL / licensing

- Penumbra/Antumbra is AGPL-3.0-or-later. Bundling the unmodified
  binary makes the distributed app AGPL-3.0.
- Included: `LICENSE.penumbra` (full AGPL-3.0 text, also bundled in
  the APK as `assets/LICENSE.penumbra`) and `CREDITS.md` (also
  `assets/CREDITS.md`), naming Penumbra v2.0.0 / commit f721520 /
  author Shomy / https://github.com/shomykohai/penumbra, the binary
  SHA, and Kyle credited as https://github.com/crabcakes97. The
  app's About / Credits card in the GUI carries the same note.
- No Penumbra source is copied into the app's own sources; the
  binary is invoked as a child process. Corresponding Source for the
  binary is the upstream repo at tag v2.0.0.
- Exo 2 font is SIL OFL 1.1 (credited in CREDITS.md).

## Verification (all passed, on disk)

- Build exit 0 (kotlinc warnings only, pre-existing valproto ones).
- `apksigner verify --print-certs`: valid, cert SHA-256 `b6efa857…`
  (same as 1.1.21), DN `CN=Moto Flasher, OU=CRONCH, O=Coop`.
- `aapt2 dump badging`: package `com.cronch.motoflasher`,
  versionCode 30, versionName 2.0.0, minSdk 28, targetSdk 34,
  only `WAKE_LOCK`.
- APK contents (`unzip -l`): `assets/antumbra` 6,304,752 B,
  `assets/fonts/Exo2.ttf`, `assets/LICENSE.penumbra`,
  `assets/CREDITS.md`, `assets/fastboot`, `assets/adb`,
  `assets/rootusb`, `assets/capstone/*.so`, 58 libs, `classes.dex`.
- Bundled Antumbra extracted from the finished APK re-verified:
  SHA-256 `11d2617d…` (pinned release), static aarch64 ELF.
- Dex check (piped `dexdump`, authoritative): `Penumbra`,
  `PenumbraSession`, new `BromUi` present; old protocol classes
  absent; key strings present — "Test Antumbra binary (--version)",
  "MOTO FLASHER 2.0.0", "antumbra 2.0.0", "Root re-check passed",
  "waiting for any device" abort, "write-offset", "NoCompatibleDa",
  hw_subcode logging, scatter-fallback note. No old-protocol
  remnants (`BromHandshake`/`BromSessionImpl`/`mtkclient`/`da_gpt`:
  none).
- 1.1.21 tree untouched: manifest still versionCode 29, APK SHA
  still `50b4099c17…`.

## Open gaps — what Kyle still needs to test on his phone

1. **BLOCKING FIRST TEST — on-device execution of the bundled
   Antumbra binary is UNPROVEN.** The release binary would not run
   under qemu-user on the VM (silent exit 1, cause undetermined);
   that does not prove it broken, but nothing proves it runs on the
   S26 FE either. First test on the phone: tap **"🐔 Test Antumbra
   binary (--version)"** in the BROM section (or run
   `su -c <filesDir>/penumbra/antumbra --version` by hand). It must
   print `antumbra 2.0.0`. **If it fails, route (a) is dead and
   1.1.21 stays the fallback** — report it, do not attempt any BROM
   work with 2.0.0.
2. **No BROM flow has run on either phone with Penumbra.** After the
   `--version` test passes, the safe order is read-only-adjacent:
   Detect → Connect → `storage-info` → `pgpt` → `read-offset` on
   Nevada, then a `write-offset` of `lk_a` only. Keep 1.1.21 as the
   fallback until a Penumbra write is proven.
3. **DA hw_subcode must equal 0x8A00 at runtime.** Expected (same DA
   the current app uses), but if the phone reports a different
   subcode Antumbra errors `NoCompatibleDa` where 1.1.21 matched on
   (hwCode, hwVer, swVer). The app logs whatever subcode appears.
4. **Scatter is Flash-Upgrade-only.** If the DA rejects the
   dual-layout scatter, the app falls back to per-entry
   write-offset automatically and logs it — but that fallback path
   is also unproven on-device.
5. **Preloader single writes go through `write-flash`** (DA-routed
   to BOOT1) — assumed from Penumbra's partition-kind routing; the
   DA's actual routing for `preloader` on this phone is unproven.
6. **GPT write math** (total sectors from storage-info User size,
   backup at end of user area) is an assumption from the
   storage-info fields; the write refuses without storage-info and
   verifies by read-back, but no GPT write has run.
7. **pgpt/storage-info output parsing** is regex-based on the
   upstream `info!` formats (`Name: … Addr: 0x… Size: 0x…`,
   `Storage Type: …`, `Total Size: 0x…`). If a future Antumbra
   version changes those formats, pgpt/storage-info will fail
   loudly (empty parse) rather than silently — pinned to v2.0.0 so
   this should not drift.

## Files (remake dir)

- `MotoFlasher-2.0.0.apk` — deliverable
- `BUILD_2.0.0.md` — this note
- `build_motoflasher.sh` — hand build (verifies the Antumbra SHA pin)
- `AndroidManifest.xml` — versionCode 30 / 2.0.0
- `CREDITS.md`, `LICENSE.penumbra` — AGPL + credits (also in APK assets)
- `assets/antumbra` — pinned Penumbra v2.0.0 binary
- `assets/fonts/Exo2.ttf` — Exo 2 font
- `src/com/cronch/motoflasher/UiStyle.kt` — neon GUI (rewritten)
- `src/com/cronch/motoflasher/MainActivity.kt` — same logic, neon layout
- `src/com/cronch/motoflasher/brom/Penumbra.kt` — CLI driver (new)
- `src/com/cronch/motoflasher/brom/PenumbraSession.kt` — BROM ops (new)
- `src/com/cronch/motoflasher/brom/BromUi.kt` — BROM UI (rewritten)
- `src/com/cronch/motoflasher/brom/BromApi.kt` — slimmed shared types
- Kept from 1.1.21: `Fastboot.kt`, `Adb.kt`, `Flashfile.kt`,
  `LibLk.kt`, `Vbmeta.kt`, `CapstoneNative.kt`, `valproto/`,
  `brom/BromScatter.kt`, `brom/BromGpt.kt`, `brom/BromDownloads.kt`,
  `brom/BromLogFile.kt`, `toolchain/`, `motoflasher.keystore`,
  `SPEC.md` (1.1.x reference).
