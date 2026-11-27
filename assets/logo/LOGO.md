# Moto Flasher — Logo 2.0.1

Original art, drawn in code (Python + PIL, builder script kept out of the
tree in /tmp). No stock imagery, no clip art, no AI/branding watermark.

## The mark

A single chunky geometric lightning bolt — the "flash" in Moto Flasher.
It is drawn as one polygon: top tip right, bottom tip left, with a hard
zig in the middle. The two outer peaks read as a stylised **M** as well
as a flash. No fine detail, no text inside the icon itself; the app
label supplies the name, so the bolt stays legible down to 48 px.

## Colours (match UiStyle.kt / the neon LK theme)

| Swatch | Hex | Role |
|---|---|---|
| magenta | `#FF2E88` | bolt top, wordmark start |
| violet | `#7B2FFF` | bolt middle, tile rim |
| cyan | `#00E5FF` | bolt tip, accents |
| orange | `#FF7A18` | sun-gradient accent, rainbow ramp |
| yellow | `#FFD600` | sun-gradient accent, rainbow ramp |
| dark violet | `#0D0714` | tile / adaptive background |
| card violet | `#1A0F2E` | tile gradient top |

- **Tile:** vertical gradient `#1A0F2E` → `#0D0714`, rounded square
  (radius 22.5%), thin `#7B2FFF` rim, soft neon glow behind the bolt.
- **Bolt:** vertical gradient magenta → violet → cyan, with a faint
  white highlight along its top edge.
- **Banner wordmark:** "MOTO FLASHER" in the bundled Exo 2
  (`assets/fonts/Exo2.ttf`, SIL OFL), per-letter rainbow using the same
  ramp as `UiStyle.rainbow` (magenta → orange → yellow → green → cyan
  → violet), subline "BROM • FASTBOOT • PENUMBRA" in dim lavender
  `#B9A8D6`.

## Files

| File | Size |
|---|---|
| `assets/logo/logo_master_1024.png` | 1024×1024, transparent |
| `assets/logo/logo_banner.png` | 720×180, transparent (top bar) |
| `assets/logo/preview.png` | 1440×1360, dark-background preview sheet |
| `res/mipmap-mdpi/ic_launcher.png` + `ic_launcher_round.png` | 48×48 |
| `res/mipmap-hdpi/ic_launcher.png` + `ic_launcher_round.png` | 72×72 |
| `res/mipmap-xhdpi/ic_launcher.png` + `ic_launcher_round.png` | 96×96 |
| `res/mipmap-xxhdpi/ic_launcher.png` + `ic_launcher_round.png` | 144×144 |
| `res/mipmap-xxxhdpi/ic_launcher.png` + `ic_launcher_round.png` | 192×192 |
| `res/mipmap-xxxhdpi/ic_launcher_foreground.png` | 432×432, bolt only |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | adaptive icon (round) |
| `res/drawable/ic_launcher_background.xml` | solid `#0D0714` background |

All launcher PNGs were redrawn at their native size (not downscaled from
the master), so the rim, glow and bolt edges scale with the density.
Round icons are the same art under a circular mask.

## Adaptive icon

- Foreground is the bolt **alone** (no tile — the tile would double up
  against the adaptive background). It is ~249 px tall inside the 432 px
  canvas, i.e. inside the 66 dp / 264 px safe-zone circle: the farthest
  opaque pixel sits 124.7 px from centre, under the 132 px safe radius
  (measured on the actual alpha channel, not the bounding box).
- Background is the solid `#0D0714` drawable above.
- Both adaptive XMLs reference resources that exist in `res/`:
  `@drawable/ic_launcher_background` and `@mipmap/ic_launcher_foreground`.

## 48 px check

Verified visually (preview sheet, actual-size row): the bolt reads
clearly at 48 px — magenta top, cyan tip, dark tile. No muddiness; no
simplification was needed beyond the already-minimal design (one shape,
no text, no thin lines).

## Wiring still needed (GUI agent — not this task)

`AndroidManifest.xml` currently declares **no** `android:icon` at all
(the app uses the Android default icon). The GUI agent must add
`android:icon="@mipmap/ic_launcher"` and
`android:roundIcon="@mipmap/ic_launcher_round"` to the `<application>`
element. The adaptive XMLs will then take over on API 26+; the
`mipmap-*` PNGs cover older devices. No source, manifest, or build
script was touched by the logo work, and `MotoFlasher-2.0.0.apk` and
`~/workspace/moto-flasher-app/` (1.1.21) were not touched.
