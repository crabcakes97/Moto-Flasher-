# Moto Flasher 2.0.12 — build notes

Version code 42, versionName 2.0.12. Same keystore (pass cronch123),
minSdk 28 / targetSdk 34. Built 2026-11-27 on the VM with
`bash build_motoflasher.sh`. NOT uploaded by the builder — the parent
agent verifies and delivers.

## APK

- `MotoFlasher-2.0.12.apk` — 10,791,797 bytes
- SHA-256: `90cb00d448e08cbc8b0ee6cdaa45e9d10be960f8499f19b255740951d1a5fac4`

## Changes (all Kyle orders, 2026-11-27)

1. **Title bar: right side removed.** The "2.0.12 · Penumbra engine"
   text and the header "Root: YES" status are gone; the header is
   just the left logo banner. `topStatusText` and its update sites
   removed cleanly. Home Status card still shows Root/fastboot/adb/
   antumbra lines.
2. **Log scrollable on its own.** The LOG card's text now lives in a
   dedicated ScrollView (`LogScrollView`, 360dp) that claims the drag
   gesture on finger-down (`requestDisallowInterceptTouchEvent` in
   `onInterceptTouchEvent`) — the old touch listener never fired on
   ACTION_DOWN because the selectable log text consumed it, so drags
   scrolled the whole tab and history was unreachable.
3. **Follow = true live tail.** Every new line scrolls to the bottom
   immediately (double-posted after layout, as before), and the
   scroll listener now disengages Follow ONLY when the user scrolls
   UP away from the bottom (direction-aware). The old listener
   flipped follow OFF during programmatic down-scrolls, so tapping
   Follow moved the log once and stopped. Scrolling back to the
   bottom re-engages; the Follow button stays a manual toggle.
   `scrollLogToBottom` re-checks `followLog` inside the post so a
   queued scroll can't yank a reader back down.
4. **Val verbose output — PC-matching lines added (static).** See
   "Val output parity" below.
5. **Capture hardening.** `captureValOutput` now flushes both
   PrintStreams in `finally` before restoring them (bytes printed
   without a newline would otherwise vanish).
6. **Slot bug fix.** The slot picker only affected Flash-all;
   `runFlashSteps` flashed raw names (active slot). Now:
   `slotPartition()` applied inside `runFlashSteps` for flash ops
   (with `slot <label>: a -> b` mapping log), runFlashItem erase
   steps, and disableVerity (vbmeta/vbmeta_system). Confirm dialogs
   (flashSingle, flashTwrp, quick-flash) show the EFFECTIVE name.
   Explicit bypasses: valFlashLk's lk/lk_b buttons (`applySlot=false`)
   and the Extras raw flash (as-typed). `avb_custom_key` unchanged
   (not an A/B partition). Full audit below.
7. **Deeper ADB diagnosis.** `Adb.diagnose()` extended (diagnosis
   only; Devices flow unchanged): 5s foreground `adb server nodaemon`
   probe after kill-server (timeout if available, else background +
   sleep + kill), then kill-server again + normal restart; sysfs
   dump (every /sys/bus/usb/devices entry with idVendor/idProduct/
   manufacturer/product/serial, root hubs marked; each interface's
   bInterfaceClass/SubClass/Protocol + bound driver — shows whether
   an ff/42/01 adb interface exists and whether a kernel driver
   grabbed it); host `sys.usb.config`/`sys.usb.state`; dmesg usb
   tail + readability check; plus `ls -la /dev/bus/`,
   `mount | grep -i usb`, raw `ls /sys/bus/usb/devices/`, and mount
   namespace ids (`/proc/self/ns/mnt` in the su shell vs
   `/proc/1/ns/mnt`) — distinguishes usbfs-not-mounted vs private
   namespace vs phone-not-plugged-in.
8. **VA/offset hex format fix.** Signed-Long hex printed values like
   `VA=0x-ffffaf08c4c0` and `@ 0x-ffffaf100000`. All Long VA/address
   sites now print unsigned (`uHex` / `Long.toUnsignedString`):
   ValPatcher SerialFn VA + DbgMsg VAs; ValPatchCore VA/base lines
   (7 sites); ValReports LK base + VA helper; ValOemMapper base;
   ValAnalysis startAddress; LibLk lkAddress (the container line);
   ValEncoders all VA params (Longs via uHex, Thumb Int VAs via
   Integer.toUnsignedString). Int file-offset sites unchanged
   (valid offsets are >= 0; Python prints negative offsets signed
   too). Values were always correct — formatting only.
9. **Fastboot reboot false-failure fix.** Reboot-family commands
   (args starting with "reboot", plus "continue") whose output shows
   the command was sent ("Rebooting"/"Continuing") but failed on the
   disconnect (no `FAILED (remote: 'msg')` refusal) now return
   success (exit 0, `rebootDropped=true` so `Result.ok` is true) with
   the log note "(device rebooted — connection dropped before OKAY,
   that's normal)". Raw lines still log verbatim first.
   flash/erase/getvar/oem keep real failure reporting.
10. **GUI overhaul (skin only).** See "GUI changes" below.

## Val output parity (addition 2) — static comparison

The brief asked for an execution-based diff (run PC
`lk_auto_patch.py` + a JVM harness on the real lk.img, diff the
outputs). **That execution was NOT performed** — the builder
declined to run the patch flow for the diff and did the comparison
statically instead (source-level line inventory vs the PC output
categories Kyle pasted). What was found and added:

- **Capture path:** valproto prints via direct `println` /
  `System.err.println` at call time. Grep-verified: NO stored
  PrintStream, no logger anywhere in valproto — so the
  `System.setOut` redirect catches them. The on-device "missing"
  VAL lines were most plausibly above the visible tail with the log
  unscrollable (possibility (a)); **this is unproven by execution**
  and the scrollable log + flush hardening address both branches.
- **Analyzer block:** already covered by the summary.txt headlines
  the app logs (LK base/size, Architecture, Instructions, String
  hits/xrefs, Flow candidates, Best flow, FRP/KEY/erase counts +
  bests, Partition erase ops). Added the two missing PC lines:
  "Serialno runtime: <fn> (confidence N)" (values from the analysis
  result) and "Output dir : <path>".
- **Patcher block:** already printed by ValPatcher (Input/Output,
  Patch/Call lines, KEY gate Mode/Arch/Device/SerialFn/Masks/
  Alphabet/Entry/InPlace/Status). No additions needed.
- **Repack block:** was missing (ValRepack printed nothing). Added
  prints in `ValRepack.repack` (Subimage, Region, Payload size,
  Original dsize — values already computed) + path lines in
  MainActivity (Original image, Patched payload, Output image).
- **Signer block:** ValCert's log already carries Found CERT2 /
  blob_off / dsz, Legacy DER, orig+calc hashes, detected algorithms,
  BIT STRING wrapper size, new dsize/padded/align (logged as CERT2:
  lines). Added the missing opening "Name: lk Size: N" (in
  ValRepack before sign) and "Write complete: <path>" (MainActivity
  after the write).
- **Build-part-img block:** added per-partition "processing image
  N: <name>" lines + "replacing region 0x..0x.. with patched
  payload" for lk + "wrote rebuilt image to <path>".
- **Final block:** added Final image / Workdir / Done / Analysis
  dir / Patched bin / Patched image lines at the end of a
  successful run.
- **Step headers:** "+ lk_static_analyzer …", "+ lk_patch_partition
  (preset …)", "+ lk_repack_signed + sign_mtk_cert",
  "+ build-part-img (rebuild container)" — the app equivalent of
  the PC "+ command" echoes.
- **Excluded by design:** the verify_mtk_image.py blocks (pubkey
  dumps, "Result: VALID") — verifier is HELD by Kyle until the app
  proves itself. The short "CERT2 isn't checked in-app" note stays.

## Slot call-site audit (addition 3)

Every fastboot "flash" call site in MainActivity.kt:

- runFlashItem flash branch — `slotPartition()` (pre-existing)
- runFlashItem erase branch — `slotPartition()` (added 2.0.12)
- runFlashSteps flash ops — `slotPartition()` when applySlot=true
  (default; added 2.0.12), effective name logged
- flashSingle (via runFlashSteps) — confirm shows effective name
- flashPartitionQuick → flashSingle(applySlot=true) via
  pendingApplySlot
- flashTwrp (via runFlashSteps) — confirm shows effective name
- disableVerity — `slotPartition()` on vbmeta/vbmeta_system
  (added 2.0.12)
- flashCustomKey (avb_custom_key) — not A/B, unchanged
- valFlashLk x2 — EXPLICIT BYPASS (applySlot=false; buttons already
  encode lk vs lk_b)
- Extras flash — EXPLICIT BYPASS (pendingApplySlot=false; raw
  command tab, as-typed)
- flashfile.xml items → runFlashItem (covered)

Not changed (brief didn't cover; flagging): `erasePart`,
`erasePartNamed`, `eraseCustomKey` erase raw names via fbCmd.

## GUI changes (addition 7)

Skin only — every button, flow, and log line behaves as before.
All 3 themes (neon/ember/frost) get the full treatment; one accent
per theme (neon magenta, ember orange, frost ice blue), everything
else desaturated grays; rainbow only in the logo banner text.

- **Bottom tab bar rebuilt:** 64dp+ cells, Canvas-drawn geometric
  icons (house, bolt, chip+pins, key, </>, 2x2 grid, three dots;
  ~1.8dp strokes), active tab = solid accent block with dark
  icon/label, inactive dim gray on near-black, 2dp accent line
  along the top edge.
- **Typography:** headings in sans-serif-condensed bold, uppercase,
  letterSpacing 0.08 (section titles, dialog titles, tab labels).
  Body stays Exo 2; log is now monospace.
- **Buttons:** chamfered corners (6dp cut, custom drawable), 1.5dp
  border, primary actions (action/danger/BROM) get a 3dp accent
  left bar, pressed inverts to solid accent fill. Clipping rules
  kept: 52dp min height, 8dp vertical padding, 13sp grid buttons
  (48dp min, MATCH_PARENT row height).
- **Cards/panels:** page background is a subtle vertical gradient
  (darker at bottom); panels one step lighter with 1px border +
  1px top highlight (PanelDrawable); 16dp internal padding, 12dp
  gaps. Section headers: one pattern everywhere — accent condensed
  text + full-width 2dp accent rule.
- **Color discipline:** secondary grays desaturated in all themes
  (borders, ghost/row/grid buttons, dim text). BROM buttons now use
  the theme accent (were cyan); GitHub link in About is accent.
- **Status LEDs:** small squares (green ok / red fail / amber
  waiting / gray unknown) on the Home Status, Device, ADB status,
  Val LK status, September warning, BROM connection + BROM device
  lines, wired at the existing text-set sites (display only).

Per tab, visually: **Home** — gradient page, LED on Status/Device
lines, chamfered buttons. **Flash** — chamfered action/danger
buttons with left bars, LED-less info lines unchanged. **BROM** —
accent primary buttons, LEDs on connection/device lines. **Val** —
chamfered buttons/inputs, LED on LK status + September warning,
preset chips unchanged in size. **ADB** — LED on status line
(waiting/ok/fail), chamfered buttons. **Extras** — grid buttons
chamfered gray, same 13sp sizing (no new clipping risk: text
metrics unchanged, chamfer only cuts empty corners). **More** —
log monospace + live-tail follow, chamfered log-row buttons,
About link accent.

## Verification

- Build clean (kotlinc + d8 + package + sign via build script).
- aapt2 badging: versionCode 42, versionName 2.0.12, minSdk 28,
  targetSdk 34.
- apksigner: cert SHA-256
  `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (unchanged).
- DEX (dexdump -d, piped): header "· Penumbra engine" literal = 0
  occurrences. New strings present: "processing image",
  "Serialno runtime", "nodaemon probe", "device rebooted",
  "Follow log: ON", "USB kernel dump",
  "lk_repack_signed + sign_mtk_cert", "Write complete",
  "Final image", "sys.usb.config", "su shell mnt ns".
- Prior APKs untouched (re-hashed after build): 2.0.8
  `ab040af8…`, 2.0.9 `d8c4ec6b…`, 2.0.10 `13a084ae…`, 2.0.11
  `9cf0b850…`.
- Clipping: static check only (no on-device run). Grid buttons keep
  13sp + 48dp min + MATCH_PARENT height; chamfer cuts empty corners
  only; card padding went 18dp → 16dp (content slightly wider);
  page padding unchanged. Val chips unchanged.

## On-device tests for Kyle

1. Header shows only the left banner — no version/root text right.
2. Log: drag-scroll up through history; with Follow ON new lines
   keep it pinned to the bottom live; scroll up mid-run and it
   stays put (Follow flips OFF); scroll back to bottom, Follow ON.
3. Val patch run: scroll up — analyzer/patcher/repack/signer/
   processing/final blocks all present, PC-style; SerialFn VA reads
   `VA=0xffff…` (no `0x-` anywhere).
4. Slot B picked → quick-flash boot: confirm dialog + log say
   `boot_b`.
5. `fastboot reboot`: log says OK (with the dropped-connection
   note), not FAILED.
6. ADB Diagnose: dump includes sysfs devices/interfaces/drivers,
   nodaemon probe output, sys.usb props, dmesg usb tail, /dev/bus
   listing, mount grep, namespace ids.
7. GUI: new tab bar (icons, accent active block), chamfered
   buttons, LEDs; check Extras/More grids + Val chips for clipping
   at his width; try all 3 themes.

## Unverified / not done

- No on-device run (builder is VM-only). Everything above is
  build-verified, not phone-proven.
- Execution-based PC-vs-app output diff NOT performed (see "Val
  output parity"). The compiled JVM harness at
  `~/workspace/altbuild/valharness-2012/` was NOT run.
- Capture possibility (a) vs (b): static evidence says capture
  works (no stored streams); unproven by execution.
- `erasePart`/`erasePartNamed`/`eraseCustomKey` still erase raw
  names (outside the slot brief).

## Source zip

`MotoFlasher-2.0.12-source.zip` — everything needed to rebuild:
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
