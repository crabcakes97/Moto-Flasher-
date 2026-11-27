# Moto Flasher toolchain — fastboot binary + libs

Source: Termux `android-tools` 37.0.0p1-3 (aarch64), same package Kyle proved
working on the S26 FE tonight (Termux `fastboot devices` saw the Moto over OTG).
Repo: https://packages.termux.dev/apt/termux-main/
`.deb` SHA-256 verified against the repo Packages index:
`534e90d338bd432c06677cb402d0e9ed98a82a74de4bf5f90e643f15d8fc4274`

Provenance kept on disk: `debs/` (the four .debs) and `extract*/` (unpacked trees).

## Staged files

| File | Size | SHA-256 |
|---|---|---|
| `toolchain/fastboot` | 2,444,432 | `507457f9ad0915daf805577f2ed2c0baeb08a304d2090fb17910ab794b44803a` |
| `toolchain/libs/libc++_shared.so` | 1,423,696 | `05bef9d3901910c3ff8ab450b03bc8b28383d363a2290f2f7b9aed7331ea6ff7` |
| `toolchain/libs/libfmt.so` | 166,608 | `9b59cf1f5c53e7dcaa890880e1dce473bb1712a4509ba413b228938e070500c4` |
| `toolchain/libs/libz.so.1` | 74,056 | `df5e95e3ea1ffb8f2e0dcebfc43a6c0824b6aebec11752ad8f8764123e24f8a6` |

Total bundle: ~4.1 MB (binary + 3 libs).

`file` output: `ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV),
dynamically linked, interpreter /system/bin/linker64, stripped`.
`strings` sanity: contains `37.0.0p1` version string and `FastbootError`
symbols. It was NOT executed on this Linux VM — it is an Android binary and
cannot run here (no `/system/bin/linker64`); on-device test on the S26 FE is
still required.

## NEEDED libraries (from `readelf -d fastboot`)

| Library | Comes from | Action |
|---|---|---|
| `liblog.so` | Android system (`/system/lib64/`) | Nothing — present on every Android phone |
| `libc.so` | Android system (bionic) | Nothing |
| `libdl.so` | Android system (bionic shim) | Nothing |
| `libz.so.1` | Termux `zlib` 1.4 | BUNDLED — see note below |
| `libfmt.so` | Termux `fmt` 1:12.2.0 | BUNDLED in `libs/` |
| `libc++_shared.so` | Termux `libc++` 30 | BUNDLED in `libs/` |

Transitive deps of the bundled libs (checked with `readelf -d` on each):
- `libfmt.so` → `libc++_shared.so` (bundled), `libdl.so`, `libc.so` (system)
- `libc++_shared.so` → `libc.so`, `libm.so`, `libdl.so` (all system)
- `libz.so.1` (SONAME `libz.so.1`, file `libz.so.1.4`) → `libc.so` (system)

No other transitive deps. No `libusb` NEEDED — libusb is statically linked
into the binary (LIBUSB_* error strings are present in `strings`, no
`libusb.so` reference, no `dlopen` of any `.so` found). Nothing else is
dlopen'd.

### Why libz.so.1 must be bundled

Android ships `/system/lib64/libz.so` (SONAME `libz.so`), NOT `libz.so.1`.
The loader matches by SONAME, so the system copy can never satisfy fastboot's
`libz.so.1` NEEDED entry — the Termux copy must ship in the app. Same logic
applies to `libfmt.so` and `libc++_shared.so` (no system copies at all).

## Loader / environment — how to run it on-device as root

- INTERP is `/system/bin/linker64` — exists on the phone, nothing to bundle.
- The binary has a baked-in RUNPATH of `/data/data/com.termux/files/usr/lib`
  (the Termux lib dir). That path will NOT exist for the app, so it is useless
  in the app — the bundled lib dir must be supplied at launch instead.
- Launch form (app extracts binary + `libs/` to its private dir, chmod 755,
  then as root):

  ```
  su -c 'LD_LIBRARY_PATH=<app-lib-dir> <app-dir>/fastboot <args>'
  ```

  Set `LD_LIBRARY_PATH` *inside* the `su -c '...'` string, so it is applied
  after su's own environment handling. Do not rely on the caller's environment
  surviving `su`.
- Fallback if the linker ignores `LD_LIBRARY_PATH` under su: invoke the loader
  directly, which bypasses env handling entirely:

  ```
  su -c '/system/bin/linker64 --library-path <app-lib-dir> <app-dir>/fastboot <args>'
  ```

  (Verify `--library-path` is accepted by the S26 FE's linker64 on-device;
  the `LD_LIBRARY_PATH` form is the primary path.)
- No other Termux env is needed: no `PREFIX`, no `TERMUX_*`, no config files.
  fastboot is self-contained apart from the 3 bundled libs + system bionic.
- USB access: as root, `/dev/bus/usb/*` is directly accessible — no Android
  USB permission dialog applies to the root path. (Termux proved this tonight.)
- Still to verify on-device: that the staged binary + these 3 libs actually
  start under `su` on the S26 FE (`fastboot devices` showing the Moto), and
  which of the two launch forms works. Do that before the app ships them.
