# Moto Flasher 2.0.9 — September warning moved Home → Val, wording changed (BUILD_2.0.9.md)

Kyle's order 2026-11-24 ~03:49 CST ("val moved there"), plus a wording
change ~03:53 CST. Built 2026-11-24 ~09:55 CST. 2.0.8 APK untouched.

## Deliverable

- **APK:** `MotoFlasher-2.0.9.apk` — **10,783,605 bytes**
- **SHA-256:** `d8c4ec6bc013dc4f48dfc41e5fc7122f7870d9f088313b6dfbfe6c191700faf9`
- **versionCode 39, versionName "2.0.9"**, package `com.cronch.motoflasher`
- **minSdk 28 / targetSdk 34**, only permission WAKE_LOCK
- **Signing cert SHA-256:** `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (identical to 2.0.5 / 2.0.6 / 2.0.7 / 2.0.8 — continuity confirmed by apksigner)
- **2.0.8 APK untouched:** SHA-256 still
  `ab040af8925a6a83a319c82923e46b12128f32665e29cf99834d6a579962ee22`
- Do NOT upload / deliver a link from this note — the main agent handles delivery.

## What changed (only this)

1. **September warning moved Home → Val.** The warning card is deleted
   from the Home tab and now sits at the top of the Val Protocol card
   (directly under the "Val Protocol" section title), same amber
   `UiStyle.Status.WAIT` style as before.
2. **Wording changed (Kyle's new exact text, 2026-11-24 ~03:53 CST):**
   > "Val protocol is patched in the September update — it won't work on September firmware."
   This replaces the old text ("September security patch won't work — it's
   patched.") in BOTH places it appears: Val tab and Extras tab. The old
   text appears nowhere in the app anymore (0 hits in source and DEX).
3. **Extras tab keeps the warning** (same new wording, same amber style).
4. **Version bump:** versionCode 38 → 39, versionName "2.0.8" → "2.0.9"
   (manifest + app-bar string, which now shows 2.0.9).
5. **Nothing else changed.** ADB tab, slot picker, Extras tab, themes,
   log auto-scroll, erase/format behavior, and all 2.0.8 features are
   byte-for-byte the same code as 2.0.8 apart from the items above.

## Build note

The first 2.0.9 build run was killed mid-compile (before signing, no APK
produced) when Kyle changed the wording — the shipped APK is from the
second run, built from source carrying the new text in both places.
Build: same hand build as 2.0.8 (aapt2 + kotlinc 2.4.20 + d8), via the
updated `build_motoflasher.sh` (output name 2.0.9).

## Verified on VM

- Build: aapt2 + kotlinc 2.4.20 + d8, clean (warnings only, all
  pre-existing unchecked casts / elvis-operator notes).
- apksigner verify: cert `b6efa857…`, package/version/sdk as above.
- aapt2 dump badging: versionCode 39, versionName 2.0.9, sdkVersion 28,
  targetSdkVersion 34, WAKE_LOCK only.
- DEX (dexdump, piped): 1,633 classes (same as 2.0.8). New warning string
  present; old warning string — 0 references. The new string is loaded
  once (`const-string v4, "Val protocol is patched…" // string@2918`,
  right after the "Val Protocol" section header) and reused: the Val
  call (`UiStyle.status` + `setStatus(WAIT)` + `addView` to the Val
  card) and the Extras call (`UiStyle.status` with the same register
  v4 + `setStatus(WAIT)`) both use it. This single-load pattern is
  D8's redundant-load elimination — 2.0.8 showed the identical pattern
  (one load, two call sites), and Kyle's 2.0.8 screenshot confirmed the
  warning rendered on both tabs there. Home has no reference to the
  string: the only load in the whole DEX sits in the Val-building
  region, so Home cannot display it.
- DEX size 3,249,964 B (2.0.8: 3,249,912 B; +52 B, consistent with the
  longer new string). APK size is coincidentally identical to 2.0.8
  (10,783,605 B) — the small compressed growth is absorbed by zipalign
  padding.
- 2.0.8 APK untouched (SHA `ab040af8…` before and after).

## Still needs Kyle's on-device test

- **Val tab:** the new warning ("Val protocol is patched in the
  September update — it won't work on September firmware.") visible at
  the top of the Val Protocol card, amber.
- **Home tab:** no September warning anymore.
- **Extras tab:** same new wording, amber, still there.
- **Regression:** app bar shows 2.0.9; ADB tab, slot picker, themes,
  log follow, erase, Extras commands all behave as in 2.0.8.
