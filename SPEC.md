# Moto Flasher — App Spec (v1: fastboot flasher only)

Status: SPEC ONLY — nothing built yet. Build starts only on Kyle's explicit go.
Scope chosen 2026-11-07: the flasher only. Val Protocol unlock and mtkclient/BROM are NOT in v1.

## 1. What it is

An Android app that runs on the rooted host phone (Kyle's Galaxy S26 FE) and flashes
Motorola phones connected over a USB OTG cable — no PC needed.

The core is already proven on-device (2026-11-07): Termux on the S26 FE, `fastboot
devices` sees the Moto (`Motorola Fastboot Interface`), `fastboot reboot` works.
The app is that same path with a UI instead of a terminal.

## 2. Goals / non-goals

Goals (v1):
- Detect a Moto in fastboot mode over OTG and show it (model, serial, slot, lock state).
- Flash a partition from a picked .img file.
- Flash-all: flash every partition from a firmware folder in one run.
- Reboot the target (system / bootloader / recovery).
- Switch active slot.
- Boot an image without flashing (`fastboot boot`).
- Raw command box for any other fastboot command.

Non-goals (v1 — parked, not dropped):
- Val Protocol unlock flows (separate scope decision later).
- mtkclient / BROM / DA / preloader work (Python + libusb port — big lift, see §7).
- Samsung / Odin / Download mode (different protocol entirely).
- Flashing the host phone itself.

## 3. How it talks to the Moto (transport)

Recommended v1 approach: bundle a fastboot binary, shell out as root.
- Ship an arm64 `fastboot` binary in app assets (from AOSP platform-tools / the same
  android-tools build already proven in Termux tonight).
- Run it via `su` (KernelSU root on the host). Root is needed for raw USB access
  to /dev/bus/usb; the normal Android USB host API is the fallback if root is off,
  but root is the primary path.
- Parse the binary's stdout/stderr for the log and results — no protocol
  reimplementation in v1.

Alternative considered and deferred: implement the fastboot protocol natively in
Kotlin over UsbDeviceConnection. Cleaner long-term, no binary to ship — but it is a
rewrite of a proven tool for v1. Revisit only if the bundled binary proves flaky.

## 4. Host requirements

- Rooted Android host (S26 FE, temp root via KernelSU-Next — same as today).
- USB OTG cable + adapter; target Moto in fastboot mode.
- Host must stay awake and must NOT reboot mid-flash: temp root dies on reboot,
  and a dead host mid-write is a bricked target. App holds a wake lock during
  any write and warns before starting.
- Host battery: warn/stop below 30% before a write.

## 5. Features (v1)

### 5.1 Device screen
- `fastboot devices` on connect / refresh button.
- Per device: serial, `getvar product`, `getvar current-slot`, `getvar unlocked`,
  `getvar version-bootloader`, battery voltage if reported.
- One device at a time (v1). No multi-device.

### 5.2 Flash
- Pick an .img file (system file picker), pick or type the partition name
  (`boot`, `init_boot`, `lk`, `vbmeta`, `super`, etc. — free text plus a
  common-partition list).
- Before any write: confirmation screen showing the EXACT command that will run
  (`fastboot flash <partition> <file>`, file size + SHA-256), plus a
  "this can brick the target" warning. No silent writes, ever.
- Live log of the flash output, success/failure clearly stated.

### 5.2b Flash-all (flash every partition)
- Pick a firmware folder. Two ways in, in this order:
  1. `flashfile.xml` (Motorola's own flashing script, shipped in stock firmware):
     parse its steps in order and run them one by one — `flash`, `erase`,
     `oem` commands, `reboot` — exactly as the file says.
  2. Folder fallback (no flashfile.xml): every `.img` in the folder, filename =
     partition name (`boot.img` → `boot`, `lk.img` → `lk`, ...), flashed in a
     fixed safe order (boot-critical partitions last where order matters).
- FLASH ORDER (folder mode, enforced): the PROPER vbmeta family goes FIRST
  (`vbmeta`, `vbmeta_system`, `vbmeta_vendor`), then the images vbmeta
  verifies (`boot`, `init_boot`, `vendor_boot`, `dtbo`), then the SPARSE
  images (`super`, `vendor`, `product`, `system`, `odm`, `oem`), then
  `recovery`, `logo`, `lk`. vbmeta verifies the rest, so it lands first and
  everything after it is covered the moment it lands. XML mode keeps
  Motorola's own order (the vendor script already does this); the confirm
  screen shows the order either way so Kyle can see vbmeta is first.
- Before starting: confirmation screen listing EVERY partition + file + size +
  SHA-256 that will be written, and the order. One confirmation for the whole run.
- YOU PICK WHAT FLASHES: every partition on that list has a checkbox. Tick only
  what you want — flash one partition, five, or all of them. Defaults: all
  checked; bootloader-critical partitions (`lk`, `vbmeta`, `boot`) flagged in
  the list so you see the risky ones before you tick them.
- Per-partition progress and result list. STOP on the first failure — do not keep
  flashing after a failed partition. Full log kept.
- Sparse images: consecutive `super.img_sparsechunk.N` steps (from XML or folder)
  collapse into ONE checkbox row per partition ("flash super <- super.img
  (24 chunks, 6.4 GB)"). The chunks still flash one by one in order; only
  the list gets shorter (v1.0.2 — the video showed 24 rows of super).
- Honest limit: "all" means all *fastboot-flashable* partitions. fastboot cannot
  write `preloader` (and some protected partitions) — those stay BROM/mtkclient
  territory and are listed as skipped, not silently dropped.
- Flash-all generally needs an unlocked bootloader: most partitions refuse to
  flash while locked. App reads `getvar unlocked` first and warns plainly if the
  target is locked ("most of these will fail until it's unlocked") instead of
  running a doomed 20-partition run.

### 5.3 Other commands
- Reboot: system / bootloader / recovery (`fastboot reboot`, `reboot bootloader`,
  `reboot recovery`).
- Slot: `fastboot set_active a|b` + show current slot.
- Boot without flashing: pick image, `fastboot boot <file>`.
- Erase: `fastboot erase <partition>` — behind the same confirmation screen
  as flashing, with its own warning.
- Raw console: type any fastboot command, see raw output. (Power-user escape hatch.)

### 5.4 Log
- Scrolling command log with timestamps, copy + clear. Real output only.

## 6. Safety rules (hard)

- Every write/erase/boot asks first and shows the exact command. No exceptions.
- fastboot has NO partition-read command on these phones, so v1 CANNOT back up a
  partition before overwriting it. The confirmation screen must say that plainly:
  "there is no backup of this partition — flashing overwrites it."
- Refuse to run while host battery is low (<30%) or while the host is not rooted.
- If the USB device disconnects mid-write, say so loudly and stop — do not retry
  a partial write automatically.
- Never touch the host phone's own partitions. The app only ever addresses the
  OTG-attached device (checked via `fastboot devices` serial).

## 7. Why mtkclient/BROM is NOT in v1

- mtkclient is Python + libusb. An Android app would need a bundled Python
  runtime or a full Kotlin rewrite of the BROM/DA protocol — a project on its
  own, not a v1 feature.
- The DA/auth files cannot ship in a public app (Kyle's DA is not public).
  Users would have to supply their own.
- BROM targets are usually dead (no LK, no fastboot) — different recovery flow
  from a live fastboot device. That is the MTK GUI's job on a PC today.
  The v2 in-app port is spec'd in §5.13 (ordered 2026-11-08); nothing
  there is built.

## 8. App shape (build notes)

- Kotlin, single module. Min SDK to cover the S26 FE host (Android 17) — exact
  minSdk decided at build time, target the host first.
- Package name + app name: Kyle's call (not decided).
- Permissions: root via su (no root = detect only, no flash), USB host feature,
  file picker (Storage Access Framework, no broad storage permission needed).
- fastboot binary: arm64, Apache-licensed platform-tools build; verify it runs
  on-device before shipping (same check as Termux tonight).
- No AI branding, no fake credits. Real credits only (AOSP platform-tools,
  bkerler/mtkclient noted as NOT included in v1).

## 9. Testing plan

- Nevada (Moto G Play 2026, fastboot reachable): detect, getvar, reboot,
  set_active read-back, `fastboot boot` with a known-good image, then a real
  flash of a low-risk partition only when Kyle says so.
- Kansas (Moto G 2025, dead LK, no fastboot): OUT of scope for v1 testing —
  it cannot reach fastboot, so it cannot be a v1 test device.
- Host-side: kill temp root mid-session and confirm the app refuses to flash
  instead of failing halfway.

### 5.5 Bootloader unlock + custom key (added 2026-11-07, v1.0.1)
- Unlock status: `fastboot oem device-info` + `getvar all`, shown in the device box
  (locked/unlocked, and anything the bootloader reports about keys).
- Get unlock data: `fastboot oem get_unlock_data` — shows the code to paste into
  Motorola's unlock site.
- Unlock with key: type/paste the Motorola unlock key, confirm (unlocking WIPES
  the target phone — the confirm screen says so in plain words), runs
  `fastboot oem unlock <key>`.
- Relock: `fastboot oem lock` behind its own confirm — warns that relocking with
  custom images on the phone can brick it.
- Custom AVB key: pick a key file, flash it as `avb_custom_key`; erase it with
  `fastboot erase avb_custom_key`; status comes from the device-info/getvar
  output (if the bootloader does not report the key state, the app says so
  instead of guessing).
- Flashing from XML: flashfile.xml `oem` steps already run in flash-all; the
  parser also accepts `unlock` and `lock` step operations so an XML can drive
  them (`unlock` with a key in the step, `lock` with none). Unknown operations
  still stop the run — fail-closed.

### 5.6 More fastboot (added 2026-11-07, v1.0.1)
- Standard flashing commands: `flashing unlock`, `flashing lock`,
  `flashing get_unlock_ability` (unlock goes through the same wipe-warning
  confirm as `oem unlock`).
- `reboot fastboot` (reboot into fastbootd), `continue` (boot to system from
  fastboot), `reboot bootloader` / `reboot recovery` (already in v1.0.0).
- Raw console covers anything else.

### 5.7 Separate partitions flashing (added 2026-11-07, v1.0.1)
- Its own "Flash partitions" section, apart from the type-a-name box and
  flash-all: one-tap buttons per partition — boot, init_boot, vendor_boot,
  recovery, dtbo, vbmeta, vbmeta_system, super, logo, modem, dsp, lk.
  Tap a partition, pick the file, confirm, it flashes that partition.
- TWRP: pick a TWRP image, choose the target — recovery, boot, or vendor_boot
  (A/B devices have no recovery partition; the target is the user's call).
- Format: `format userdata`, `format cache`, `format boot`, `format recovery`
  — each behind its own wipe-warning confirm. Format can fail on partitions
  with no filesystem type; the log shows the bootloader's answer.

### 5.8 Live log (added 2026-11-07, v1.0.1; fixed 2026-11-07, v1.0.2)
- Moving log: every line timestamped (HH:mm:ss), auto-scrolls to the newest
  line as output arrives, selectable text, copy + clear.
- v1.0.2 FIX (bug Kyle caught on video, 21:48 CST): the log yanked the whole
  screen around on every line. Cause: each line rebuilt the ENTIRE log text
  and called fullScroll(FOCUS_DOWN), which requests focus on the bottom view
  and bubbles up to the outer screen. Fix: lines only append; the log scrolls
  with scrollTo (moves only the log's own position, never asks for focus);
  auto-scroll runs ONLY while the user is already at the bottom of the log
  (scroll up to read and it stays put; scroll back down to follow again);
  a "Follow log: ON/OFF" toggle; log capped at 2000 lines (oldest 500
  dropped) so a long flash doesn't grow the view forever.

### 5.9 vbmeta disabler (added 2026-11-07, v1.0.1)
- Flashes an empty vbmeta (valid AVB header, no descriptors, 4096 bytes,
  built in-app) with fastboot's `--disable-verity --disable-verification`
  flags, which set the VERIFICATION_DISABLED / HASHTREE_DISABLED bits at
  flash time. Buttons: vbmeta, vbmeta_system, or both.
- Needs an UNLOCKED bootloader. The UI says: flash the disabler FIRST, then
  your images. On a locked phone the flash fails (or worse) — the confirm
  screen says so before anything runs.

### 5.10 Val Protocol, built in (added 2026-11-07, v1.0.1)
- FULL VAL FLOW (the target, coordinator porting it for v1.1.0):
  1) pull LK off the phone (Puller, §5.12) → 2) analyze it (liblk, §5.11)
  → 3) pick a preset + patch it (patch engine, CERT2 re-sign) → 4) flash
  the patched LK back (lk / lk_b) → 5) `oem unlock` with the generated
  key (§5.5) → 6) verify unlocked (device-info / get_unlock_ability).
  Steps 1, 2, 4, 5, 6 work in v1.0.1+; step 3 is the port (v1.1.0).
  Finished Val files publish to `Download/MotoFlasher/val/`:
  `lk.<preset>.img`, `lk.patched.bin`, and the analysis dir as a single
  `analysis-<preset>.zip`. Working / intermediate files stay app-private.
- liblk IS the container layer of the flow, not a standalone viewer: the
  Python auto-patcher opens the image with `LkImage` (lk_auto_patch.py
  line ~208) and the patcher + repack work on the parsed container it
  produces. In the app, the pulled LK auto-analyzes into the shared
  `lkImage`/`valFlowFile`, the patch engine consumes that LkImage, and
  the repack rebuilds from its partitions (do NOT re-implement container
  parsing in the repack — the Python repack's own PartHdr is a duplicate
  of liblk; in Kotlin, reuse LibLk). Flash (step 4) takes the flow's
  container, not just the picked file. A one-tap "Pull lk + analyze"
  button starts the flow; the Val section shows the step state.
- LK picker: pick an lk.img / LKS container file from storage.
- Analyze (liblk, §5.11): lists every partition with name, size, load
  address and certs; shows which certs (cert1/cert2) each partition has.
- Extract: saves the raw `lk` payload out of the container to a file
  (app folder → `lk/lk-payload.bin`) for the Python patcher — or ANY
  partition by name (`lk/<name>.bin`, v1.0.4).
- Flash LK: flash the picked container to `lk` or `lk_b`, behind the
  brick-warning confirm (LK is the bootloader; a bad flash needs BROM + DA
  to recover).
- NOT in the app, stated plainly: the Python patcher itself (Capstone
  static analysis that generates the patched LK). That stays in
  val-protocol on a PC. The app does not pretend to generate patches.
- FULL VAL SCOPE (measured 2026-11-07, v1.0.2 decision pending): the Val
  patcher is 12,185 lines of Python across 7 files. Portable to Kotlin:
  patch engine lk_patch_partition.py (6,409 lines, stdlib only — needles,
  hand-rolled AArch64 branch decoders, gate discovery for known LK builds),
  container rebuild lk_repack_signed.py (249), CERT2 hash update
  tools/sign_mtk_cert.py (452 — hash-only, no private key/RSA: it rewrites
  two SHA-256 BITSTRINGs in the CERT2 ASN.1), keygen lk_keygen.py (180),
  oem cmd map lk_oem_cmd_mapper.py (272). NOT portable as-is: the static
  analyzer lk_static_analyzer.py (3,615 lines) — it disassembles with
  Capstone to discover gates in an UNKNOWN LK, and lk_auto_patch.py runs it
  as a subprocess to produce the analysis dir the patch engine consumes.
  So full Val in-app = port the ~7,500 portable lines (known-LK presets
  first) + either bundle Capstone native (NDK job, separate) or keep
  unknown-LK discovery on PC.

### 5.11 liblk, ported to Kotlin (added 2026-11-07, v1.0.1)
- A parse-only Kotlin port of R0rt1z2/liblk (Roger Ortiz, GPL-3.0-or-later),
  the container parser Val Protocol uses: same 512-byte little-endian
  header (magic 0x58881688, ext magic 0x58891689), same partition walk
  (cert partitions attach to the previous partition, stop at
  image_list_end or at the first non-magic offset), same load-address
  detection (LOADADDR pattern, then the v2 phys-offset pattern), same
  version rule (v2 iff an `aee` or `bl2_ext` partition is present).
- Verified against the Python liblk on real containers before shipping
  (same partition list, same sizes, same load addresses).

### 5.12 Puller (added 2026-11-07, v1.0.1)
- Pull a partition straight off the target phone: `adb shell su -c dd
  if=/dev/block/by-name/<part> of=/sdcard/MotoFlasher/<part>.img bs=1M`
  (falls back to `/dev/block/bootdevice/by-name/<part>`), then `adb pull`
  to the app's folder on this phone (`pulls/<part>.img`), size + SHA-256
  logged. Quick buttons for `lk` and `lk_b`.
- Needs on the TARGET: USB debugging ON, adb authorized, and TEMP ROOT
  (dd reads the block device as root on the target — adb alone cannot read
  partitions). The host phone also needs root (adb runs through the same
  su path as fastboot). If the target is not rooted the pull fails and
  the log says why.
- adb binary: Termux android-tools, staged the same way as fastboot
  (assets/adb + its .so deps in the shared lib dir). See
  toolchain/adb-NOTES.md for provenance and the dependency closure.

### 5.13 BROM / mtkclient in-app (v2 spec — ordered 2026-11-08, build in progress as v1.1.0)

- Status: Kyle said "Spec it out" 2026-11-08 05:08 UTC, answering the
  2026-11-07 offer to spec the in-app BROM port as a v2 job (the other
  option then was leaving it parked until Kansas is unstuck). BUILD
  ORDERED 2026-11-08 12:20 CST (Kyle tapped "Build the blankflash APK
  port"). DA+auth only — no crasher/exploit path unless ordered
  separately. All five workstreams landed (analysis, crypto, transport,
  DA/GPT/scatter, UI + Downloads); verification is file-only, no
  on-device flashing. FULL BLANKFLASH SCOPE ordered 2026-11-08 12:35
  CST: GPT rewrite + fastboot flag are IN SCOPE (GPT write implemented;
  fastboot flag open gap — destination unestablished). Quality bar
  (Kyle 2026-11-08 ~12:36 CST): "nothing half baked" — complete +
  verified or it does not ship as working.
- v1.1.2 (built 2026-11-08, from a fresh mtkclient-source research
  pass Kyle ordered after 1.1.1's Connect failed on-device): detects
  ALL modes — BROM 0x0003 plus every preloader PID in usb_ids.py
  (0x2000, 0x6000, 0x2001, 0x201D, 0x20FF, 0x3000) and Sony BROM PIDs
  under VID 0x0FCE (they were wrongly matched under 0x0E8D before);
  per-PID USB interface (0x6000 -> 2). Connect re-scans fresh every
  200 ms for up to 60 s and opens the fresh device the instant it
  appears (tap Connect first, then plug in) — a saved Detect object
  goes stale when the looping phone re-enumerates, and its permission
  grant does not carry over. BROM-side SLA is skipped in preloader
  mode (mtkclient sends none there); DA-level SLA runs at DA upload.
  Fastboot flag CLOSED: DA shutdown(FASTBOOT) sends CMD:SET-BOOT-MODE
  FASTBOOT then REBOOT — no Vol- hold. On newer devices there is no
  BROM (0003) until the DA loads; the preloader is the entry point.
- v1.1.3 (built 2026-11-08, ordered by Kyle ~14:34 CST after 1.1.2
  proved on-device that the permission flow can NEVER work on a
  looping phone — the grant is per device instance and the preloader
  re-enumerates every ~2.5 s, so the dialog fires forever): BROM is
  root-only now, like fastboot. A native helper (src/native/rootusb.c,
  arm64, raw usbfs ioctls, libc only) opens /dev/bus/usb/BBB/DDD
  directly as root and speaks a length-prefixed framing protocol over
  stdin/stdout (CLAIM / BULK_OUT / BULK_IN / CONTROL / CLOSE); a
  RootUsbTransport drives it behind the same BromTransport surface, so
  the session/handshake/DA code is untouched. The UsbManager
  permission request/dialog is GONE from the BROM flow — no
  hasPermission check, no requestPermission call, no grant receiver.
  Connect without root fails plainly: "root required — BROM needs
  root like fastboot". Connect robustness: the 60 s / 200 ms scan loop
  stays, and a transport failure mid-cycle (open fails, handshake
  dies, device vanishes mid-upload) now closes the session and
  re-scans for the next preloader appearance — up to 3 full cycles
  within the 60 s window, mirroring mtkclient's wait_for_device +
  Preloader.init retry loop. Auth/DA failures fail outright (retrying
  the same DA/auth cannot help). Handshake counts unchanged from
  Port.py (5 attempts, 500 ms per-byte timeout, 10 ms backoff, 50 ms
  flush) — the 1.1.2 failure was the permission dance burning the
  2.5 s window, not the handshake. Log files: every BROM log line is
  also appended to Download/MotoFlasher/motoflasher-brom-<stamp>.txt,
  one file per Connect run (MediaStore insert, getExternalFilesDir
  fallback); the file path is logged on screen when created.
- Plain version first: today a dead Moto (bad LK, no fastboot — Kansas)
  can only be fixed from a PC (SP Flash Tool) or from Termux + mtkclient
  on the host phone. This section specs the third path: the Moto Flasher
  app itself talking to the dead phone over OTG, so Kansas-class recovery
  happens on the phone with no PC at all. The fastboot half of the app
  (§5.1–5.9) is for live phones; this half is for dead ones.
- What it unlocks, concretely: read the good slot's LK off Kansas and
  write it to the bad slot without mom's PC; recover Nevada from a
  re-brick the same way; run a full 11-partition blankflash from the
  phone when the GPT is intact. It does NOT unlock a GPT rewrite — see
  the holes below.

#### Protocol chain (mapped to mtkclient, clone main 118a000)

Source of truth: `~/workspace/blankflash-study/mtkclient/` (fresh clone,
2026-11-07) and `~/workspace/blankflash-study/STUDY.md` (Kansas package
+ mtkclient mapping, verified against that source). File sizes below are
that clone's line counts.

1. **Detect (BROM mode).** Target phone: hold Vol Up + Vol Down, plug
   the OTG cable in. Android USB Host API (`UsbManager`, permission
   intent — NOT libusb, which has no Android build). Vendor 0E8D;
   BROM PIDs `[0x0003, 0xF200, 0xD1E9, 0xD1E2, 0xD1EC, 0xD1DD]`
   (`Port.py:158`, `brom_pids`); preloader VCOM PID `0x2000` (seen in
   Kyle's SP Flash log, `vid_0e8d&pid_2000`, COM5). The 2.5 s preloader
   window applies only if a working preloader exists; a truly dead phone
   is BROM only.
2. **BROM handshake.** `Port.run_handshake()` (`mtk_main.py:778`) via
   `mtk_class.py` `setup()` (215 lines). This is the "phone answers at
   all" step — if it fails, nothing else runs.
3. **SLA auth.** `Auth/sla.py` (36 lines: `generate_brom_sla_challenge`,
   `generate_da_sla_signature`) + `sla_keys.py` (418) + `mtk_crypto.py`
   (810: AES-CBC/ECB and helpers) + `cryptutils.py` (515). The auth file
   is supplied by the user (Kansas `auth_sv5.auth`, 2,256 B; Nevada
   `auth_sv5_1_8iw2.auth`, 2,256 B — different files, must match the DA
   and the phone). The DA is v6; the loader resolves it via
   `find_da_hash_v6` (`mtk_daloader.py:102`).
4. **DA upload.** `preloader.send_da()` (`mtk_main.py:303,575`) with the
   user-supplied DA binary (`mtk_daloader.py` 536 + `daconfig.py` 232).
   Kansas DA `DA_BR.bin` 831,952 B; Nevada DA `DA_BR_0_dlol.bin`
   828,168 B. The DA is NEVER shipped in the app (Kyle's DA is not
   public) and never uploaded anywhere — picked from storage, used
   locally, like the Val DA rule. Wrong DA = the exact failure Kyle hit
   in SP Flash Tool (98% / ~448 KB then `data_mux receive timeout`):
   the tool sent its own bundled ~457 KB DA instead of the package's
   812 KB one. The app must show the DA file's size + SHA-256 on the
   connection screen so that mistake is visible before anything runs.
5. **DA protocol.** `DA/mtk_da_handler.py` (1,618 lines): `DaHandler`
   `connect()` / `configure_da()`, `da_gpt()` (read GPT),
   `da_read_partition()` / `da_write_partition()`, `da_write()` (bulk),
   `da_erase()`, `da_read()`/`da_write()` by offset. `storage.py`
   (372) resolves regions — preloader writes go to the EMMC_BOOT1
   hardware region automatically, no special flag.
6. **GPT: read + write (full blankflash, 2026-11-08).** `Partitions/gpt.py` (331) + `partition.py` (206)
   + `mbr.py` (320) / `pmt.py` (173) for the read path. mtkclient has
   NO GPT-write command (`--gpt_file` only tells it to *read* the layout
   from a file), so the write is NEW work, not a port: `GptBuilder`
   builds primary + backup from the scatter (correct CRCs) and the app
   writes them with raw DA offset writes as the first step of the
   blankflash run, only after Check-GPT passes, only inside that flow
   (never a standalone button), verified by read-back. The fastboot
   flag IS written since 1.1.2 — as a DA command at reset
   (CMD:SET-BOOT-MODE FASTBOOT, then REBOOT); no Vol- hold needed.

#### What gets ported (Kotlin), and what does not

- Transport: `Connection/usblib.py` (936) is REPLACED by the Android USB
  Host API (`UsbDeviceConnection.bulkTransfer`, endpoint discovery),
  not ported line-for-line; `devicehandler.py` (157) + `Port.py` (261)
  become the Kotlin connection layer.
- BROM: `mtk_class.py` (215) + `mtk_preloader.py` (1,125) for setup,
  `send_da`, and memory reads.
- DA: `mtk_da_handler.py` (1,618) + `mtk_daloader.py` (536) +
  `daconfig.py` (232) + `storage.py` (372).
- Auth/crypto: `sla.py` (36) + `sla_keys.py` (418) + `mtk_crypto.py`
  (810) + `cryptutils.py` (515).
- GPT/partitions (read path only): `gpt.py` (331) + `partition.py`
  (206) + `mbr.py` / `pmt.py`.
- Total: ~3,000–5,000 lines of Kotlin + the USB layer (same measurement
  as 2026-11-07). NOT ported: the exploit/crasher path
  (`mtk_class.py` `bypass_security` / `crasher`, `Exploit/`) — that is
  SLA-bypass research, not needed for the DA+auth path, and stays out
  unless Kyle orders it separately. NOT ported: any of SP Flash Tool
  (Windows .exe, can never run on a phone).

#### UI (mirrors the MTK GUI Blankflash tab — the PC reference that works)

- **Connection tab:** pick DA file + auth file (file picker; stored
  app-private, never bundled). Shows each file's size + SHA-256 and
  whether it matches a known pair (Kansas / Nevada). Mismatch warning
  before any run.
- **BROM screen:** "phone in BROM" state, Detect button, shows VID/PID
  and chip if identified. Same log view as §5.8 (append-only, follow
  toggle, 2000-line cap).
- **Gate first — Check GPT.** A `printgpt` equivalent MUST pass before
  any write button enables. It proves the DA is up (auth accepted) AND
  the table reads. If it fails: stop, plain reason (wrong DA/auth, or
  damaged GPT → PC + SP Flash Tool only).
- **Read tab:** read a partition by name to a file (`Download/MotoFlasher/pulls-brom/`), size
  + SHA-256 logged. This is the Kansas first step: read the OTHER
  slot's LK, verify it with liblk (§5.11) before writing anything.
- **Write tab:** pick partition + file, confirm screen (exact op, size,
  SHA-256, "no backup — this overwrites it"), one write at a time,
  STOP on the first failure. Preloader flagged as BOOT1 in the confirm.
- **Blankflash tab:** pick a package folder → parse the scatter → show
  the 11-partition plan in scatter order (preloader, proinfo, spmfw_a,
  pi_img_a, sspm_a, mcupm_a, gz_a, lk_a, dtbo_a, tee_a, logo_a) with
  file checks → Check GPT → Run (confirm, GPT rewrite first, one write
  per partition, stop on first failure, reset at end, hold Vol- while
  rebooting to land in fastboot). The GPT is built from the scatter
  (primary + backup, correct CRCs) and written with raw DA offset
  writes as the first step of the run, ONLY after Check-GPT passes and
  ONLY inside this flow (never a standalone button); the built table
  must agree with the on-phone table and is verified by read-back.
  The fastboot flag IS written since 1.1.2 — DA shutdown(FASTBOOT)
  sends CMD:SET-BOOT-MODE FASTBOOT then REBOOT at the end of the run.
- **Reset:** `reset` equivalent at the end (no loader/auth on the reset
  call, same as mtkclient). Sends the DA fastboot flag, so the phone
  reboots into fastboot — no Vol- hold needed.

#### Safety rules (extend §6 for BROM)

- Same host gates as fastboot: battery ≥30% before a write, wake lock
  during any write, host must not reboot mid-write (temp root dies, and
  a dead host mid-write bricks the target).
- Transport uses the root USB helper (1.1.3), not the USB Host API
  permission flow — BROM needs root on the host, like fastboot. The
  permission flow provably cannot work on a looping phone (grant is
  per device instance; the preloader re-enumerates every ~2.5 s).
- No write without the Check-GPT gate passing. GPT write only inside
  the blankflash run (never standalone), verified by read-back. No
  auto-retry of a partial write. USB disconnect mid-write = stop
  loudly, do not retry.
- LK / preloader writes get the brick-warning confirm (§5.10 rule):
  a bad LK needs BROM + DA to recover — which is this very feature, so
  the confirm names the recovery path instead of pretending there is none.
- DOCUMENTATION FIRST (Hardware Council verdict, 2026-11-07 session):
  every BROM op is marked Verified / Untested / Unsupported in the UI
  and the log; only `printgpt` + read are candidates for first
  on-device proof, writes only after a read-back verifies.

#### Testing plan

- Nevada first (alive phone, proven DA pair): BROM detect + Check GPT +
  read `lk`, no writes. Proves the transport + handshake + SLA + DA on
  real hardware with a recoverable phone.
- Kansas second (dead LK): read the other slot's LK, verify with liblk,
  single write to the bad slot, then fastboot check. Full 11-partition
  blankflash only after the single-write path proves out.
- SP Flash Tool on mom's PC stays the fallback throughout; a failed
  BROM run in-app never blocks the PC path.

#### Open questions (for Kyle, with the build decision)

1. Build order: after Val 1.1.0 lands, or start the BROM port now in
   parallel? (Recommendation in the spec: Val first — it is nearly done
   and BROM is a multi-thousand-line port, not a next build.)
2. DA+auth only, or also the exploit/crasher path later?
3. DA/auth storage: app-private folder via picker (as spec'd), or a
   named folder the MTK GUI can share?

## 10. Open questions for Kyle

1. App name + package name?
2. Include the raw fastboot console in v1, or keep v1 to the buttons only?
3. `fastboot boot` in v1 or hold it for v1.1?
4. Any partitions that should be blocked from flashing entirely (e.g. never
   allow flashing `preloader` from this app)?

## 11. Later (not ordered)

- mtkclient/BROM support — spec'd in §5.13; built as v1.1.0 (full blankflash incl. GPT rewrite), v1.1.1 (launch-crash fix), v1.1.2 (all-mode detection + Connect re-scan + DA fastboot flag), v1.1.3 (root USB transport, connect retry, BROM log files), v1.1.4 (disconnect-all-interfaces claim fix for the cdc_acm EBUSY — FAILED on-device, 1.1.4 log 16:32), v1.1.5 (version-stamped BROM logs, descriptor dump, claim diagnostics, mtkclient's exact claim sequence), v1.1.6 (helper reinstalled from assets on every open — fixes the stale-helper bug that left the 1.1.3 helper running through 1.1.4/1.1.5 — + one-connect-run guard). Fastboot flag open gap CLOSED in 1.1.2.
- Multi-device, flash-all-from-folder, saved device profiles.
