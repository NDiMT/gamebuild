#!/usr/bin/env bash
# Downloads the toolchain used by scripts/build-offline.sh into ~/.cache/pulse-tools.
# Only needs Maven Central + GitHub (no Google servers required).
set -euo pipefail

TOOLS="${PULSE_TOOLS:-$HOME/.cache/pulse-tools}"
mkdir -p "$TOOLS"
cd "$TOOLS"

[ -f android-30.jar ] || curl -fSL -o android-30.jar \
    https://github.com/Sable/android-platforms/raw/master/android-30/android.jar

[ -f dx.jar ] || curl -fSL -o dx.jar \
    https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/14.0.0_r21/dalvik-dx-14.0.0_r21.jar

[ -f uber-apk-signer.jar ] || curl -fSL -o uber-apk-signer.jar \
    https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar

if [ ! -x build-tools/aapt2 ]; then
    curl -fSL -o sdk-tools.zip \
        https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-x86_64.zip
    unzip -o -q sdk-tools.zip build-tools/*
    rm -f sdk-tools.zip
    chmod +x build-tools/*
fi

echo "tools ready in $TOOLS"
