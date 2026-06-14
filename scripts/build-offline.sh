#!/usr/bin/env bash
#
# Builds dist/PULSE.apk without the Android SDK / Gradle, using:
#   - android.jar        (compile classpath; Sable/android-platforms mirror)
#   - aapt2 + zipalign   (static builds; lzhiyong/android-sdk-tools)
#   - dx                 (dexer; com.jakewharton.android.repackaged:dalvik-dx)
#   - uber-apk-signer    (v1+v2+v3 debug signing; patrickfav/uber-apk-signer)
#
# Tool locations can be overridden via env vars below. Run scripts/fetch-tools.sh
# first to download everything into ~/.cache/pulse-tools.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${PULSE_TOOLS:-$HOME/.cache/pulse-tools}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android-30.jar}"
AAPT2="${AAPT2:-$TOOLS/build-tools/aapt2}"
ZIPALIGN="${ZIPALIGN:-$TOOLS/build-tools/zipalign}"
DX_JAR="${DX_JAR:-$TOOLS/dx.jar}"
SIGNER_JAR="${SIGNER_JAR:-$TOOLS/uber-apk-signer.jar}"

SRC="$ROOT/app/src/main"
OUT="$ROOT/build-offline"
DIST="$ROOT/dist"

for f in "$ANDROID_JAR" "$AAPT2" "$ZIPALIGN" "$DX_JAR" "$SIGNER_JAR"; do
    [ -e "$f" ] || { echo "missing tool: $f (run scripts/fetch-tools.sh)" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk" "$DIST"

echo "==> aapt2 compile"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
# the Gradle build supplies the package via 'namespace'; inject it here
sed 's/<manifest /<manifest package="gr.happyonline.doge" /' \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 21 --target-sdk-version 30 \
    --version-code 5 --version-name 1.4 \
    --java "$OUT/gen" \
    -o "$OUT/apk/base.apk" \
    "$OUT/res.zip"

echo "==> javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -Xlint:-options \
    -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> dx"
java -cp "$DX_JAR" com.android.dx.command.Main --dex \
    --min-sdk-version 21 \
    --output "$OUT/apk/classes.dex" "$OUT/classes"

echo "==> package + align + sign"
(cd "$OUT/apk" && zip -q -u base.apk classes.dex)
"$ZIPALIGN" -f 4 "$OUT/apk/base.apk" "$OUT/apk/aligned.apk"
java -jar "$SIGNER_JAR" --apks "$OUT/apk/aligned.apk" --allowResign >/dev/null

cp "$OUT/apk/aligned-aligned-debugSigned.apk" "$DIST/DOGE.apk" 2>/dev/null \
    || cp "$OUT/apk/"aligned-*Signed*.apk "$DIST/DOGE.apk"

echo "==> done: $DIST/DOGE.apk"
ls -la "$DIST/DOGE.apk"
