#!/bin/bash
# Moto Flasher 2.0.12 — title bar right side removed (Kyle, 2026-11-27). Same hand build as 2.0.6 (aapt2 + kotlinc + d8). No Gradle.
# Keystore lives in this dir only, never inside the APK.
# Assets (bundled fastboot + adb binaries + .so deps + Antumbra) must be staged first:
#   assets/fastboot  +  assets/adb  +  assets/lib/*.so  +  assets/rootusb
#   assets/antumbra  (Penumbra v2.0.0 + the write-offset fix, static aarch64,
#     rebuilt with NDK clang because the musl cross gcc segfaults — SHA-pinned, verified below)
#   assets/fonts/Exo2.ttf
#   assets/logo/logo_banner.png + logo_master_1024.png (top-bar logo)
#   (fastboot from toolchain/, adb + its libs from toolchain/adb-libs/,
#   rootusb from toolchain/ — NDK build of src/native/rootusb.c,
#   libs merged + deduped — see toolchain/NOTES.md and toolchain/adb-NOTES.md)
# Resources: res/mipmap-*/ic_launcher(.png/.xml) — compiled with aapt2
# when present. When absent (logo not landed yet), the build strips the
# android:icon attrs from a TEMP manifest copy and builds icon-less
# (the app bar then shows the gradient tile fallback, never a crash);
# the checked-in manifest keeps the icon attrs for the re-package.
set -e
ROOT=~/workspace/moto-flasher-remake
SDK=~/workspace/onelineage/android-sdk
PLATFORM=$SDK/platforms/android-34/android.jar
BT=$SDK/build-tools/34.0.0
export JAVA_HOME=~/workspace/toolchain/jdk21
export PATH=$JAVA_HOME/bin:$PATH
TMP=$ROOT/build
rm -rf "$TMP"
mkdir -p "$TMP/classes" "$TMP/dex"

ANTUMBRA_SHA=c0257dd7104f9df66bb66371bcf1b44055165f20b4ea1504ee9a3c4bd164fb20
ANTUMBRA_SIZE=6213928

# --- stage assets from the toolchain (idempotent; dedupes the 3 libs both
# binaries share — byte-identical copies, verified in adb-NOTES.md) ---
mkdir -p "$ROOT/assets/lib"
cp -f "$ROOT/toolchain/fastboot" "$ROOT/assets/fastboot"
cp -f "$ROOT/toolchain/adb" "$ROOT/assets/adb"
cp -f "$ROOT/toolchain/rootusb" "$ROOT/assets/rootusb"
for d in "$ROOT/toolchain/libs" "$ROOT/toolchain/adb-libs"; do
    for f in "$d"/*.so "$d"/*.so.1; do
        [ -f "$f" ] || continue
        cp -f "$f" "$ROOT/assets/lib/$(basename "$f")"
    done
done
echo "assets staged: fastboot + adb + $(ls "$ROOT/assets/lib" | wc -l) libs"

if [ ! -f "$ROOT/assets/fastboot" ]; then
    echo "MISSING: $ROOT/assets/fastboot — stage the fastboot binary first (toolchain/NOTES.md)"
    exit 1
fi
if [ ! -f "$ROOT/assets/adb" ]; then
    echo "MISSING: $ROOT/assets/adb — stage the adb binary first (toolchain/adb-NOTES.md)"
    exit 1
fi
if [ ! -f "$ROOT/assets/rootusb" ]; then
    echo "MISSING: $ROOT/assets/rootusb — build it first (src/native/rootusb.c, see the header comment for the NDK command)"
    exit 1
fi

# --- Antumbra (Penumbra v2.0.0 + write-offset fix): size + SHA pin — refuse a wrong binary ---
if [ ! -f "$ROOT/assets/antumbra" ]; then
    echo "MISSING: $ROOT/assets/antumbra — copy the fixed Antumbra binary there first (see BUILD_2.0.3.md)"
    exit 1
fi
ACT_SIZE=$(stat -c%s "$ROOT/assets/antumbra")
ACT_SHA=$(sha256sum "$ROOT/assets/antumbra" | cut -d' ' -f1)
if [ "$ACT_SIZE" != "$ANTUMBRA_SIZE" ] || [ "$ACT_SHA" != "$ANTUMBRA_SHA" ]; then
    echo "ANTUMBRA MISMATCH: size=$ACT_SIZE (want $ANTUMBRA_SIZE) sha=$ACT_SHA"
    echo "The bundled binary is NOT the pinned fixed Antumbra build — refusing to build."
    exit 1
fi
echo "antumbra OK: $ACT_SIZE bytes sha256=$ACT_SHA"
if [ ! -f "$ROOT/assets/fonts/Exo2.ttf" ]; then
    echo "MISSING: $ROOT/assets/fonts/Exo2.ttf — the GUI falls back to system sans (non-fatal, continuing)"
fi

# --- keystore (local only) ---
KS=$ROOT/motoflasher.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias motoflasher -storepass cronch123 -keypass cronch123 \
        -dname "CN=Moto Flasher, OU=CRONCH, O=Coop" -validity 10000 -keyalg RSA -keysize 2048
fi

# --- resources (launcher icon, 2.0.1) ---
RES_ARGS=()
MANIFEST="$ROOT/AndroidManifest.xml"
if [ -f "$ROOT/res/mipmap-xxxhdpi/ic_launcher.png" ]; then
    "$BT/aapt2" compile --dir "$ROOT/res" -o "$TMP/compiled_res.zip"
    RES_ARGS=(-R "$TMP/compiled_res.zip")
    echo "launcher icon resources compiled (res/mipmap-*)"
else
    echo "NOTE: res/mipmap launcher icon absent — building WITHOUT an icon (re-package once the logo lands)"
    MANIFEST="$TMP/manifest-noicon.xml"
    sed -e '/android:icon="@mipmap\/ic_launcher"/d' \
        -e '/android:roundIcon="@mipmap\/ic_launcher_round"/d' \
        "$ROOT/AndroidManifest.xml" > "$MANIFEST"
fi

# --- aapt2 (assets bundled: fastboot/adb/antumbra + libs + fonts + logo) ---
"$BT/aapt2" link -o "$TMP/base.apk" -I "$PLATFORM" --manifest "$MANIFEST" -A "$ROOT/assets" "${RES_ARGS[@]}"

# --- kotlinc 2.4.20 (real distribution; /tmp is full, so the JVM tmpdir MUST
# live off /tmp or kotlinc silently emits partial output) ---
KOTLINC_HOME=~/workspace/altbuild/toolchain/kotlinc-2.4.20-dist/kotlinc
STDLIB=$KOTLINC_HOME/lib/kotlin-stdlib.jar
mkdir -p ~/workspace/altbuild/tmp
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$HOME/workspace/altbuild/tmp" \
    "$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 \
    -classpath "$PLATFORM" -d "$TMP/classes" $(find "$ROOT/src" -name '*.kt' | sort)

# --- d8 (inputs must be jars: jar the classes dir first) ---
python3 - "$TMP" <<'PYEOF'
import sys, os, zipfile
tmp = sys.argv[1]
classes = os.path.join(tmp, "classes")
with zipfile.ZipFile(os.path.join(tmp, "classes.jar"), "w", zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(classes):
        for f in files:
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, classes))
print("classes.jar ready")
PYEOF
"$BT/d8" --release --min-api 28 --output "$TMP/dex" "$TMP/classes.jar" "$STDLIB"

# --- package: base.apk + classes.dex ---
python3 - "$TMP" <<'PYEOF'
import sys, zipfile, os
tmp = sys.argv[1]
dex = os.path.join(tmp, "dex", "classes.dex")
assert os.path.exists(dex), "no classes.dex"
with zipfile.ZipFile(os.path.join(tmp, "base.apk")) as zin:
    items = [(i, zin.read(i.filename)) for i in zin.infolist()]
with zipfile.ZipFile(os.path.join(tmp, "unsigned.apk"), "w", zipfile.ZIP_DEFLATED) as zout:
    for info, data in items:
        zout.writestr(info, data)
    zout.write(dex, "classes.dex")
print("packaged")
PYEOF

# --- align + sign ---
"$BT/zipalign" -f 4 "$TMP/unsigned.apk" "$TMP/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:cronch123 --key-pass pass:cronch123 \
    --out "$ROOT/MotoFlasher-2.0.12.apk" "$TMP/aligned.apk"

echo "=== verify ==="
"$BT/apksigner" verify --print-certs "$ROOT/MotoFlasher-2.0.12.apk"
"$BT/aapt2" dump badging "$ROOT/MotoFlasher-2.0.12.apk" | head -4
ls -la "$ROOT/MotoFlasher-2.0.12.apk"
sha256sum "$ROOT/MotoFlasher-2.0.12.apk"
