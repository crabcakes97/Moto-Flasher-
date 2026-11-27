# Moto Flasher 2.0.2 — battery gate removed

**Date:** 2026-11-14
**Base:** 2.0.1 (whole GUI + logo, versionCode 31)
**Order:** Kyle, 2026-11-14 — "pos remove the battery limit" / "in moto flasher"
(the fastboot-half battery gate removal that was pending his answer; the
BROM half already had no battery check since 1.1.11)

## What changed

- `MainActivity.writeGate()`: the 30% host-battery gate is **gone**.
  Removed `val pct = batteryPct(); if (pct in 0..29) { toast("Host battery
  $pct% — charge to 30%+ before flashing"); logLine("BLOCKED: host battery
  $pct% (need 30%+)"); return false }`. Flashing is never blocked on host
  battery level anymore. The battery % is no longer read at all.
- `MainActivity.batteryPct()` deleted; `import android.os.BatteryManager`
  deleted (both dead after the gate removal).
- `BromApi.BromConstants.BATTERY_MIN_PERCENT` (30) deleted — it was the
  only reference in the tree (grep over src/ + manifest confirmed).
- Version bump: versionCode 31 → **32**, versionName 2.0.1 → **2.0.2**
  (AndroidManifest.xml + build script output filename).

## What did NOT change

- Everything else is identical to 2.0.1: the whole GUI (top app bar +
  bottom tab bar, all five tabs), the logo (launcher icon + in-app logo),
  the Penumbra integration (bundled Antumbra v2.0.0, Penumbra.kt /
  PenumbraSession.kt, same command map and timeouts).
- The other gates in `writeGate()` are intact: fastboot-binary-installed
  check, the 1.1.19 fresh root re-check, and the no-root block.
- All hard rules preserved: no BROM gates, fresh root re-check on every
  write, fastboot "waiting for any device" abort + guidance,
  confirm-before-write, stop-on-first-failure, Downloads output,
  🐔 waiting states, AGPL-3.0 (LICENSE.penumbra + CREDITS.md in the APK).

## Deliverable

- APK: `~/workspace/moto-flasher-remake/MotoFlasher-2.0.2.apk`
- **10,795,893 bytes** (coincidentally the same byte size as 2.0.1 —
  the removed strings/method are small vs. zip alignment padding;
  the SHA differs and the dex checks prove the code changed)
- SHA-256: `f07a08661c62a4b2af4a21e4683074bfbc4625e9ff9ac6c910e8ec229602d1d6`
- versionCode **32**, versionName **2.0.2**, package `com.cronch.motoflasher`
- minSdk **28**, targetSdk **34**, only permission `WAKE_LOCK`
- launchable `MainActivity`; launcher icon `res/mipmap-anydpi-v26/ic_launcher.xml`
  at all densities
- Signing cert SHA-256: `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (same keystore/cert as 1.1.21 / 2.0.0 / 2.0.1, DN `CN=Moto Flasher, OU=CRONCH, O=Coop`)
- Bundled Antumbra re-verified from the finished APK: 6,304,752 B,
  SHA `11d2617dc97d4d6970a121b0cd9b7fa293bbb01a4941c98033ed6bbb460a9842`
  (the pinned Penumbra v2.0.0 release)

## Verification (independent, by the coordinator — not just the builder's word)

- Build: `bash build_motoflasher.sh` exited 0 (~2 min). Only pre-existing
  kotlinc warnings. The script's own Antumbra SHA pin check passed.
- `apksigner verify --print-certs`: valid; cert SHA matches above.
- `aapt2 dump badging`: package, versionCode 32, versionName 2.0.2,
  sdkVersion 28, targetSdkVersion 34, only `WAKE_LOCK`, icon at all
  densities, launchable MainActivity.
- Dex/string proof the battery gate is GONE (on `classes.dex` from the
  finished APK, piped dexdump — 1,584 classes parsed, no false
  "0 classes" artifact):
  - "charge to 30": **0**
  - `batteryPct`: **0** (strings + piped dexdump name search)
  - `BATTERY_MIN_PERCENT`: **0**
  - `BatteryManager` / any `battery` substring: **0**
- Proof the other gates REMAIN:
  - "fastboot binary not installed": present
  - "flashing needs root": present (3 matching lines)
  - `hasRoot` and `writeGate` both present as method names in piped dexdump.
- Prior APKs untouched (SHAs re-checked): 2.0.1
  `0dc00413670e016328f2057f90fdf59bcac1a0f96bbc3a852ca641ba365dfa66`,
  2.0.0 `b74d1ce5740c7cafec120eece6964d8035c8d004e2344a7cb40cdfc15ffddcac`,
  1.1.21 `50b4099c17573f3959f0db8cd320439db8ba3f017fb41ee0c7807c65e4c1d1c7`.
- Sources: no `batteryPct`, `BATTERY_MIN_PERCENT`, `BatteryManager`,
  or "charge to 30"/"need 30" strings remain anywhere in src/ (only
  explanatory comments about the removal). One comment of mine was
  corrected during the edit: the battery % is NOT displayed anywhere
  else in the app (status shows Root/fastboot/adb/antumbra only).

## Open gaps (Kyle's on-phone test decides)

1. The 2.0.2 change is build- + dex-verified only — on-device proof
   that flashing now proceeds at low battery is Kyle's (install 2.0.2,
   flash with the host below 30% and confirm no block).
2. The 2.0.0 blocking first test still stands: tap "🐔 Test Antumbra
   binary (--version)" in the BROM tab — it must print `antumbra 2.0.0`.
   If it fails, the Penumbra route is dead and 1.1.21 stays the fallback.
3. All 2.0.0 / 2.0.1 open gaps carry over unchanged (see BUILD_2.0.0.md
   and BUILD_2.0.1.md).

## Note

- The battery gate was a host safety precaution (flashing under 30%
  host battery risks the host dying mid-flash). Kyle ordered it removed;
  the app no longer enforces it. The wake lock (30 min partial) is
  still taken during flash operations.
- No link was created and nothing was uploaded or flashed — the APK is
  on disk only, for the parent agent to deliver.
