# Moto Flasher 2.0.0 — Credits & License

## This app
Moto Flasher 2.0.0 — built for Kyle — https://github.com/crabcakes97

The app itself is licensed under the **GNU Affero General Public
License v3.0 (AGPL-3.0)** because it bundles the Antumbra binary (see
below). The full license text is `LICENSE.penumbra` (the AGPL-3.0
text, which covers both the bundled binary and this app).

## BROM engine — Penumbra / Antumbra
- Project: Penumbra — https://github.com/shomykohai/penumbra
- Author: Shomy (shomykohai)
- Version bundled: **v2.0.0** (commit `f721520`), unmodified
- Binary: `assets/antumbra` (static aarch64 release build),
  6,304,752 bytes,
  SHA-256 `11d2617dc97d4d6970a121b0cd9b7fa293bbb01a4941c98033ed6bbb460a9842`
- License: AGPL-3.0-or-later. Source:
  https://github.com/shomykohai/penumbra (tag v2.0.0).
- The binary is shipped unmodified and driven as a child process
  (`su -c antumbra …`); no Penumbra source code is copied into this
  app's own sources. Per AGPL-3.0, the Corresponding Source for the
  bundled binary is the upstream Penumbra repository at the pinned
  tag above, and the app's own source is offered under AGPL-3.0.

## Other bundled components
- fastboot / adb binaries + shared libraries: Android platform tools
  (Apache-2.0), staged from the app's toolchain.
- rootusb helper: built in-app from `src/native/rootusb.c`.
- Exo 2 font: SIL Open Font License 1.1
  (https://github.com/google/fonts — ofl/exo2).
- Capstone native libs (Val Protocol analyzer): BSD-licensed
  (https://www.capstone-engine.org/).

## What this app does NOT contain
No "made by AI" branding. No bundled DA / auth files — the user picks
their own. No exploit / crasher path.
