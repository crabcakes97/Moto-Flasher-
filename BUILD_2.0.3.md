# Moto Flasher 2.0.3 — fixed Antumbra write-offset

**Date:** 2026-11-14
**Base:** 2.0.2 (versionCode 32)
**Order:** Kyle, 2026-11-14 — "fix the bug then tf" (after the Antumbra
`write-offset` Rust panic + the connect now failing on storage-info)

## What changed

### The bug (upstream Antumbra v2.0.0)
- `write-offset` crashed with a Rust panic (exit 101):
  `thread 'main' panicked at writeoffset.rs:58:46: called Option::unwrap()
  on a None value`. `dev.get_storage().unwrap()` — `get_storage()` is
  `None` when the DA's GetHwInfo (storage detection) fails. Same bug in
  `read-offset` (readoffset.rs:57). `storage-info` / `rpmb` already
  handled the `None` gracefully; the offset commands did not.
- Then storage detection failed SYSTEMATICALLY (2026-11-14 14:37-14:38):
  handshake + DA upload fine, but GetHwInfo gets no answer, so
  `storage-info` (connect) fails too — "Cannot retrieve storage
  information from the device." Same root cause, now blocking connect.

### The fix (in ~/workspace/penumbra-eval/src, v2.0.0 + patches)
1. `tui/src/cli/commands/device/writeoffset.rs` + `readoffset.rs`:
   `.unwrap()` replaced with a clean error ("Cannot retrieve storage
   information from the device.") — no more Rust panic.
   New `--section <name>` flag (e.g. `--section UFS-LUA2`,
   `EMMC-USER`). When given, storage detection is skipped entirely.
2. `tui/src/cli/helpers/mod.rs`: new `parse_section()` mapping section
   names to `PartitionKind` (UFS-LUA0..7, UFS-LUA0LUA1, EMMC-BOOT1/2,
   EMMC-RPMB/GP1..4/USER/END/BOOT1BOOT2).
3. `core/src/da/xml/storage.rs`: `detect_storage()` retries GetHwInfo
   up to 3 times (the DA can drop the first reply after DA upload).

### The aarch64 rebuild (the hard part)
- The musl cross gcc segfaults (exit 139) and its `ar` segfaults too —
  both broken in this VM. Rebuilt with:
  - NDK r28 clang 19 as linker + C compiler
    (`--target=aarch64-linux-musl --sysroot=<musl-cross>`)
  - NDK llvm-ar / llvm-ranlib as the archiver
  - A local `sys/cdefs.h` shim (~/workspace/penumbra-eval/
    musl-shim-include/) — musl lacks that glibc header and clang's
    bundled stdatomic.h includes it unconditionally.
  - Nightly Rust (the `acon` dependency needs `#![feature(...)]`;
    the stable `cargo check` fails on it).
- Result: static aarch64 binary, 6,213,928 B,
  SHA `c0257dd7104f9df66bb66371bcf1b44055165f20b4ea1504ee9a3c4bd164fb20`.
  Old v2.0.0 binary backed up at
  ~/workspace/penumbra-eval/antumbra-2.0.0-orig (SHA `11d2617d…`).

### The app (PenumbraSession.kt)
- Every `write-offset` / `read-offset` call now passes
  `--section <user section>` via a `userSection()` helper:
  UFS -> `ufs-lua2`, eMMC -> `emmc-user`, unknown -> `ufs-lua2`
  (Nevada/Kansas are UFS). So the write works even when storage-info
  fails. The old binary would reject the flag, so the app + binary
  ship together in 2.0.3.
- `Penumbra.kt` pin updated: EXPECTED_SHA256 + EXPECTED_SIZE now match
  the fixed binary; the stale-binary error message updated.

### Version
- versionCode 32 -> **33**, versionName 2.0.2 -> **2.0.3**
  (AndroidManifest.xml + build_motoflasher.sh output filename).
  Everything else identical to 2.0.2 (GUI, gates, root re-check,
  fastboot abort, AGPL-3.0).

## Deliverable

- APK: `~/workspace/moto-flasher-remake/MotoFlasher-2.0.3.apk`
- **10,767,221 bytes**
- SHA-256: `18168dfa846e69d8a992e8b36d389ad5987dce83bc29d3ccd7270564ab9a84f0`
- versionCode **33**, versionName **2.0.3**, package `com.cronch.motoflasher`
- minSdk **28**, targetSdk **34**, only permission `WAKE_LOCK`
- Signing cert SHA-256: `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (same keystore/cert as 1.1.21 / 2.0.0 / 2.0.1 / 2.0.2)

## Verification (independent — not just the builder's word)

- `cargo +nightly check -p antumbra` (host): PASSED (2m 34s, only
  pre-existing dead-code warnings).
- Release aarch64-musl build: `Finished release profile in 4m 20s`,
  no errors.
- Binary: ELF 64-bit LSB, ARM aarch64, statically linked, stripped,
  no INTERP/DYNAMIC; 6,213,928 B; SHA as above.
- Runs under qemu-aarch64-static: `--version` prints `antumbra 2.0.0`.
  `write-offset --help` and `read-offset --help` both show the new
  `--section <SECTION>` option ("If given, storage detection is skipped").
- Finished APK: `apksigner verify` valid; `aapt2 dump badging` confirms
  versionCode 33 / versionName 2.0.3 / sdkVersion 28 / targetSdk 34 /
  WAKE_LOCK only; cert SHA matches.
- Bundled Antumbra re-verified FROM the finished APK (zip extraction):
  6,213,928 B, SHA `c0257dd7…` — matches the fixed binary exactly.
- Dex (piped dexdump + strings): `userSection` method present,
  `ufs-lua2` and `--section` strings present — the app passes the flag.
- Prior APKs untouched (SHAs re-checked at build time): 2.0.2
  `f07a08661c62…`, 2.0.1 `0dc00413670e…`, 2.0.0 `b74d1ce574…`,
  1.1.21 `50b4099c17…`.

## What is NOT proven yet (Kyle's, on the phone)

- The `write-offset` of lk_a at 0x4C400000 actually writing (the fix
  removes the crash + the storage-detection dependency, but the DA still
  has to accept the WRITE-FLASH command at that address).
- `storage-info` (connect) succeeding again — the GetHwInfo retry helps
  transient drops, not a DA that has stopped answering it. The
  write-offset path no longer needs it, but connect still does.
- The preloader "Security deny" is unchanged (the DA refuses preloader
  writes; blankflash must exclude preloader / BOOT1).

## First test for Kyle

1. Install 2.0.3 (replaces 2.0.2; same package, same cert).
2. BROM tab: **Connect** (storage-info) — note whether it works now.
3. Even if connect fails, run the lk_a write: blankflash with only lk_a
   selected (v2 LK for the bisect, or the original neon LK), or the raw
   write-offset. It should NO LONGER panic (exit 101) — it either writes
   or fails with a clean error naming the real cause.
