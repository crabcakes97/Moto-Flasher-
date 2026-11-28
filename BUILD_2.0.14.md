# Moto Flasher 2.0.14 — build notes

Version code 44, versionName 2.0.14. Same keystore (pass cronch123),
minSdk 28 / targetSdk 34. Built 2026-11-27 on the VM with
`bash build_motoflasher.sh`. NOT uploaded by the builder — the parent
agent verifies and delivers.

## APK

- `MotoFlasher-2.0.14.apk` — 10,820,469 bytes
- SHA-256: `0cb3cf0033a8f57c82410e072cd3123ae9a8c7930e84c7cec604f5a0b069f01f`

## Changes (all Kyle orders, 2026-11-27)

1. **Val step chips evened.** The six flow chips (Pull / Analyze /
   Patch / Flash / Unlock / Verify) are now identical size: fixed
   28dp height, equal weight in the row, single-line labels
   (`maxLines = 1`), 10sp text and 2dp side padding so "ANALYZE"
   fits on one line at ~360dp total width (it used to wrap to
   "ANALYZ / E" and make its chip taller than the rest). Centered
   text, equal gaps. Behavior unchanged: done = solid accent,
   active = pulsing, pending = dim.
2. **Flash LK to lk_a.** Third explicit button on the Val tab
   (`valFlashLk("lk_a")`, slot-picker bypass like lk / lk_b) for
   devices that have no bare "lk" partition, only the _a/_b pair.
   A pulled `lk_a` now also feeds the Val flow like lk/lk_b do.
3. **Partition-size check before flash.** In `runFlashSteps`
   (single flash, quick flash, TWRP, custom key, Val LK flash),
   before each flash op the app runs
   `getvar partition-size:<effective-partition>` and logs both
   sizes. File bigger than the partition → confirm dialog ("File
   is X, partition is Y — flash anyway?"); declining skips the
   flash. A failed/empty getvar never blocks — it logs and
   proceeds. Flash-all (`runFlashItem`) logs sizes per step with
   no per-step dialog; oversize steps are collected and one
   summary line is logged at the end of the run ("Size check: N
   file(s) bigger than their partition (...) — flashed anyway").
   The background-thread confirm uses a latch; any dismissal
   counts as "no" (`styledDialog` gained an optional `onNo`).
4. **Bootloader status button (Val tab).** One tap runs
   `getvar securestate`, `unlocked`, `frp-state`, `current-slot`;
   every line is logged verbatim and the four values land in a
   status readout under the LK status line (LED green when
   unlocked, amber otherwise). Read-only.
5. **Log filter (More tab).** A "Filter log" box above the log:
   typing shows only matching lines (case-insensitive), clearing
   restores all. Display only — the buffer keeps everything, so
   Copy/Save still export the full log. New lines arriving while
   a filter is active show only when they match; Follow/live-tail
   works exactly as before.
6. **Auto-backup toggle (Flash tab, default OFF, persisted).**
   "Back up partition before flashing" checkbox in the Flash one
   partition card. When ON, single/quick flashes (the
   `runFlashSteps` paths) pull the target partition first via the
   existing puller machinery (refactored into
   `pullPartitionBlocking`) and publish it to
   `Download/MotoFlasher/backups/<partition>-<yyyyMMdd-HHmmss>.img`.
   A failed backup logs why and asks "Flash anyway?" — it never
   blocks silently. When OFF, behavior is exactly as 2.0.13.
   (Note: the pull needs the target in Android/TWRP with adb +
   temp root, while flashing needs fastboot mode — so in fastboot
   mode the backup will fail and the confirm appears. That is the
   specified behavior: warn, let Kyle proceed.)
7. **More animations** (same rules as 2.0.13: UI thread only,
   never block work, stop on op end/tab hide, no emojis):
   - Card entrance: when a tab opens, its cards fade/slide in
     with a ~40ms stagger (~200ms each).
   - Val chip transitions: a step completing fades its chip from
     the previous colours to solid accent over ~250ms instead of
     snapping (`setChipState` tracks the previous state per chip).
   - Active-card shimmer: while an operation runs, an accent
     sweep travels along the top border of the last card touched
     (`PanelDrawable.shimmerPhase`; the card is tracked via
     `dispatchTouchEvent` hit-testing on the visible tab).
   - Success check / failure X: a small Canvas-drawn mark strokes
     in (~300ms) next to the Home status line when an operation
     with a known verdict finishes. BROM writes pass no verdict,
     so they get no mark — never a fake one.
   - Step counter tick: flash-all shows "Step X of N" under the
     Flash selected button; the number and the determinate strip
     animate together (~250ms per step).
8. **Settings section (More tab, below Theme).** Persisted in
   SharedPreferences, applied immediately where possible; toggle
   state shows as a solid (on) / dim (off) block:
   - **Animations** on/off (default ON). OFF disables all
     animations from 2.0.13 + this build: progress strip still
     shows as a static fill, LEDs solid, no pulses, shimmers,
     entrances, press dips, chip fades, bolt glow, or edge-flash
     fade (the edge flash shows solid ~600ms instead). Everything
     already running settles to its static state on toggle.
   - **Log text size** S / M / L (default M = 13sp; S = 11sp,
     L = 15sp), applied live to the log view.
   - **Follow log at launch** on/off (default ON). Also applies
     to the current session (Follow button label + scroll).
   - **Keep screen on** on/off (default OFF;
     `FLAG_KEEP_SCREEN_ON` while the app is in foreground).
   The Theme picker is unchanged, still in More above Settings.

Everything else stays as 2.0.13. Standing rules unchanged: no DA
checks, no greyed blankflash buttons, no BROM battery gate, erase
not format, verbose logs, Download/MotoFlasher/ outputs, no
emojis, September warning verbatim, credits unchanged, clipping
rules (52dp min button height, 8dp vertical padding, 13sp grid
buttons). The Val verifier stays HELD.

## Verification

- Build clean (kotlinc + d8 + package + sign via build script;
  only pre-existing valproto warnings; one fix during the build:
  `@Volatile` on a local in `confirmBlocking` → `AtomicBoolean`).
- aapt2 badging: versionCode 44, versionName 2.0.14, minSdk 28,
  targetSdk 34.
- apksigner: cert SHA-256
  `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (unchanged).
- DEX (dexdump -d, piped): new strings present — "lk_a" (6),
  "Flash LK to lk_a" (1), "partition-size" (3), "Bootloader
  status" (5), "backups" (2), "Filter log" (1), "Animations"
  (24), "Log text size" (2), "Follow log at launch" (2), "Keep
  screen on" (2), "Back up partition before flashing" (1),
  "File is bigger than the partition" (1), "Flash anyway" (3),
  "Size check" (4), "securestate" (5), "frp-state" (3),
  "current-slot" (2). Protected strings intact: September
  warning (1), "Made with love by crabcakes97" (1),
  "github.com/crabcakes97" (2), "MOTO FLASHER" (1),
  "Experimental commands." (1). Emoji-range scan of the DEX: 0.
- Prior APKs untouched (re-hashed after build): 2.0.8
  `ab040af8…`, 2.0.9 `d8c4ec6b…`, 2.0.10 `13a084ae…`, 2.0.11
  `9cf0b850…`, 2.0.12 `90cb00d4…`, 2.0.13 `0fd8d302…`.
- Chips: static code review — fixed 28dp height, weight 1 each,
  `maxLines = 1` + `setSingleLine(true)`, 10sp, 2dp side padding,
  `includeFontPadding = false`. All six identical by construction.
- Animations/settings: static check only (no on-device run).
  Every animator is cancelled on op end, tab hide (card settle),
  settings toggle-off, or onDestroy (shimmer, flash-all tick,
  chip fades, verdict mark, LED/chip/bolt pulses).

## On-device tests for Kyle

1. Val tab: all six chips the same size, "ANALYZE" on one line;
   run the flow — chips fade to solid as steps complete, active
   chip pulses.
2. Val tab: "Flash LK to lk_a" flashes `lk_a` (log + confirm say
   lk_a); "Bootloader status" shows securestate / unlocked /
   frp-state / current-slot in the readout and the log.
3. Size check: single-flash an oversize file (e.g. a big image to
   a small partition) — dialog shows both sizes; Cancel skips,
   "Flash anyway" flashes. Normal-size flash logs the size check
   and proceeds with no dialog. Flash-all with an oversize step:
   sizes logged per step, one summary line at the end, no dialogs.
4. Log filter (More): type part of a word — only matching lines
   show; new matching lines appear live; clear the box — full log
   back. Follow still tails. Copy/Save still export everything.
5. Auto-backup: toggle ON (Flash tab), flash something with the
   target in fastboot mode — backup fails, dialog asks, "Flash
   anyway" proceeds. (With the target in TWRP/Android + adb root
   the pull itself can succeed, but the flash then needs fastboot
   mode.) Toggle OFF — no backup attempt, exactly as 2.0.13.
6. Animations: cards stagger in on tab switch; shimmer sweeps the
   touched card's top border while flashing; green check / red X
   strokes in next to Root status after an op; flash-all counter
   ticks "Step X of N" with the strip.
7. Settings (More): Animations OFF — strip static, LEDs solid, no
   pulses/shimmer/entrance/press dip; ON restores them. Log size
   S/M/L changes the log live. Follow-at-launch OFF + relaunch —
   log does not tail until Follow is tapped. Keep screen on ON —
   screen stays awake in the app. Relaunch: all four settings
   persist.

## Unverified / not done

- No on-device run (builder is VM-only). Everything above is
  build-verified, not phone-proven.
- The size check, bootloader status, log filter, auto-backup,
  animations, and settings all need the on-device tests above.
- Auto-backup pulls over adb (target in Android/TWRP + temp
  root); flashing needs fastboot mode. In fastboot mode the
  backup fails and the confirm dialog is the path — by design,
  never a silent block. There is no fastboot-side partition read
  in this LK, so a same-mode backup is not possible today.
- The shimmer tracks the last card touched on the visible tab;
  BROM-tab cards built by BromUi are not direct children of the
  tab root, so the shimmer may not find a card there (harmless
  no-op).
- `UiStyle.logoBanner` is still dead code (left in place,
  harmless).

## Source zip

`MotoFlasher-2.0.14-source.zip` — everything needed to rebuild:
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
