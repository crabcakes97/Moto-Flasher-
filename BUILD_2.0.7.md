# Moto Flasher 2.0.7 — ADB tab

Kyle's order 2026-11-21: an ADB mode/section in the app that actually
works against a real device — a device booted into normal Android
(adbd running, USB debugging on) is the primary target, and it must
also work when the device is booted into a custom recovery (TWRP etc.,
which runs adbd too).

## What changed (source only, no engine changes)

- **New bottom-nav tab "ADB"** (Home / Flash / BROM / Val / ADB / More).
  The tab bar uses equal-weight cells, so a 6th short label fits cleanly;
  the log still lives on the More tab.
- **ADB tab UI** (`MainActivity.kt`): a command input, a "Run adb command"
  button (runs the input as raw adb args), a "Run as shell command
  (adb shell …)" button (passes the whole input as one argument so the
  device's own shell handles pipes/quotes/redirects), preset row buttons
  (Devices, Reboot, Reboot bootloader, Reboot recovery), a status line
  (last command + exit code + short summary), and a "Show log (More tab)"
  button. Neon theme, existing `UiStyle` helpers only.
- **Run path is the real one** — `adbRun()` uses the existing `Adb.run`
  (bundled aarch64 adb via the root shell, `HOME` pointed at the app
  dir, stdout + stderr captured line by line). Every line adb prints goes
  to the shared log verbatim, including errors. No device attached = adb's
  own "no devices/emulators found" / empty device list, never a fake
  success. No booted-Android-only checks anywhere: no system-property
  or booted-OS gate before running a command — the Devices list shows
  whatever adbd answers, normal Android or recovery.
- **Untouched:** flash engine, BROM/Penumbra/Antumbra, the 2.0.6 slot
  picker, package (`com.cronch.motoflasher`), label, credits. Startup
  status line now reads "adb ready (ADB tab + puller)".
- Version bump: versionCode 37, versionName 2.0.7 (manifest + app bar +
  build script output name).

## The adb binary — what shipped and where it came from

- **Bundled:** Termux `android-tools` **37.0.0p1-3** (aarch64), from
  https://packages.termux.dev/apt/termux-main/ — the same package the
  fastboot binary came from. Binary SHA-256 inside the APK:
  `c114b702ef0aa609950ba4b8e48176907bded52c8d043050b815f3b9cf3efa67`
  (identical to `toolchain/adb`; 3,532,296 B). Its 58 `.so` deps come
  from the Termux `abseil-cpp` / `brotli` / `liblz4` / `libprotobuf` /
  `zstd` packages (see `toolchain/adb-NOTES.md`); `libc.so` / `libm.so` /
  `libdl.so` / `liblog.so` come from the phone's own system.
- **Newest-version check (2026-11-21):** Google's
  `platform-tools-latest-linux.zip` downloaded today reports
  `Android Debug Bridge version 1.0.41 / Version 37.0.3-16887417`.
  The Termux Packages index re-downloaded today still lists
  `android-tools 37.0.0p1-3` as the latest aarch64 build — so the
  bundled adb is the **newest aarch64 build available from this trusted
  source**, same major release (37) and same adb protocol (**1.0.41**)
  as Google's 37.0.3; the gap is a patch level (.0 vs .3), not a
  different adb generation. No older or random build was substituted.
  The x86_64 adb from Google's download does NOT run on the phone and
  was used only to establish the newest version number.

## Execution proof (on this VM, before shipping)

The binary is an Android aarch64 binary (`/system/bin/linker64`
interp) and cannot run natively here, so it was run under
`qemu-aarch64-static` with a sysroot built from the LineageOS 23.2 GSI
system image on disk (`linker64` + bionic `libc`/`libm`/`libdl`/`liblog`
from the runtime APEX payload, plus the 58 bundled libs). Scratch:
`~/workspace/adb-verify/` (sysroot kept for re-runs).

Exact output:

```
$ qemu-aarch64-static -L sysroot ./adb-arm64 version
Android Debug Bridge version 1.0.41
Version 37.0.0p1-android-tools
Installed as /home/hatch/workspace/adb-verify/adb-arm64
Running on Linux 7.0.0-44-generic (aarch64)

$ ./adb-arm64 start-server        (via binfmt_misc, QEMU_LD_PREFIX=sysroot)
* daemon not running; starting now at tcp:5037
* daemon started successfully

$ ./adb-arm64 devices
List of devices attached
```

`start-server` succeeds — the daemon starts and ACKs. `devices` with
nothing attached prints the plain empty list (this is the exact output
the app shows when no device is attached). Note: the first
`start-server` attempt under plain qemu failed with "execl returned -1:
Exec format error" because the adb daemon re-execs itself and the host
kernel has no aarch64 interpreter — that is a qemu artifact, not a
binary defect; registering `qemu-aarch64-static` via binfmt_misc (with
the kernel-decoded `\xNN` escape form) made the re-exec work. The
registration was removed afterwards. On the aarch64 phone the re-exec
is native and needs no such workaround.

## Build + verify

- Built with `build_motoflasher.sh` (aapt2 + kotlinc 2.4.20 + d8, same
  hand build as 2.0.6). Only pre-existing kotlinc warnings in untouched
  Val files.
- `MotoFlasher-2.0.7.apk`: **10,775,413 B**,
  SHA-256 `64dd20aac2d035e3e53ba854c4d6a30d7fa5c38f254105804f9fc32a6883abcc`
- Badging: package `com.cronch.motoflasher`, versionCode **37**,
  versionName **2.0.7**, minSdk 28 / targetSdk 34.
- `apksigner verify`: **PASS**. Cert SHA-256
  `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1` —
  identical to 2.0.6.
- `assets/adb` inside the APK: SHA-256 `c114b702…` (matches the staged
  Termux binary); `assets/lib/libz.so.1` present.
- DEX: `adbRun` method 4 refs in 2.0.7, 0 in 2.0.6; new ADB UI strings
  ("Run adb command", "Run as shell command (adb shell …)",
  "No adb command run yet.", "Show log (More tab)",
  "ADB — device commands") present in 2.0.7, 0 in 2.0.6.
- 2.0.6 APK untouched (SHA `e5c5b25e…` unchanged).

## On-device test for Kyle

1. Plug the Moto into the S26 FE over OTG, USB debugging ON.
2. ADB tab → tap **Devices**. The Moto's serial must appear in the
   status line and the log. First time on that Moto, accept the
   "Allow USB debugging?" prompt on the Moto itself.
3. Same test booted into TWRP (recovery): Devices must still list it.
4. Try the Shell button with `getprop ro.build.version.release` — the
   version must print in the log.
5. If nothing is attached, Devices must say "none attached" — never a
   fake success.

## What is NOT verified on the VM

Real-device ADB over OTG (a Moto answering adbd, the USB-debugging
auth prompt, and `adb shell su` on the target) cannot be tested here —
there is no phone attached to this VM. The binary is proven to execute
and start its server; the on-device test above is still required.
