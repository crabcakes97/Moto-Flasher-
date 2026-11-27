# Moto Flasher 2.0.11 — build note

Built 2026-11-27. Kyle's orders from that day: verbose Val output in the
app log, full GUI revamp (phonk / cyberpunk bleak), harder text pass
(short, simple, human), "Experimental commands." + Nevada text removal,
emoji removal everywhere, credits with GitHub link, source zip for git.

## APK

- `MotoFlasher-2.0.11.apk` — 10,783,605 bytes
- SHA-256: `9cf0b8504eaafdb25f089ead92718169d6f1e60aca0c10e8ce75cecd9d843b30`
- versionCode 41 / versionName 2.0.11 / minSdk 28 / targetSdk 34
- Signing cert SHA-256: `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (same keystore as every 2.0.x build, pass `cronch123`)
- Build: `bash build_motoflasher.sh` (aapt2 + kotlinc 2.4.20 + d8, no Gradle).
  Clean compile, warnings only (pre-existing in valproto/Fastboot).

## What changed

### 1. Verbose Val patch output in the app log (the original 2.0.11 order)

valproto prints PC-style verbose output via `println` (~221 call sites).
In the app those went to logcat = invisible. Now `MainActivity.captureValOutput`
redirects `System.out`/`System.err` around the four Val calls
(`LkStaticAnalyzer.analyze`, `ValPresets.buildPatchOptions`,
`ValPatcher.runPatch`, `ValRepack.repack`) and streams every line live
into the app log, line-buffered, prefixed `VAL: ` (stdout) / `VAL ERR: `
(stderr). Streams restored in `finally`. `logLine` only posts to the UI
(verified: no `System.out` writes), so no feedback loop. Headline lines
from the analyzer's `summary.txt` (up to "Address mapping") are also
logged as `VAL:` lines.

### 2. GUI revamp — phonk / cyberpunk bleak (UiStyle.kt)

- Near-black base (`#07040C`), darker cards, blood-red danger, magenta
  primary, toxic-green log text.
- Buttons: sharp corners (2dp), dark fill (accent blended toward
  black), thin 1dp neon border, uppercase + letter-spaced labels,
  pressed = solid accent fill (invert). No more soft purple pills.
- Cards: sharp corners, thin border, darker fill.
- Section headings: solid neon (magenta), uppercase, letter-spaced.
  Rainbow killed everywhere except the logo banner gradient.
- Header: "MOTO FLASHER" shows ONCE (in the logo banner image). The
  duplicate rainbow title is gone. Version + root status are small
  right-aligned text. Bottom accent line is solid magenta.
- Tab bar: angular cells, active = dark fill + magenta border + solid
  magenta underline bar, uppercase labels.
- Dialogs + inputs: sharp corners, solid neon titles.
- All three themes (Neon/Ember/Frost) restyled in the new language;
  default Neon is the phonk look. Persisted theme names unchanged.
- 2.0.10 clipping fix preserved: 52dp min height, 8dp vertical button
  padding, 13sp gridButton with equal-height cells. Functionality
  identical — styling only.

### 3. Harder text pass (all tabs)

Every user-facing string cut down: short, simple, human. No
over-explaining, no "—" asides, no parentheticals unless data. Button
labels 1–3 words. Card text one short line. Examples:
- Val card: "Pulls the LK, patches it, flashes it back. Patched file is
  checked before flashing. CERT2 isn't checked in-app."
- "FRP presets. Tap one." / "Pull LK + analyze" / "Analyze + patch"
- Confirm dialogs: command + size + SHA kept, warnings one short line.
- Logs stay verbose (standing order). Data output untouched.
- September warning kept EXACTLY as Kyle worded it (Val tab only):
  "Can't unlock bootloader with Val on units with the September patch —
  it's patched."

### 4. "Experimental commands." + Nevada removal

- Extras description is now exactly: "Experimental commands."
- Swept all user-facing strings for "Nevada": only one existed (the old
  Extras description). Gone. Code comments + scatter data unchanged.
  DEX check: 0 "Nevada" strings.

### 5. Emoji removal (overrides the old chicken waiting-state rule)

All emoji gone from UI text, status lines, log lines, buttons, toasts,
waiting states. Chicken prefixes removed (BROM buttons, connect waits),
status "Starting…" plain, Val flow checkmarks replaced with the word
"done", risky-partition marker is now ASCII "!". Ellipsis "…" and
middle dot "·" kept (punctuation). DEX check: 0 chicken, 0 checkmarks.

### 6. Credits (More tab)

- "Made with love by crabcakes97"
- `github.com/crabcakes97` — tappable, opens https://github.com/crabcakes97
- Upstream credits kept short: Penumbra by Shomy (AGPL-3.0),
  liblk by R0rt1z2, Val Protocol by Maikyxd.

### 7. Spanish sweep (valproto)

The earlier sweep (99 replacements, pre-compaction) missed 27 exception
strings. Found by whole-word scan and translated (strings only):
"mensaje checkpoint demasiado largo" → "checkpoint message too long",
"no alineado" ×16 → "not aligned", "demasiado largo/corto" → "too
long/short", "usa --erase-partition <nombre>…" → "use … <name>…",
"el bloque firmado es mas corto…" → "signed block is shorter…", etc.
Files: ValAnalysis, ValEncoders, ValPatchOps, ValPatcher, ValRepack,
ValTokens. Re-scan: 0 Spanish hits. DEX check: 0 "mensaje" / "alineado"
/ "demasiado"; 17 "not aligned".

### NOT in this build (held by Kyle)

- In-app CERT2 verifier port (verify_mtk_image.py → ValVerify.kt):
  HELD until the app is proven working on-device. The Val card keeps
  one short honest line: "CERT2 isn't checked in-app."

## Verification (done on the VM)

- aapt2 badging: versionCode 41, versionName 2.0.11, sdkVersion 28,
  targetSdkVersion 34. Only WAKE_LOCK permission.
- apksigner: cert `b6efa857…` (continuity with all 2.0.x).
- DEX (dexdump -d, piped): "VAL: " ×2, "VAL ERR: " ×1,
  `captureValOutput` + `ValLineSink` present, "Experimental commands."
  ×1, "Made with love by crabcakes97" ×1, github link ×2, September
  warning ×1, chicken ×0, checkmark ×0, "Nevada" ×0, Spanish ×0.
- Prior APKs untouched: 2.0.8 `ab040af8…`, 2.0.9 `d8c4ec6b…`,
  2.0.10 `13a084ae…` — SHAs re-verified after build.

## On-device tests (Kyle)

1. Run a Val patch: full verbose output streams into the More tab log
   (`VAL:` lines) like the PC tool.
2. Header shows the name once (logo banner only); version + root small.
3. New phonk style on all tabs: angular buttons, sharp cards, solid
   neon headings, tab bar with magenta active cell.
4. No clipping anywhere (Extras OEM grid, More grids).
5. No emoji anywhere; Val flow line reads "1 pull done · 2 analyze …".
6. Credits: "Made with love by crabcakes97" + GitHub link opens browser.
7. Extras tab: description reads "Experimental commands." only.

## Source zip

`MotoFlasher-2.0.11-source.zip` — everything needed to rebuild:
`src/`, `res/`, `assets/`, `toolchain/`, `AndroidManifest.xml`,
`build_motoflasher.sh`, `BUILD_*.md`, `CREDITS.md`, `LICENSE.penumbra`,
`SPEC.md`, `LK_EXTRACTION_2.0.8.md`.
Excluded: `motoflasher.keystore` (build script generates a fresh one if
missing — note: a fresh keystore means a different signing cert),
`build/` intermediates, all APK outputs + `.idsig` files, `preview/`.

- Size: 62,586,034 bytes (3,428 files, 159,472,937 bytes uncompressed)
- SHA-256: `532bad86d748062053374d336d341715ad1e35d1c58d383af9dd430540e74913`
- Top level: `AndroidManifest.xml`, `BUILD_2.0.0.md` … `BUILD_2.0.11.md`,
  `CREDITS.md`, `LICENSE.penumbra`, `LK_EXTRACTION_2.0.8.md`, `SPEC.md`,
  `assets/`, `build_motoflasher.sh`, `res/`, `src/`, `toolchain/`.
- Verified: no keystore, no APKs in the zip. (The copy of this note
  inside the zip predates these two lines — a file can't contain its
  own zip's hash.)
