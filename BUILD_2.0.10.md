# Moto Flasher 2.0.10 — build note

Built 2026-11-27. Order: Kyle (voice call + 4 screenshots of 2.0.8, plus a follow-up: "adb needs fixed, add more logging").

## APK

- `MotoFlasher-2.0.10.apk` — **10,783,605 bytes**
- SHA-256: `13a084aef02c3f73cac24068f817d21bcfc08520820577c84e415ad043470b65`
- versionCode **40**, versionName **2.0.10**, minSdk 28 / targetSdk 34, WAKE_LOCK only
- Signing cert SHA-256: `b6efa857992218150ddd56409a266e24bd9e9e5b9cdb37e680a079120e3a7bd1` (continuity ✓, same keystore)
- 2.0.8 (`ab040af8…`) and 2.0.9 (`d8c4ec6b…`) APKs re-checked after the build — untouched ✓
- 2.0.10 supersedes the undelivered 2.0.9 (which only moved the September warning).

## What changed

### 1. GUI clipping fixed (Kyle's screenshots)
Root cause: `UiStyle.styledButton` had **0 vertical padding**, so wrapped text in half-width row buttons touched the edges and read as clipped; at 15sp the long OEM names broke mid-word ("MRDUMP_CHKIM / G", "FALLOC / ATE").
- All styled buttons now get 8dp vertical padding.
- New `UiStyle.gridButton`: 13sp text, 8dp horizontal / 10dp vertical padding, MATCH_PARENT height so both buttons in a row are the same height. Longest one-word labels (MRDUMP_FALLOCATE, PRINTK-RATELIMIT, 16 chars ≈ 114dp at 13sp bold) fit one line in a ~178dp half-width cell; multi-word labels wrap at spaces only.
- Extras tab grid (Fastboot + OEM + OEM-strings-only) and the More-tab raw-command preset rows (3 per row) now use `gridButton`.
- Sweep of all 7 tabs: the only other fixed-height view is the log ScrollView (600px, intentional — it's a scroll view). Full-width buttons grow with wrapped text and now have vertical padding. Tab bar, checkboxes, slot picker, theme row fine as-is.

### 2. September warning — Val tab ONLY, new wording
- New text, exactly: **"Can't unlock bootloader with Val on units with the September patch — it's patched."** — shown once, at the top of the Val Protocol card (amber).
- Removed from Extras (2.0.9 had it on Val + Extras; Home already had it removed in 2.0.9). The Extras body line under it ("Commands below come from the September firmware LK…") was removed too.
- Both old texts verified 0 hits in source and DEX.

### 3. Text rewrite — short, simple, human; no arrows
- Card bodies, status lines and section intros shortened across all tabs (Flash slot/TWRP/erase/vbmeta, Val, Puller, ADB, Extras, BROM sections in BromUi, About). Same facts, fewer words.
- The Val paragraph is now: "Pull the LK, patch it, flash it back, unlock, verify. Patched file gets re-checked with liblk before flashing. CERT2 re-sign isn't verified in-app — check on PC if you want the full check."
- Flow line drops arrows: "Flow: 1 pull ✓ · 2 analyze ✓ · 3 patch … · 4 flash · 5 unlock … · 6 verify".
- All `→` removed from UI text, button labels, toasts and log lines (BromUi ×9, PenumbraSession ×3, MainActivity ×3). Verified 0 arrows in DEX strings. (A few remain in code comments only — they don't ship.)
- valproto Spanish error strings translated to short plain English (strings only, no logic changes): ~150 exception/err/requireArg messages across ValAnalysis, ValPatchOps, ValPatcher, ValPresets, ValTokens, ValKeygen, ValEncoders. These surface in the app log via the runValPatch/runValKeygen catches. `println` report output (ValReports etc.) left as-is — internal-only, never surfaces.
- Log lines kept factual + verbose; essay-length ones trimmed where they were UI copy.

### 4. FRP options in the Val tab (new feature)
- Two new presets in `ValPresets.PRESETS` (now 23) + `buildPatchOptions`, following the existing `base.copy(...)` pattern:
  - **frp-skip** → `frpSkipCheck = true` (FRP check patched out)
  - **frp-compare** → `frpCompareValue = args.frpCompareValue` (sets what the FRP check compares against)
  - Both validate cleanly against ValPatcher's existing checks (frpSkipCheck XOR frpCompareValue; the "nothing set" check passes on either).
- Val tab UI: two tappable chips ("frp-skip", "frp-compare") under the preset box that fill the preset box, plus a small numeric input "FRP compare value (default 1)" — so the compare value IS wired, not hardcoded. It flows through `PresetArgs.frpCompareValue` and is logged with the patch run. (Note: the manual fall-through preset also reads `frpCompareValue`, same as before — changing the box affects manual too; default stays 1.)
- The analysis `frp-checkers=` log line kept.

### 5. GUI revamp (light)
- Consistent button padding/heights via the `styledButton` change + `gridButton`; Extras grid aligned with equal-height cells. Themes (Neon/Ember/Frost), 🐔 waiting states, plain Home banner unchanged. No structure/tab/flow changes.

### 6. ADB: heavy logging + retry (Kyle's follow-up)
2.0.8's fix (fresh server per Devices) did not prove out on-device, so 2.0.10 logs everything and retries once:
- **Every adb invocation logged in full** (`Adb.run`): the exact command line with env (`HOME`, `ANDROID_USER_HOME`, `LD_LIBRARY_PATH`, binary path, args), every stdout+stderr line verbatim (Fastboot.runSu merges the streams), and the exit code after. This covers the Devices/Reboot presets, raw commands, shell commands, kill-server, and the puller.
- **Devices (`devicesFresh`)**: logs kill-server result, start-server result (daemon started as root through the same `su` path), a daemon check (`pidof adb`, `ps -A | grep adb`, port 5037 owner via `ss`/`netstat` when visible), the USB bus listing (`ls -la /dev/bus/usb`, `ls -la /dev/bus/usb/*`, device nodes, node count), then raw `devices -l` output. If the list is empty, it automatically runs **one retry** (kill-server → start-server → daemon check → USB listing → devices -l) and logs both attempts. Empty list is still reported as empty — no fake success.
- **Diagnose ADB** now dumps in one shot: binary path + exists/size/executable, libs present (count + names), HOME path + root-writable probe, version, kill-server, start-server, daemon check, USB bus listing, then the full fresh-server devices run (with retry). All in the More-tab log, copyable as before.
- Everything else in the ADB tab (raw input, shell command, presets, Kill adb server) works as in 2.0.8.

## Verification (VM)

- Build clean (aapt2 + kotlinc 2.4.20 + d8; warnings only, all pre-existing).
- aapt2 badging: versionCode 40 / 2.0.10 / minSdk 28 / targetSdk 34 / WAKE_LOCK only.
- apksigner cert SHA-256 = `b6efa857…` ✓.
- DEX (1,638 classes; strings checked): new September text present (1), old September texts 0/0, `frp-skip` ×4, `frp-compare` ×4, arrows 0, `adb cmd:` / `adb exit:` / retry strings present, `fastboot format` 0.
- 2.0.8 + 2.0.9 APK SHAs unchanged ✓.

## On-device tests needed (Kyle)

1. **Extras grid**: every OEM button fully visible, no mid-word breaks, no clipped bottoms (check MRDUMP_FALLOCATE, PRINTK-RATELIMIT, OFF-MODE-CHARGE).
2. **September warning**: shows on the Val tab only, new wording. Not on Home, not on Extras.
3. **Val FRP**: tap frp-skip / frp-compare chips — preset box fills; run a patch with each; compare value box respected (log line shows `frpCompareValue=`).
4. **ADB**: plug Moto in via OTG (USB debugging on) → ADB tab → Devices. Log must show the full command lines, daemon check, USB listing, raw devices output. If still empty, the retry runs automatically; then hit **Diagnose ADB** and send the log (Copy log on More tab). Repeat in TWRP.
5. **Text spot-check**: Val tab, BROM tab, Extras — short plain text, no arrows.
6. Regression: slot picker, log auto-scroll, themes, erase steps still work.

## Not verified / honest gaps

- ADB on-device behavior still unproven — 2.0.10 adds logging + one retry; whether it fixes the empty list on Kyle's phone is for the test above to say. If the USB listing shows nodes but devices is still empty, the fault is inside adb's enumeration; if no nodes show, the OTG device isn't visible to the host at all.
- FRP presets are wired through the existing engine but untested on a real LK — the patcher will refuse loudly if the FRP gates aren't found.
- Nothing was uploaded. Delivery is the main agent's call.
