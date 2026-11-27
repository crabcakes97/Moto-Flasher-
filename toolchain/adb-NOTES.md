# Moto Flasher toolchain — adb binary + libs

Source: Termux `android-tools` 37.0.0p1-3 (aarch64) — same package the
fastboot binary came from (see `NOTES.md`). The adb binary was already on
disk inside that package's extract tree; the extra libraries it needs were
downloaded from the Termux repo and staged here.
Repo: https://packages.termux.dev/apt/termux-main/
Packages index re-downloaded 2026-11-08 (3,030 packages) and the five
filenames + SHA-256 below match the on-disk index from the fastboot staging.

Provenance kept on disk: `adb-debs/` (the five .debs) and `adb-extract-*/`
(unpacked trees).

## Source packages

| Package | Version | .deb SHA-256 |
|---|---|---|
| `abseil-cpp` | 20260817.0 | `b698963bb7096494ad928ce89cd869a53e6adf0b05f5f407b40fda9c85c6e2b0` |
| `brotli` | 1.2.1 | `4fba29438b2b44f8470d52173b4548e938542dc2fc632f8699be5a284cbb9485` |
| `liblz4` | 1.11.0 | `3befcdf8c984a2a6c5d60e08544b14b404d8479ca6b1fda84412356985c16907` |
| `libprotobuf` | 2:36.2-1 | `daa3b9169e094644ba95c35d40a5cc283473144decc7dae8396c49cc51440815` |
| `zstd` | 1.5.7-1 | `e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3` |

All five .debs verified OK against the repo Packages index before extraction.

## Staged files

Binary: `toolchain/adb` — 3,532,296 B,
SHA-256 `c114b702ef0aa609950ba4b8e48176907bded52c8d043050b815f3b9cf3efa67`
(copied from `toolchain/extract/data/data/com.termux/files/usr/bin/adb`).

Libraries: `toolchain/adb-libs/` — 58 files, 8,455,736 B total.
Combined bundle (binary + libs): 11,988,032 B (~11.4 MiB).

Three of the 58 (`libc++_shared.so`, `libfmt.so`, `libz.so.1`) are byte-identical
copies of libs already staged for fastboot in `toolchain/libs/` — they are
duplicated into `adb-libs/` so the adb bundle stands alone; the app can dedupe
them at packaging time if it ships both binaries.

| File | Size | SHA-256 |
|---|---|---|
| `libabsl_base.so` | 43,320 | `b5f177a06b2bf17c78435c1605924bd5620e5a79f30459e7a6184bc1cc66124c` |
| `libabsl_city.so` | 6,568 | `5c3e20e3d2605821241a7f3ef916adbedbb46444fd49edeba1b79b6d5d0f4942` |
| `libabsl_cord.so` | 113,296 | `714265b88db187e1b1c020f7091b8294893802f85599c4c0a3c6f2ff32a05a07` |
| `libabsl_cord_internal.so` | 86,448 | `e434557c44ff910f7070c4e7a7042026b12311b8c6ca029982bd57bb5326b4b9` |
| `libabsl_cordz_functions.so` | 6,056 | `099286c7d80bcdb8bc7f026b6aacc9934ee060088df55ea50921ed23bb1bd59f` |
| `libabsl_cordz_handle.so` | 38,320 | `00feb8eddbc355a66c319b7db7403d07550ab61141d0f19fe87b80a965f31189` |
| `libabsl_cordz_info.so` | 43,944 | `94080e40464e5e44ed521d41273c98303304bf9ce74b8071156ceffdb8e06520` |
| `libabsl_crc32c.so` | 40,080 | `a702473871f0efb1c7c4e6de9ba0fa0885483c2f731912384d58dbbedaee27aa` |
| `libabsl_crc_cord_state.so` | 46,984 | `36e1d8ad79172db673ffcfbe38372312d77bb00c92e4f68242be3ed1e29eeefa` |
| `libabsl_crc_internal.so` | 41,936 | `c7ced5ac5ed9a528d0f519ad97c0f7e9e86222f334c2f065a79917cd3b905fb4` |
| `libabsl_die_if_null.so` | 6,192 | `bc59400f416dfd2fd91562edc695d3314b59093955b771544f12d7559eb149f3` |
| `libabsl_examine_stack.so` | 6,920 | `57390d37d02a19649804b5c49294c729d24fad810ad5c974d7984dcf711d2873` |
| `libabsl_exponential_biased.so` | 7,344 | `39524ce079a04ffff81dd3ab9ea049e974fbb59e758cef74f19b844a36fef63c` |
| `libabsl_hash.so` | 6,544 | `878e48d66d4347c2acdf3a07750ad115fc62d051e5fe1ddbb188bb81408dd580` |
| `libabsl_hashtablez_sampler.so` | 44,032 | `3ed130301c4da4259a44a970049d32839e6bcbf2a037f56083d8a9fdea029f9f` |
| `libabsl_int128.so` | 44,984 | `ed0716a64bf2c93a4850e910f95b789fd48d898287d08b7d77c35ef02afbc281` |
| `libabsl_kernel_timeout_internal.so` | 9,440 | `434fd519616aff404b0f1e75f520eadd006c044e3dc1db31749fcd9a4de409dd` |
| `libabsl_leak_check.so` | 4,896 | `18d3ae199d0b1c14e06bd247b22532a4447c5073ab590a59b47df49afd961aff` |
| `libabsl_log_globals.so` | 41,768 | `a4c42b09c6586bfc51c3a4067c0261a723c56d020605eef9049edc85dde42cb8` |
| `libabsl_log_internal_check_op.so` | 53,624 | `d7fbac5ed282c3bd4b8cf0755e6b4c9726570298689d3e0404e4e1c1984a9dd7` |
| `libabsl_log_internal_conditions.so` | 7,176 | `c00052f7da1bafb982ae2dfe5a4c4cc6398be6860cb9b4858dcea0bab36542f7` |
| `libabsl_log_internal_fnmatch.so` | 4,976 | `e09a90db36a8cbfcf7b8928b245422220420032aaf7afb0f495d9b0e4518bf59` |
| `libabsl_log_internal_format.so` | 9,256 | `d29071f0bd9a6292c73bdacc456f92c82b18aafa5c508de824318f63c735baf1` |
| `libabsl_log_internal_globals.so` | 8,808 | `144c3272d5cf332b4bc2449b39451e3a56c0e8c7c1d4f27c74698ee1e47a0ac2` |
| `libabsl_log_internal_log_sink_set.so` | 45,336 | `04edcbb500bd452ed7aa6b63d46afd78ab38035446559b95527111321b1574f1` |
| `libabsl_log_internal_message.so` | 78,264 | `63d1d273ac6cfd75bc000db48b750b90b17b402a911dc1276a20891f0709a3ca` |
| `libabsl_log_internal_nullguard.so` | 4,312 | `14a3ecef381306759067ab0634c77531c40d863cdfa8d2ab33d2062955e259cc` |
| `libabsl_log_internal_proto.so` | 7,464 | `42fd59832986c126d4cb5490e6ea6de6d67827ff816761d63a31886a91ced61f` |
| `libabsl_log_internal_structured_proto.so` | 7,272 | `3a79e9968290895678c545d4d4010d2bb8222b0cdb5479203c6dab19df9a4534` |
| `libabsl_log_sink.so` | 5,072 | `f788a5fd5013b54a9e8135f1b1a7604aef0df80a843159f470aa2c4217b4990c` |
| `libabsl_malloc_internal.so` | 46,136 | `d537f5d290a30d0648c3aecf77b7e57ca6d966052ada4fdbea9b20a10284d21d` |
| `libabsl_raw_hash_set.so` | 33,328 | `672c04053111b5fe89c3d108769e24fb9cd6a11cf1ce77cb04f9658b847fd3b8` |
| `libabsl_raw_logging_internal.so` | 36,320 | `3a503c4fdcea77910a5013f503ea4f0af62f5da5ee0623e5b36007c716e3ad77` |
| `libabsl_spinlock_wait.so` | 34,256 | `506a085e40238a832db930eb9f1aeb6d898c73cb240211a70df49fed78986e26` |
| `libabsl_stacktrace.so` | 6,856 | `457d5a89721401ac17213f4677fdd621df7b1e0f5261b91740e1590acacba493` |
| `libabsl_status.so` | 72,312 | `507ad72b792936a5ae3fd90926d264ccb920ca87e22a2b8e5c395e8de170803e` |
| `libabsl_statusor.so` | 43,312 | `efb4027752f3f2a31b3f1d33799af3d5cb4f44407b2b9367de3f1261e0b5b58c` |
| `libabsl_str_format_internal.so` | 132,312 | `2fa948ec927a9fb8804390d5d0514468a546d76ec105d6bca5e93b0394f975e6` |
| `libabsl_strerror.so` | 34,736 | `60b87bd0695d67ed353ea13a78ed031a8798cb29f23dbf0d27ebe08b040e41c1` |
| `libabsl_strings.so` | 162,416 | `9d5bf81075a89a15ee58c0fc9d778c34eb9b8f38ba50746f0760195c180bebc0` |
| `libabsl_strings_internal.so` | 38,120 | `05c308c20afdb9e372a411a91b7279d32ee13b907e9a4c63d5cceb0c03bee0a6` |
| `libabsl_symbolize.so` | 5,080 | `ef8a9a9dc6c3856e1680de27c91958a4b0588f18b69bd93fa3accc340c228f58` |
| `libabsl_synchronization.so` | 85,552 | `937208694f704c786dbd5f102843b1ec091ee6a8df694bb71d60d0c4d60307c0` |
| `libabsl_throw_delegate.so` | 42,040 | `01aac4721151ee412f782b34761c03d06451eb3562adeb863b1b9cb0bb97ba91` |
| `libabsl_time.so` | 92,680 | `537861ae4280cd0677377ca017b0ecd90c8c35d15b3d18b02126dba8f4cc00ba` |
| `libabsl_time_zone.so` | 138,664 | `e5b127eb8467a5850fba9e4f0d2d3129be4809f5b0fd7e0feed3b90696d039df` |
| `libabsl_tracing_internal.so` | 4,352 | `cbe054898ac1e15c7406ba2de85661a6d9250e4aaa51e1c1fba88589ac829583` |
| `libabsl_vlog_config_internal.so` | 51,448 | `1e3f27c02b7d4ba85527e2e17799e0dd0312045522989c98c20b667189d34eea` |
| `libbrotlicommon.so` | 133,352 | `7b1e560c3593f1d530a081cfde59867cb90e189be107da5255d6f94da8cac98b` |
| `libbrotlidec.so` | 45,472 | `1957fa40857e698849e97683193b15c6e26e1993fd4b33f2191792f56340f847` |
| `libbrotlienc.so` | 684,488 | `6c03fc56cc84f8ee5fdb77611ae870de0d4b89749aba5c1fa847f7ec1bf025ad` |
| `libc++_shared.so` | 1,423,696 | `05bef9d3901910c3ff8ab450b03bc8b28383d363a2290f2f7b9aed7331ea6ff7` |
| `libfmt.so` | 166,608 | `9b59cf1f5c53e7dcaa890880e1dce473bb1712a4509ba413b228938e070500c4` |
| `liblz4.so` | 171,800 | `98ac31d81b69b0692295e250c0b4e766288905f7bbc7ad4b5240d05752ccc810` |
| `libprotobuf.so` | 2,997,784 | `8d742e537c0c309ca1db089ffbe1d49eeca92c14c1e8af02fb290a59f57d4fa3` |
| `libutf8_validity.so` | 7,088 | `bb74924cec6b1326d3a67768208fa41c5db74c45cd72cf353a99f8318ee12c97` |
| `libz.so.1` | 74,056 | `df5e95e3ea1ffb8f2e0dcebfc43a6c0824b6aebec11752ad8f8764123e24f8a6` |
| `libzstd.so.1` | 820,840 | `1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7` |

`file` output for adb: `ELF 64-bit LSB shared object, ARM aarch64,
version 1 (SYSV), dynamically linked, interpreter /system/bin/linker64,
stripped`. It was NOT executed on this Linux VM — it is an Android binary and
cannot run here (no `/system/bin/linker64`); on-device test on the S26 FE is
still required.

## NEEDED closure (from `readelf -d`)

### adb's direct NEEDED

| Library | Source | Action |
|---|---|---|
| `libabsl_log_internal_message.so` | Termux `abseil-cpp` 20260817.0 | BUNDLED in `adb-libs/` |
| `liblog.so` | Android system (`/system/lib64/`) | Nothing — present on every Android phone |
| `libabsl_vlog_config_internal.so` | Termux `abseil-cpp` 20260817.0 | BUNDLED in `adb-libs/` |
| `libc.so` | Android system (bionic) | Nothing |
| `libprotobuf.so` | Termux `libprotobuf` 2:36.2-1 | BUNDLED in `adb-libs/` |
| `libz.so.1` | Termux `zlib` 1.4 | BUNDLED (copy of `toolchain/libs/libz.so.1`) |
| `libfmt.so` | Termux `fmt` 1:12.2.0 | BUNDLED (copy of `toolchain/libs/libfmt.so`) |
| `libbrotlidec.so` | Termux `brotli` 1.2.1 | BUNDLED in `adb-libs/` |
| `libbrotlienc.so` | Termux `brotli` 1.2.1 | BUNDLED in `adb-libs/` |
| `liblz4.so` | Termux `liblz4` 1.11.0 | BUNDLED in `adb-libs/` |
| `libzstd.so.1` | Termux `zstd` 1.5.7-1 | BUNDLED in `adb-libs/` (real content of `libzstd.so.1.5.7`; the `libzstd.so`/`libzstd.so.1` symlinks were resolved, not copied) |
| `libc++_shared.so` | Termux `libc++` 30 | BUNDLED (copy of `toolchain/libs/libc++_shared.so`) |
| `libdl.so` | Android system (bionic shim) | Nothing |

### Transitive closure

The closure was computed mechanically (`readelf -d` on adb, then on every
staged .so, iterating until no new NEEDED appeared) and then independently
re-verified. Result: **closure complete — no unsatisfied NEEDED** in adb or
any of the 58 staged libs; every staged file's SONAME matches its filename.

- The two absl libs adb needs directly pull in the rest of Abseil: 48 absl
  libs total in the bundle (base, strings, time, synchronization, status,
  cord, hash, log internals, etc.).
- `libbrotlidec.so` / `libbrotlienc.so` pull in `libbrotlicommon.so`.
- `libprotobuf.so` pulls in `libutf8_validity.so` — but NOT `libutf8_range.so`
  (present in the libprotobuf package, not needed by anything here, not staged).
- `libfmt.so` → `libc++_shared.so` (bundled), `libdl.so`, `libc.so` (system).
- `libc++_shared.so` → `libc.so`, `libm.so`, `libdl.so` (all system).
- `libz.so.1` → `libc.so` (system).
- Everything else in the bundle only needs system libs
  (`libc.so`, `libm.so`, `libdl.so`, `liblog.so`) or other bundled libs.

Same `libz.so.1` note as fastboot: Android ships `/system/lib64/libz.so`
(SONAME `libz.so`), NOT `libz.so.1` — the loader matches by SONAME, so the
system copy can never satisfy adb's `libz.so.1` NEEDED entry.

## Loader / environment — how to run it on-device as root

Same pattern as fastboot (`NOTES.md`):

- INTERP is `/system/bin/linker64` — exists on the phone, nothing to bundle.
- The binary has a baked-in RUNPATH of `/data/data/com.termux/files/usr/lib`
  (the Termux lib dir). That path will NOT exist for the app, so it is useless
  in the app — the bundled lib dir must be supplied at launch instead.
- Launch form (app extracts binary + `adb-libs/` to its private dir, chmod
  755, then as root):

  ```
  su -c 'LD_LIBRARY_PATH=<app-lib-dir> <app-dir>/adb <args>'
  ```

  Set `LD_LIBRARY_PATH` *inside* the `su -c '...'` string, so it is applied
  after su's own environment handling. Do not rely on the caller's environment
  surviving `su`.
- Fallback if the linker ignores `LD_LIBRARY_PATH` under su: invoke the loader
  directly, which bypasses env handling entirely:

  ```
  su -c '/system/bin/linker64 --library-path <app-lib-dir> <app-dir>/adb <args>'
  ```

  (Verify `--library-path` is accepted by the S26 FE's linker64 on-device;
  the `LD_LIBRARY_PATH` form is the primary path.)
- No other Termux env is needed: no `PREFIX`, no `TERMUX_*`, no config files.
  adb is self-contained apart from the 58 bundled libs + system bionic.
- USB access: as root, `/dev/bus/usb/*` is directly accessible — no Android
  USB permission dialog applies to the root path. (Termux proved this tonight
  for fastboot; adb over OTG uses the same path.)
- The app can share one lib dir for both binaries: `adb-libs/` already
  contains the three fastboot libs, so a single extracted lib dir serves both
  `adb` and `fastboot` — no need to extract `libs/` separately if both are
  bundled.
- Still to verify on-device: that the staged binary + these libs actually
  start under `su` on the S26 FE (`adb devices` showing a connected device),
  and which of the two launch forms works. Do that before the app ships them.
