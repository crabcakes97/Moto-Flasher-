# Moto Flasher

Flash MediaTek Motorolas from your phone. No PC. No "just install these 47 drivers and pray." Your rooted Android is the PC now.

Moto Flasher runs on a rooted Android phone and talks to a target Moto over a USB OTG cable. It does BROM flashing (via Penumbra/Antumbra), fastboot, ADB, and it has the Val Protocol LK patcher built in — the whole unlock flow, in-app, no Termux gymnastics required.

## What you need

- A rooted Android phone to run this on (aarch64, USB OTG capable). It was built and tested on a Galaxy S26 FE, but anything rooted with OTG should work.
- A USB OTG cable/adapter.
- A MediaTek Motorola to flash. (If you flash the wrong phone's firmware to it, that's between you and the phone. It will not forgive you.)
- For BROM work: a DA file and auth file for your target. The app takes any DA you hand it — no checks, no lectures. That's on purpose. Pick the right one anyway.

## The tabs

- **HOME** — status (root, fastboot, adb, antumbra versions) and the big buttons.
- **FLASH** — fastboot flashing. Pick a firmware folder or flashfile.xml and flash-all, flash one partition, quick-flash buttons, TWRP, vbmeta verity disabler. There's a slot picker (Active / A / B) — pick B and it actually flashes B. Revolutionary, we know.
- **BROM** — preloader/BROM mode via Antumbra. Blankflash, read/write partitions, write by offset for when the DA only exposes four partitions and acts like the rest don't exist.
- **VAL** — the Val Protocol LK patcher. Pull the LK off the phone, analyze, patch (unlock-serial, FRP skip, modem unlock, 23 presets), re-sign CERT2, flash it back, generate the unlock key. The log shows the full PC-style output so you can see exactly what got patched.
- **ADB** — run adb against a booted phone or TWRP. Devices, shell commands, reboot presets, and a Diagnose button for when adb is being adb about it.
- **EXTRAS** — every fastboot/oem command pulled out of the actual LK command tables. Experimental commands. Some of them do things. Some of them do *things*. You were warned.
- **MORE** — the log (scrollable, follows live), themes, credits.

## Val unlock, the short version

1. Patch the LK with the `unlock-serial` preset and your secret.
2. Flash the patched LK (both slots on A/B devices).
3. Generate the key from your secret + the phone's serial number.
4. `fastboot oem unlock <key>` — type it by hand if paste acts up.

**Heads up:** units on the September security patch can't be unlocked with Val — Motorola patched it. The app will tell you, but now you know too.

## Building from source

You need a JDK, the Android SDK build-tools, and Kotlin. Then:

```
./build_motoflasher.sh
```

The script signs with `motoflasher.keystore` if present, or generates one. If it generates a fresh one, your build has a different signing cert than the released APKs — Android will make you uninstall before installing yours over an official build. That's not a bug, that's just how signing works.

## Warnings, the honest kind

- Flashing the wrong thing to the wrong partition can brick the target phone. BROM can usually bring it back. "Usually" is doing a lot of work in that sentence.
- The app does not verify CERT2 signatures in-app yet. It re-signs and re-parses, and the log shows you the hashes — but if you want the full check, run the PC verifier before you flash.
- Erase means erase. There is no undo. There is no "are you sure" that saves you from yourself — well, there is a confirm dialog, but you know how that goes.

## Credits

Made with love by [crabcakes97](https://github.com/crabcakes97).

- BROM engine: [Penumbra/Antumbra](https://github.com/shomykohai/penumbra) by Shomy — AGPL-3.0.
- LK container handling: [liblk](https://github.com/R0rt1z2/liblk) by Roger Ortiz.
- Val Protocol: the LK patcher this app's Val tab is ported from.
- Capstone disassembly engine, and the usual Android tools (adb/fastboot) from the Android Open Source Project.

Long live the CRONCH. 🐔
