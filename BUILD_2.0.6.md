# Moto Flasher 2.0.6 — slot A/B picker for the Flash tab

Kyle's order 2026-11-21 ~04:02 CST (screenshot of 2.0.1's Flash tab):
"also need option to pick slot a or b". The Flash-all section had no slot
choice — every flash went to the active slot's partition name (fastboot
resolves `flash boot` to the current slot).

## What changed (MainActivity.kt only)

- **Slot picker in the "Flash all (pick what flashes)" section**, above the
  "Pick firmware folder" button: a dim explainer line plus a row of three
  buttons — **Active** / **Slot A** / **Slot B**. The picked one is the
  magenta action button, the other two are dark ghost buttons (UiStyle
  rowActionButton / rowGhostButton — the app's existing neon theme).
  Default is **Active** (no suffix, exactly the old behavior), and the pick
  is session-only — it resets to Active on every launch, so a stale pick
  can never surprise-flash the other slot.
- **The choice changes the real commands.** In `runFlashItem()` the flash
  branch now runs `fastboot flash <slotPartition(part)> <file>` instead of
  `fastboot flash <part> <file>`, and logs `slot Slot A: boot -> boot_a`
  when a name is suffixed. Verified by reading the code path: the suffix
  is applied at the single point where the partition name enters the
  fastboot args — not just in the UI.
- **The checkbox labels show the truth.** `FlashItem.label` became a `var`;
  `displayLabel()` rebuilds each flash step's label from its base partition
  + the current slot, so "flash boot <- boot.img" reads "flash boot_a <-
  boot.img" when Slot A is picked. Labels are re-built both when the slot
  changes (checked state kept) and when a folder is loaded after a slot
  was already picked. Erase / oem / reboot / getvar steps are untouched —
  only flash commands get the suffix.
- **Which partitions get the suffix:** the base names that exist as _a/_b
  pairs in the Nevada GPT (MT6835_Android_scatter_blankflash.xml) —
  boot, dtbo, init_boot, vendor_boot, vbmeta, vbmeta_system, lk, logo,
  md1img, connsys_bt, connsys_gnss, connsys_wifi, dpm, gz, mcf_ota, mcupm,
  mot_reserved, pi_img, scp, spmfw, sspm, tee, tzapp, vcp. (Note: logo IS
  A/B on this device — logo_a/logo_b in the GPT.) Everything else flashes
  unchanged: super, system, vendor, product, odm, oem (logical partitions
  inside super), modem, dsp, bluetooth, preloader, recovery, vbmeta_vendor
  (not present on this device). A name that is already suffixed (e.g. lk_b
  from a flashfile.xml) is never double-suffixed.
- **Version bump:** versionCode 36, versionName 2.0.6 (manifest + the
  app-bar version string, which was still hardcoded "2.0.1" from the 2.0.1
  shell — 2.0.2–2.0.5 all displayed 2.0.1 in the app bar). Package
  (com.cronch.motoflasher) and label ("Moto Flasher") unchanged.

## Not changed

Flash engine, BROM / Penumbra / Antumbra code, fastboot binary, "Flash one
partition", the one-tap quick-flash buttons, erase, the More tab's "Set
slot A / Set slot B" (those change the *active* slot; this picker chooses
which slot a flash-all run *targets* without switching it), credits, and
everything else. The 2.0.5 APKs on disk are untouched; the build script
now outputs MotoFlasher-2.0.6.apk.

## Build

`./build_motoflasher.sh` (same hand build as 2.0.5: aapt2 + kotlinc 2.4.20
+ d8, same keystore). Verify: aapt2 dump badging (package
com.cronch.motoflasher, versionCode 36, versionName 2.0.6), apksigner
verify, cert SHA-256 must be b6efa857… (same as 2.0.5).

## On-device test (for Kyle)

Install 2.0.6 alongside 2.0.5 (same package — it upgrades in place; the
MTK Edition is a different package and stays separate). Open the Flash
tab, pick Slot A, pick a firmware folder: the steps should read
"flash boot_a <- boot.img", "flash vbmeta_a <- vbmeta.img",
"flash init_boot_a <- init_boot.img", while "flash super <- super.img"
and "flash logo_a <- logo.img" (logo IS A/B) show correctly. Picking
Active puts every label back to the plain names.
