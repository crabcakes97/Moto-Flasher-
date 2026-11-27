# Moto Flasher 2.0.1 — BUILD NOTE (whole GUI)

## What this is

The whole-GUI redesign of the 2.0.0 Penumbra remake, ordered
2026-11-14 ("make it have a whole gui if u can what about logo?").
Version **2.0.1**, package `com.cronch.motoflasher`, same
Penumbra/Antumbra v2.0.0 engine and same logic as 2.0.0 — only the
GUI changed. Built + verified on disk only — nothing flashed.
The 1.1.21 tree at `~/workspace/moto-flasher-app/` is untouched
(manifest still versionCode 29 / 1.1.21; `MotoFlasher-1.1.21.apk`
SHA still `50b4099c17…`, re-verified after this build).
`MotoFlasher-2.0.0.apk` is untouched (SHA still `b74d1ce574…`,
re-verified after this build).

## Deliverable

- APK: `~/workspace/moto-flasher-remake/MotoFlasher-2.0.1.apk`
- **10,795,893 bytes**, SHA-256
  `0dc00413670e016328f2057f90fdf59bcac1a0f96bbc3a852ca641ba365dfa66`
- versionCode **31**, versionName **2.0.1**, minSdk **28**, targetSdk **34**,
  only permission `WAKE_LOCK`, launchable `MainActivity`.
- Signing cert SHA-256
  `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (same keystore/cert as 1.1.21 and 2.0.0 — `motoflasher.keystore`,
  pass cronch123, DN `CN=Moto Flasher, OU=CRONCH, O=Coop`).
- Build: hand-rolled pipeline (`build_motoflasher.sh`: aapt2 + kotlinc
  2.4.20 + d8), same recipe as 2.0.0, plus `aapt2 compile --dir res/`
  for the launcher icon. Kotlinc warnings only (pre-existing valproto
  ones, same as 2.0.0 — no new warnings from the GUI code).
- **Logo is WIRED IN, not pending.** The logo files had already landed
  when this build ran (sibling agent): `assets/logo/logo_banner.png`
  (720×180), `assets/logo/logo_master_1024.png`, and the full
  `res/mipmap-*/ic_launcher(.png)` + `ic_launcher_round` +
  `mipmap-anydpi-v26/ic_launcher(.xml)` adaptive icon +
  `res/drawable/ic_launcher_background.xml`. No re-package needed.
  The build script still carries the no-logo fallback (see below).

## The whole GUI (2.0.1) — what changed vs 2.0.0

2.0.0 was one long scrolling list of ~20 sections. 2.0.1 is a real
app shell. **Nothing was cut** — every 2.0.0 section is still there,
grouped into tabs. Only the layout moved; all logic functions are
byte-untouched (see "Files changed").

### App shell (`MainActivity.onCreate`, `UiStyle.appBar` / `tabBar`)

- **Top app bar** (always visible): the logo image
  (`assets/logo/logo_banner.png`, 208×52 dp, loaded at runtime via
  `UiStyle.logoBanner()` with a graceful fallback — if the asset is
  ever absent, a sun-gradient tile with 🐔 shows instead, never a
  crash) + rainbow Exo 2 "MOTO FLASHER" + "2.0.1 • Penumbra engine 🐔"
  + a live root status line ("Root: YES" green / "Root: NO" red),
  mirrored from the startup root check and from the fresh re-check
  in `writeGate()`. Cyan hairline under the bar.
- **Bottom tab bar** (always visible): five tabs — 🏠 Home, ⚡ Flash,
  🐔 BROM, 🔑 Val, ⋯ More. Selected tab = cyan label + magenta
  underline; unselected = dim. One `ScrollView` per tab, only the
  active one visible (`showTab()`); activity is not recreated on
  rotation (`configChanges` unchanged), so the tab survives.
- **Confirm dialogs** are now themed (`UiStyle.styledDialog`): dark
  violet card, rainbow title, Exo 2, magenta "Yes, do it" / ghost
  "Cancel". The stock `AlertDialog.Builder` in `confirm()` is gone.
  Every write still confirms first — same call sites, new skin.
- **Checkboxes** are now themed (`UiStyle.styledCheckBox`): light
  Exo 2 text, cyan tick — used in the flash-all list and in both BROM
  checkbox lists (device GPT, blankflash plan + Write GPT).
- **Log row** buttons (Copy / Clear / Follow) are now weight-1 row
  buttons — in 2.0.0 they were full-width buttons squeezed into a
  horizontal row. Log view styling itself is unchanged (dark violet
  bg, green text).
- `UiStyle.banner()` (the 2.0.0 sun-gradient banner) is retired from
  the layout but kept in `UiStyle` — the fallback tile uses
  `bannerBackground()`.

### Tab grouping (every section accounted for)

- **🏠 Home** — Status, Device.
- **⚡ Flash** — Flash one partition, **Erase (dangerous)** (moved
  here, directly under the Flash-one card, because it erases the
  partition named in that card's box — same `partInput`, same
  behavior), Flash all (pick what flashes), Flash partitions (one tap
  each), TWRP, Format (wipes the partition), vbmeta disabler.
- **🐔 BROM** — the whole `BromUi` section, unchanged in content:
  Test Antumbra binary, DA + auth + Connect, Detect BROM device,
  Check GPT (advisory), Device partitions (from the phone's GPT),
  BROM read, BROM write, Raw flash, Blankflash, BROM reset.
  One wording fix in the intro: "The fastboot half above is for live
  phones" → "(Home / Flash / More tabs)" — it is no longer above.
- **🔑 Val** — Val Protocol, Puller (needs temp root ON the target),
  Bootloader unlock, Custom AVB key. The Val body text now says the
  generated key lands in "the Bootloader unlock card below" (same
  tab, not "above"). The keygen still copies the key into the Unlock
  box; the Unlock box is now one card below instead of a long scroll
  away.
- **⋯ More** — Reboot / slot / boot, More fastboot, Raw fastboot,
  Log, About / Credits.
- `revealLog()` (called when a long BROM run starts) now switches to
  the More tab first, then scrolls the More tab to the log card —
  same once-per-run behavior, new destination. The log's own
  follow/scroll logic is untouched.

### Logo wiring

- Manifest: `android:icon="@mipmap/ic_launcher"` and
  `android:roundIcon="@mipmap/ic_launcher_round"` on `<application>`.
  `aapt2 dump badging` shows the adaptive icon at all densities
  (`application-icon-160/240/320/480/640/65534` all
  `res/mipmap-anydpi-v26/ic_launcher.xml`).
- Build script: `aapt2 compile --dir res/` → `compiled_res.zip`,
  passed to `aapt2 link` with `-R`. Assets also carry the logo:
  `assets/logo/logo_banner.png` + `assets/logo/logo_master_1024.png`
  (+ `assets/logo/LOGO.md`, the sibling's logo note, bundled as-is).
- **No-logo fallback, in the build script and in the app:** if
  `res/mipmap-xxxhdpi/ic_launcher.png` is absent at build time, the
  script strips the two icon attributes from a TEMP manifest copy
  and links without `-R` (the checked-in manifest keeps the attrs).
  That path was TESTED on a scratch link (stripped manifest: 0
  mipmap references, links clean, badging 31 / 2.0.1) — it works,
  it just was not the path this build took. At runtime,
  `UiStyle.logoBanner()` returns null and the app bar shows the
  gradient + 🐔 tile instead of the image.
- The sibling's design preview (`assets/logo/preview.png`, 143 KB)
  was moved to `preview/logo_preview.png` so it is not shipped
  inside the APK. The APK bundles only the two contract logo files
  (+ LOGO.md).

## Hard rules carried over (verified, not just claimed)

- No gates anywhere in the BROM flow; blankflash/write buttons never
  greyed out; any DA/auth accepted, no checks — `Penumbra.kt`,
  `PenumbraSession.kt`, `BromScatter.kt`, `BromGpt.kt`,
  `BromDownloads.kt`, `BromLogFile.kt`, `Fastboot.kt`, `Adb.kt`,
  `valproto/`, `LibLk.kt`, `Vbmeta.kt`, `Flashfile.kt`,
  `CapstoneNative.kt` are **untouched** (only `MainActivity.kt`
  layout/confirm/checkboxes, `BromUi.kt` checkboxes + one wording
  string, and `UiStyle.kt` changed).
- Fresh root re-check on every write (1.1.19) — `writeGate()` logic
  untouched; it now also updates the top-bar status line.
- 1.1.21 fastboot "waiting for any device" abort + guidance intact
  (string present in dex; `Fastboot.kt` untouched).
- Confirm before every write, wake lock during writes, stop on first
  failure, partitions from the device's own GPT, finished files →
  `/sdcard/Download/MotoFlasher/`, waiting states show 🐔, no "made
  by Muse/AI" branding, real credits only (Kyle as
  https://github.com/crabcakes97).
- AGPL-3.0: `LICENSE.penumbra` + `CREDITS.md` still in the APK
  assets and in the About / Credits card (now on the More tab, text
  updated to 2.0.1). The app bundles Antumbra, so it stays AGPL-3.0.
- Nothing flashed, nothing run on a phone. Build + verify on disk
  only.

## Verification (all passed, on disk)

- Build exit 0 (kotlinc warnings only, pre-existing valproto ones).
- `apksigner verify --print-certs`: valid, cert SHA-256 `b6efa857…`
  (same as 1.1.21 / 2.0.0), DN `CN=Moto Flasher, OU=CRONCH, O=Coop`.
- `aapt2 dump badging`: package `com.cronch.motoflasher`,
  versionCode 31, versionName 2.0.1, minSdk 28, targetSdk 34,
  only `WAKE_LOCK`, launchable `MainActivity`, application icon set
  at all densities.
- APK contents (`unzip -l`, 95 entries): `assets/antumbra`
  6,304,752 B, `assets/fonts/Exo2.ttf`, `assets/LICENSE.penumbra`,
  `assets/CREDITS.md`, `assets/fastboot`, `assets/adb`,
  `assets/rootusb`, `assets/capstone/*.so`, 58 libs,
  `assets/logo/logo_banner.png` (14,068 B) +
  `assets/logo/logo_master_1024.png` (50,241 B) +
  `assets/logo/LOGO.md`, `res/mipmap-*/ic_launcher(.png)` +
  `ic_launcher_round` + `mipmap-anydpi-v26/ic_launcher(.xml)` +
  `ic_launcher_round.xml` + `res/drawable/ic_launcher_background.xml`,
  `classes.dex` 3,209,524 B.
- Bundled Antumbra extracted from the finished APK re-verified:
  SHA-256 `11d2617dc97d4d6970a121b0cd9b7fa293bbb01a4941c98033ed6bbb460a9842`
  (pinned release), 6,304,752 B, static aarch64 ELF.
- Dex check (piped `dexdump`, authoritative): new `UiStyle$AppBar`,
  `UiStyle$TabBar`, `UiStyle.appBar`, `UiStyle.tabBar`,
  `UiStyle.styledDialog`, `UiStyle.styledCheckBox`,
  `UiStyle.logoBanner`, `MainActivity.showTab` present; strings
  "MOTO FLASHER", "2.0.1", "Penumbra engine", "logo/logo_banner.png",
  "Root: YES", "waiting for any device" present; "MOTO FLASHER
  2.0.0": **0 occurrences**; no old-protocol remnants
  (`BromHandshake`/`BromSessionImpl`/`BromCrypto`/`mtkclient`: none).
- No-logo fallback link tested on a scratch manifest (see above).
- 1.1.21 tree untouched: manifest still versionCode 29, APK SHA
  still `50b4099c17…`. 2.0.0 APK untouched: SHA still `b74d1ce574…`.

## Layout previews (not device screenshots)

No emulator exists on this VM (SDK has no emulator/system-images),
so the GUI was rendered as faithful layout previews with PIL, using
the same palette, Exo 2 font, logo files, sizes and spacing as the
code (2.5 px/dp, 1080×1920). Every image carries a footer strip:
"LAYOUT PREVIEW — not a device screenshot • Moto Flasher 2.0.1".
Each tab's preview shows the top of that tab's scroll (the real
screen scrolls; the cut card at the bottom signals it).

- `preview/preview_home.png` — Home tab (Status + Device).
- `preview/preview_flash.png` — Flash tab (Flash one, Erase, Flash all…).
- `preview/preview_brom.png` — BROM tab (Test Antumbra, Connect, GPT list).
- `preview/preview_val.png` — Val tab (Val Protocol, Puller, Unlock).
- `preview/preview_more.png` — More tab (Reboot, Raw fastboot, Log).
- `preview/preview_logo.png` — the logo: master 1024, banner, launcher
  icon + adaptive-icon wiring.
- `preview/logo_preview.png` — the logo designer's own preview render
  (moved out of `assets/logo/` so it is not shipped in the APK).
- Preview renderer: `/tmp/make_previews.py` (one-off; not part of the
  app). Emoji in the previews are real color-emoji glyphs
  (NotoColorEmoji, 109 px CBDT strike, scaled down); the "→" arrows
  are DejaVu — on Android, `→` renders through the system font
  fallback, so the app itself shows it the same way.

## Open gaps — what Kyle still needs to test on his phone

1. **The whole 2.0.1 GUI is unproven on a real device.** It compiles
   and the dex proves the new classes/strings are in, but no tab has
   ever been tapped: the tab switching, the app-bar logo load, the
   themed dialogs, and the themed checkboxes have never run on
   Android. First launch check: app opens on the Home tab, logo
   shows in the top bar, all five tabs switch, the log tab reveals
   on a BROM run.
2. **BLOCKING FIRST TEST (carried from 2.0.0) — on-device execution
   of the bundled Antumbra binary is UNPROVEN.** Tap "🐔 Test
   Antumbra binary (--version)" in the BROM tab. It must print
   `antumbra 2.0.0`. If it fails, route (a) is dead and 1.1.21 stays
   the fallback — report it, do not attempt any BROM work with
   2.0.1.
3. All 2.0.0 open gaps still apply unchanged (no BROM flow has run
   with Penumbra; DA hw_subcode must equal 0x8A00 at runtime;
   scatter is Flash-Upgrade-only with per-entry fallback; preloader
   single writes route via `write-flash`; GPT write math and
   pgpt/storage-info parsing are format-pinned assumptions). See
   `BUILD_2.0.0.md`.
4. The icon's adaptive XML references `@mipmap/ic_launcher_foreground`,
   which exists only in xxxhdpi (432×432). That resolves fine at
   link time and on device (one density's asset serves all), but if
   the logo is ever re-cut, the foreground must be re-cut at all
   densities or the single-density file kept.
5. `UiStyle.banner()` is now unused by the layout (kept for the
   fallback tile's `bannerBackground()`). Harmless dead code; remove
   in a later cleanup if wanted.

## Files (remake dir)

- `MotoFlasher-2.0.1.apk` — deliverable (NEW file; 2.0.0 kept)
- `BUILD_2.0.1.md` — this note
- `build_motoflasher.sh` — hand build (now compiles `res/` + the
  no-logo fallback; outputs 2.0.1)
- `AndroidManifest.xml` — versionCode 31 / 2.0.1, icon + roundIcon
- `res/` — launcher icon (mipmap-*/ic_launcher, adaptive XMLs,
  drawable background)
- `assets/logo/` — logo_banner.png, logo_master_1024.png, LOGO.md
- `preview/` — the six layout previews + logo_preview.png
- `src/com/cronch/motoflasher/UiStyle.kt` — app bar, tab bar,
  themed dialog, themed checkboxes, logo loader (extended)
- `src/com/cronch/motoflasher/MainActivity.kt` — tabbed shell
  (onCreate only; all logic functions untouched)
- `src/com/cronch/motoflasher/brom/BromUi.kt` — themed checkboxes +
  one wording fix (logic untouched)
- Kept from 2.0.0: everything else, including `MotoFlasher-2.0.0.apk`
  and `BUILD_2.0.0.md`.
