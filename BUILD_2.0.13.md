# Moto Flasher 2.0.13 — build notes

Version code 43, versionName 2.0.13. Same keystore (pass cronch123),
minSdk 28 / targetSdk 34. Built 2026-11-27 on the VM with
`bash build_motoflasher.sh`. NOT uploaded by the builder — the parent
agent verifies and delivers.

## APK

- `MotoFlasher-2.0.13.apk` — 10,808,181 bytes
- SHA-256: `0fd8d30257b621fed52b8df91ad15674e032b8e89204ddabfed44217c26ed01c`

## Changes (all Kyle orders, 2026-11-27)

1. **Text pass, all areas.** Every user-facing string rewritten
   short, simple, human, professional: sentence-case body text, no
   over-explaining, no slang, no arrows. Log lines that mirror tool
   output (fastboot/adb output, Val PC-style VAL: lines, valproto
   exception text) stay verbatim — those are tool output, not copy.
   Notable: flash-step labels changed from `flash boot <- boot.img`
   to `flash boot: boot.img` (groupSparseChunks regex + displayLabel
   updated to match; runFlashItem's `startsWith("flash ")` dispatch
   unchanged); `slot X: a -> b` log lines now read `a becomes b`;
   `== flash boot <- x` reads `from x`; the BromUi `[UNTESTED]`
   status tags are gone from body text and log lines (statusOf /
   statusLine deleted — the tags were stale; BROM works on-device).
   Shouting normalized (STOPPED LOUDLY, REFUSED, BLOCKED, could NOT,
   BRICK WARNING, WIPES, NONE) to plain sentences; OK:/FAILED:
   prefixes kept as the app's status convention. Confirm dialog
   positive button is now "Confirm" (was "Yes, do it"). September
   warning untouched, word for word. Credits untouched. No emojis
   anywhere (verified by scan).
2. **Home STATUS card: root only.** The fastboot/adb/antumbra ready
   lines are gone from the card; it shows just `Root: YES/NO` with
   its LED. Install failures still go to the log. writeGate's
   re-check no longer preserves the old extra lines.
3. **Title bar redesign.** Rebuilt in the 2.0.12 GUI language:
   subtle vertical gradient (accent-tinted top easing into the card
   colour), chamfered bottom corners, solid accent chamfered bolt
   block (Canvas bolt icon in dark on accent), "MOTO FLASHER" in
   condensed bold letter-spaced rainbow (the one place rainbow
   lives), "BROM · FASTBOOT · PENUMBRA" in dim condensed
   letter-spaced type below, thin 2dp accent underline. No version/
   engine/root text on the right. All 3 themes (accent follows the
   theme). The logo bitmap is no longer drawn in the bar (the
   asset stays bundled; UiStyle.logoBanner remains as dead code).
4. **Erase slot fix.** `erasePart`, `erasePartNamed`, and
   `eraseCustomKey` now apply `slotPartition()` — the confirm
   dialog, the log, and the fastboot command all use the effective
   name, same pattern as the flash paths. `avb_custom_key` is not
   in AB_PARTITIONS, so it passes through unchanged (verified in
   the set). Extras raw erase stays as-typed (2.0.12 rule).
5. **Live device watcher (Home).** While the Home tab shows, the
   app polls `fastboot devices` every ~3s (quiet — no log spam;
   logs only connect/disconnect). The DEVICE card updates live:
   serial + fastboot mode when present, "No device" when absent.
   Polling skips while any operation runs (op counter, see 9) or
   while a BROM connect/session is active (BromUi.isBusy), so it
   never fights an operation for the USB device. Stops in
   onDestroy. Manual Refresh/Getvar all still work as before; the
   watcher only overwrites the card on a plug-state change, so a
   Getvar all readout is not clobbered.
6. **DEVICE card enrichment.** On connect, `getvar current-slot`
   and `getvar unlocked` are fetched once per serial (cached) and
   shown under the serial, e.g. "Slot: b · Unlocked: yes".
7. **Log export.** "Save log" button in the LOG card (second row,
   under Copy/Clear/Follow) writes the full log to
   `Download/MotoFlasher/logs/log-<yyyyMMdd-HHmmss>.txt` via
   BromDownloads (same publish path as Val outputs) and logs the
   path. Empty log = toast, nothing written.
8. **Boot image inspector (Flash tab, new card).** "Inspect boot
   image" picks an .img, reads the first 64 KB + file size, and
   parses the header with the new pure-Kotlin BootImage.kt: boot
   v0-v4 (magic ANDROID!, kernel/ramdisk/second sizes + addresses,
   page size, name, cmdline + extra cmdline, recovery DTBO and DTB
   sizes where present, v4 signature size, OS version + patch
   level decoded) and vendor_boot v3/v4 (VNDRBOOT: vendor ramdisk
   size, page size, DTB, name, ramdisk table + bootconfig sizes,
   cmdline). Facts are logged and shown in the card. A non-boot
   image says so plainly with its magic. Read-only; nothing is
   flashed from this card. Parser verified on the VM (below).
9. **Animations.** All UI-thread, Canvas/ValueAnimator only, no
   assets, cheap; everything stops when its operation ends or its
   tab hides; animators cancelled in onDestroy / onDetach.
   - Operation progress strip under the title bar: indeterminate
     accent sweep while any flash/erase/BROM/Val/ADB operation
     runs; determinate fill for flash-all (step X of N). Hidden
     when idle. Driven by an operation counter (acquireWake/
     releaseWake hook in, covering BROM writes; fbCmd, puller,
     ADB, Val patch, and the device queries count directly).
   - Button press feedback: scale to 0.97 on finger-down, spring
     back on up/cancel (~120ms), on every chamfered button
     (styled + grid). The touch listener never consumes the event,
     so clicks are unchanged; the pressed background already
     inverts to the accent fill.
   - Tab switch: incoming tab fades/slides in (~150ms).
   - Status LED: gentle pulse on the Home status LED while an
     operation runs; solid when idle. DEVICE LED pulses briefly
     and the DEVICE card border glows in the accent colour when a
     device connects (works with the watcher).
   - Val flow: the step readout is now six chips (Pull, Analyze,
     Patch, Flash, Unlock, Verify). Done steps are solid accent;
     the active step pulses while the Val tab shows. Flash/verify
     have no completion flag in the flow state, so flash becomes
     the active step once patched and verify stays pending.
   - Operation result: brief green (success) or red (failure)
     edge flash around the content area when an operation with a
     known verdict finishes, fading over ~600ms. (BROM writes run
     through the wake-lock hook with no verdict, so they get the
     strip + pulses but no edge flash.)
   - Header bolt: subtle glow pulse (alpha 0.55..1.0) only while
     an operation runs, never idle.

Everything else stays as 2.0.12: GUI overhaul, scrollable
live-follow log, slot fix, verbose Val output, FRP presets, ADB
diagnose, reboot false-failure fix, hex format fix. Standing rules
unchanged: no DA checks, no greyed blankflash buttons, no BROM
battery gate, erase not format, verbose logs,
Download/MotoFlasher/ outputs, no emojis. The Val verifier stays
HELD (verify_mtk_image.py not ported).

## Boot parser verification (VM, execution-based)

BootImage.kt was compiled with a JVM harness
(`~/workspace/altbuild/bootcheck/`, kotlinc 2.4.20, JDK 21) and run
against real images from the S741U1UES2AZI7 firmware, cross-checked
against a python struct unpack of the same headers — exact match:

- `boot.img` (67,108,864 B): Android boot image v4, kernel
  39,111,168 bytes, ramdisk 0 bytes, page size 4096 (fixed),
  header size 1584 bytes, OS version not set in header, signature
  size 0 bytes, cmdline empty.
- `vendor_boot.img` (117,440,512 B): vendor boot v4, vendor
  ramdisk 60,919,995 bytes at 0x10000000, kernel load address
  0x10008000, page size 2048, header size 2128 bytes, DTB 440,344
  bytes at 0x11f00000, name SRPZC17A002, ramdisk table 3 entries /
  324 bytes, bootconfig 96 bytes, cmdline
  "panic_on_warn=0 bootconfig loop.max_part=7".
- `dtbo.img` (8,388,608 B): correctly reported as not a boot image.

## Verification

- Build clean (kotlinc + d8 + package + sign via build script;
  only pre-existing valproto warnings).
- aapt2 badging: versionCode 43, versionName 2.0.13, minSdk 28,
  targetSdk 34.
- apksigner: cert SHA-256
  `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (unchanged).
- DEX (dexdump -d, piped): new strings present — "Save log" (1),
  "Inspect boot image" (2), "current-slot" (1), "Device
  connected" (1), "Boot image inspection" (1), "Not an Android
  boot image" (2). Old Home display string "fastboot ready" = 0
  occurrences. Protected strings intact: September warning (1),
  "Made with love by crabcakes97" (1), "github.com/crabcakes97"
  (2), "BROM · FASTBOOT · PENUMBRA" (1), "MOTO FLASHER" (1).
- Prior APKs untouched (re-hashed after build): 2.0.8
  `ab040af8…`, 2.0.9 `d8c4ec6b…`, 2.0.10 `13a084ae…`, 2.0.11
  `9cf0b850…`, 2.0.12 `90cb00d4…`.
- Animations: static check only (no on-device run). Animators are
  cancelled in onDestroy, onDetach (strip + edge flash), on tab
  switch (tab-bound pulses), and on operation end (strip, bolt,
  status LED). Button press feedback returns false from the touch
  listener so clicks are untouched. Clipping rules unchanged:
  button min heights/padding untouched, Save log got its own row
  (no 4-across squeeze), Val chips are 11sp in six equal cells.

## On-device tests for Kyle

1. Home STATUS card shows only the root line. Title bar: bolt
   block + MOTO FLASHER + BROM · FASTBOOT · PENUMBRA, gradient,
   accent underline; try all 3 themes.
2. Read through the text on every tab — short, plain, no arrows,
   no shouting. September warning unchanged on the Val tab.
3. Slot B picked → Erase partition above (type boot) and Quick
   erase boot: confirm dialog + log say `boot_b`. Erase key still
   says `avb_custom_key`.
4. Watcher: on Home, plug the Moto in fastboot mode — DEVICE card
   shows serial + Slot/Unlocked within ~3s, LED pulses, card
   border glows; unplug — back to "No device". No log spam while
   idle.
5. Save log (More tab): file lands in Download/MotoFlasher/logs/
   with the full log; path in the log.
6. Inspector (Flash tab): pick a real boot.img — facts match the
   header; pick a non-boot file — plain "not a boot image".
7. Animations: progress strip during a flash (determinate during
   flash-all), button press dip, tab fade, status LED pulse while
   flashing, Val chips (done solid, active pulsing), green/red
   edge flash at the end, bolt glow only while running. Check the
   log live-follow still tracks smoothly during a long run, and
   the Extras/More grids for clipping at his width.

## Unverified / not done

- No on-device run (builder is VM-only). Everything above is
  build-verified, not phone-proven.
- The watcher, inspector, log save, and all animations need the
  on-device tests above.
- BROM operations get the progress strip and pulses but no
  green/red result flash (no verdict reaches MainActivity).
- `UiStyle.logoBanner` is now dead code (the title bar draws its
  own bolt + text); left in place, harmless.

## Source zip

`MotoFlasher-2.0.13-source.zip` — everything needed to rebuild:
`src/`, `res/`, `assets/`, `toolchain/`, `AndroidManifest.xml`,
`build_motoflasher.sh`, `BUILD_*.md`, `CREDITS.md`,
`LICENSE.penumbra`, `SPEC.md`, `LK_EXTRACTION_2.0.8.md`,
`README.md`.
Excluded: `motoflasher.keystore` (build script generates a fresh
one if missing — note: a fresh keystore means a different signing
cert), all APKs + .idsig files, `build/`, `preview/`.
(The copy of this note inside the zip predates the zip's own
size/SHA lines appended below — a file can't contain its own zip's
hash.)
