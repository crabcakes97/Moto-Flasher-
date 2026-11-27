# Moto Flasher 2.0.8 — "super flasher" (BUILD_2.0.8.md)

Kyle's order 2026-11-24 ~02:46 CST (+ additions 02:48, 02:49, 02:59, 03:00 CST).
Built 2026-11-24 ~09:10 CST. 2.0.7 APK untouched.

## Deliverable

- **APK:** `MotoFlasher-2.0.8.apk` — **10,783,605 bytes**
- **SHA-256:** `ab040af8925a6a83a319c82923e46b12128f32665e29cf99834d6a579962ee22`
- **versionCode 38, versionName "2.0.8"**, package `com.cronch.motoflasher`
- **minSdk 28 / targetSdk 34**, only permission WAKE_LOCK
- **Signing cert SHA-256:** `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1`
  (identical to 2.0.5 / 2.0.6 / 2.0.7 — continuity confirmed by apksigner)
- **2.0.7 APK untouched:** SHA-256 still
  `64dd20aac2d035e3e53ba854c4d6a30d7fa5c38f254105804f9fc32a6883abcc`
- Do NOT upload / deliver a link from this note — the main agent handles delivery.

## What changed

### 1. Erase replaces format (the core fix)
The Moto's fastboot has NO `format` command — verified by parsing the
fastboot command table in the factory LK (no `format` entry; `format`
appears only as substrings). That is why `fastboot format cache` /
`format userdata` failed on 2.0.7 ("Formatting is not supported").
- The "Format (wipes the partition)" section is now "Erase (wipes the
  partition)": one-tap `fastboot erase userdata / cache / boot / recovery`
  (confirm first, real error output on failure, never fake success).
- `formatPart()` deleted; `erasePartNamed()` replaces it.
- `flashfile.xml` `format` steps are mapped to erase steps with a log line
  saying so (previously they were UNSUPPORTED and stopped the run).
- Erase is a first-class step type in Flash-all: "Add erase step" box +
  button appends `erase <partition>` to the loaded step list.
- LK-derived presets in the More-tab raw-command section (tap to fill,
  then Run): erase, getvar all, reboot, reboot bootloader, reboot
  recovery, continue, set_active a/b, oem hwid. Full runnable list is
  on the Extras tab.

### 2. Log auto-scroll fix
FOLLOW LOG now follows on EVERY appended line. Root cause: `logLine()`
posted the scroll once, which could run before the TextView re-measured
(one line short), and when the More tab was hidden `logText.bottom` was
0, so the scroll went nowhere with no re-scroll on return. Fix: one
`scrollLogToBottom()` helper (double-posted: after layout, then after
any re-measure), used by `logLine()`, the Follow button, and `showTab()`
(re-scrolls when switching back to the More tab while follow is on).
The user-scroll-up behavior is unchanged (scrolling up still pauses
follow; scrolling back to the bottom resumes it).

### 3. Themes
Three themes, persisted in SharedPreferences ("motoflasher" / "theme"),
applied before any UI is built, picked in the More tab (recreate on
pick):
- **Neon** (default) — the 2.0.1 look, untouched.
- **Ember** — dark red/orange.
- **Frost** — dark blue/cyan.
Palette colours in `UiStyle` are now `var`s swapped by `applyTheme()`;
all builders read them at call time.

### 4. Less text / no AI-sounding copy
Trimmed the verbose bodies: target-slot line, Flash-partitions line,
TWRP line, vbmeta disabler, Val flow intro + follow-up, Puller intro,
ADB tab intro, custom-key line, flash-all confirm. Real info kept
(partition names, real errors, version, root status). No new marketing
copy added.

### 5. Kansas removed — Nevada-only
All Kansas references removed from the app source (whole-tree grep for
`kansas|XT2513` over `src/` is clean; DEX contains 0 "kansas" strings):
`LkAnalyzer.kt`, `BromApi.kt`, `BromScatter.kt` (ground truth is now
the Nevada scatter: 91 partition_index entries per layout, 86 GPT
entries, empty proinfo.bin — all verified against
`~/workspace/blankflash-nevada/blankflash/`), `BromUi.kt`,
`PenumbraSession.kt`. Historical docs (`SPEC.md`, `BUILD_2.0.3.md`)
were left alone — they are records of past work, not app behavior.
Nevada (Moto G Play 2026, XT2615-1) fully supported; 2.0.6 slot picker
and 2.0.7 ADB tab untouched by the removal.

### 6. Cooler GUI
Same neon identity, sharper: cards radius 16→18dp, padding 16→18dp;
buttons radius 12→14dp, min height 48→52dp; active tab-bar cell now
gets a filled background (not just a coloured label + underline);
tab-bar padding tightened. No generic spinners added; waiting states
still show 🐔 (Home banner stays plain, no emoji, per Kyle's order).

### 7. Extras tab (new, 7th tab: Home / Flash / BROM / Val / ADB / Extras / More)
ALL commands pulled from the LK — verified by parsing the command
tables, not just strings. Full extraction notes:
`LK_EXTRACTION_2.0.8.md`. Sources: factory LK
`~/workspace/blankflash-nevada/blankflash/lk.img` (3,337,840 B) AND
Kyle's neon v2 LK `~/workspace/user/files/nevada-lk-neon-v2-signed.img`
(3,337,936 B). **The tables are IDENTICAL in both LKs** (same 13
fastboot + same 28 oem entries, same offsets/handlers); only the
CERT2/signature region differs.
- **Fastboot commands (13, dispatch-table verified):** download, flash,
  getvar, erase, boot, reboot, reboot-bootloader, oem, continue,
  flashing, set_active, reboot-recovery, reboot-fastboot, plus
  `partition` (table entry, not a client command — running it shows the
  real error) and `super` (a PARTITION name in the LK, not a command —
  listed with an honest note; running it shows the real answer).
  `flashing` is expanded to its three runnable forms (unlock / lock /
  get_unlock_ability).
- **OEM subcommands (28, dispatch-table verified):** config, hw,
  gpt_test, fb_mode_set, fb_mode_clear, regex, partition, logdump,
  blankflash, partitions, show_screen, backlight, logo, ramdump,
  off-mode-charge, led, display, batt-voltage, hwid, ab_reset,
  cid_prov_req, shwi_test, ssm_test, unlock, lock, get_unlock_data,
  prod_fuse, read_sv.
- **OEM names, strings-only (17, NOT in any dispatch table — may not
  run):** cdms, dump_pllk_log, get_socid, mrdump, mrdump_chkimg,
  mrdump_fallocate, mrdump_out_set, norts_mrdump, printk-ratelimit,
  ultraflash, ultraflash_en, usb2jtag, hwid_list, hwid_list_all, locked,
  utags_ext, keystore. Each is still runnable — tapping it shows the
  LK's real answer.
- Every entry is runnable (tap → real `fastboot` run, output verbatim
  in the log, never fake success). Commands needing an argument use the
  Extras argument box (partition / getvar name / slot). `flash` opens
  the file picker for the typed partition and flashes with the usual
  confirm; `boot` picks an image and boots it right away (same confirm
  as "Boot picked image"). Dangerous commands ask first (confirm dialog):
  erase, unlock, lock, flashing unlock/lock, oem unlock/lock/blankflash/
  ab_reset/prod_fuse/cid_prov_req.
- No `format` anywhere in Extras (neither LK has it).

### 8. September firmware warning (Kyle's exact words, 2026-11-24 ~02:59 CST)
> "September security patch won't work — it's patched."
Shown in the Extras tab (amber) and as a short line on the Home tab.
No invented detail (no patch-level number, no CVE, no claim about
which vulnerability).

### 9. ADB fix (item 10)
Kyle's on-device result (2.0.7): `adb devices` runs three times, daemon
up, exit 0, list EMPTY — in TWRP and in booted Android — while fastboot
works over the same OTG link. The daemon is blind to USB, not dead.
**Investigation (VM, readelf on both bundled binaries):**
- Both adb and fastboot use libusb against `/dev/bus/usb`, statically
  linked (`usb_libusb.cpp`, `usb_libusb_hotplug.cpp` strings in adb;
  `LIBUSB_ERROR_*` in fastboot). No missing `.so`: the transitive
  DT_NEEDED closure is fully covered by the 58 bundled libs + system
  bionic libs. So the difference is HOW they run, not the USB backend:
  fastboot is a fresh foreground process per command and enumerates USB
  on the spot; adb is a persistent daemon. A daemon started before the
  Moto was plugged in — or a stale one left over from an earlier session,
  possibly started without root — never sees the device: libusb's
  in-house hotplug thread does not reliably pick up a later plug-in
  here, and a client just talks to whatever daemon already owns port
  5037. (Not an auth problem: TWRP runs adbd with no prompt, and an
  unauthorized device would list its serial as "unauthorized", not an
  empty list.)
**Fix:**
- `Devices` (ADB tab + Val-tab check) now runs against a FRESH server:
  `kill-server`, then `adb devices -l`. A fresh daemon enumerates USB
  at start with the device already plugged in — no hotplug needed.
- New **Diagnose ADB** button: version → kill-server → fresh
  start-server → USB visibility (`ls /dev/bus/usb`, node count, adb
  daemon processes from `ps`) → `adb devices -l`, everything logged
  verbatim, summary in the status line. New **Kill adb server** button.
- Empty device list is NEVER reported as success: the status line says
  "Devices: NONE — server is up but sees nothing. Run Diagnose ADB…"
  instead of "exit 0".
- `Adb.install()` now re-copies the binary + all libs every time (a
  truncated/corrupt lib used to be skipped forever because it already
  existed). HOME is checked for writability AS ROOT once per process
  (touch test via su); if the app-owned `adb-home` is not writable as
  root, it falls back to `/data/local/tmp/motoflasher-adb-home`.
- Bundled adb binary unchanged (Termux android-tools 37.0.0p1-3,
  SHA-256 `c114b702…`, same as 2.0.7).

## Standing rules kept
No DA checks, no battery gate, no greyed-out buttons, real error
messages, verbose logs, live-scrolling log. AGPL-3.0 credits kept
(Penumbra by shomykohai, github.com/shomykohai/penumbra,
LICENSE.penumbra). On-screen credits do NOT mention Kyle. Signing cert
identical. minSdk 28 / targetSdk 34.

## Verified on VM
- Build: aapt2 + kotlinc 2.4.20 + d8, clean (warnings only, all
  pre-existing unchecked casts). One build error during development
  (two `Fastboot.runSu` calls passed the callback as a trailing lambda,
  which binds to `abortOn`, not `onLine`) — fixed, rebuilt clean.
- apksigner verify: cert `b6efa857…`, package/version/sdk as above.
- aapt2 dump badging: versionCode 38, versionName 2.0.8, sdkVersion 28,
  targetSdkVersion 34.
- DEX (dexdump, piped): 1,633 classes; MainActivity / UiStyle / Adb
  present. Strings confirm new code: "September security patch won't
  work", "Diagnose ADB", "ADB diagnose", "Add erase step", "LK commands",
  "strings only, unproven", "devicesFresh", "USB visibility", "fresh USB
  scan", theme names neon/ember/frost, OEM names (fb_mode_set,
  blankflash, get_unlock_data, ramdump, ultraflash, hwid_list, …).
  Old code gone: "Format userdata/cache/boot", "fastboot format" — 0
  hits. "kansas" in DEX — 0.
- Assets in APK: adb, fastboot, antumbra (SHA-pinned `c0257dd7…`,
  6,213,928 B), rootusb, 58 libs, Exo2.ttf, logo files.
- Bundled adb under qemu-aarch64-static (LineageOS 23.2 GSI sysroot):
  `adb version` → 1.0.41 / 37.0.0p1-android-tools, exit 0; `nodaemon
  server` + `adb devices` / `devices -l` → "List of devices attached"
  (empty, no USB in the VM), exit 0. NOTE: plain `start-server` fails
  under qemu ("execl returned -1: Exec format error") because the daemon
  re-execs itself and the host kernel cannot exec an aarch64 binary —
  a qemu-only artifact. On the phone the daemon starts fine (Kyle's
  2.0.7 screenshot proves the daemon answers on-device). The nodaemon
  path exercises the same daemon code.
- 2.0.7 APK untouched (SHA `64dd20aa…` before and after).

## Still needs Kyle's on-device test
- **ADB (the fix):** Moto via OTG, USB debugging on. ADB tab →
  **Devices** must list the Moto's serial. Repeat in TWRP. `Shell
  getprop ro.build.version.release` must print the version. If Devices
  is still empty, run **Diagnose ADB** and send the log: the USB
  visibility lines will show whether the device nodes are visible to
  the root shell at all (nodes listed but adb empty = adb's USB
  enumeration is the fault; no nodes listed = OTG device not visible
  to the host).
- **Erase:** Flash tab → Erase userdata / Erase cache must run
  `fastboot erase …` (the old `format` steps failed; erase is what the
  Moto supports). Flash-all with a firmware folder: `format` steps in a
  flashfile.xml must become erase steps (log says so). "Add erase step"
  must append a working erase step.
- **Extras:** open the Extras tab, run a safe command (`getvar all`,
  `oem hwid`, `oem partitions`) — real output in the log. Dangerous
  ones (erase / unlock / lock / blankflash) must ask first.
- **Themes:** More tab → Ember / Frost must re-skin the whole app and
  survive relaunch; Neon must look exactly like 2.0.7.
- **Log follow:** More tab → run a long command (e.g. `getvar all`),
  FOLLOW LOG: ON must keep the log pinned to the bottom without
  tapping; switching to another tab and back must re-scroll to bottom.
- **Slot picker + ADB tab regression check:** 2.0.6 slot picker and
  2.0.7 ADB tab behavior unchanged (slot suffixes, presets, shell).
- **September warning** visible on Home + Extras.
- **Kansas:** no Kansas anywhere in the UI.
