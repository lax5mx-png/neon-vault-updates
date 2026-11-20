#!/bin/bash
set -eo pipefail
cd "$(dirname "$0")"; BT=~/android/sdk/build-tools/34.0.0; AJ=~/android/sdk/platforms/android-34/android.jar
rm -rf build && mkdir -p build/res build/classes build/dex
$BT/aapt2 compile --dir res -o build/res/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml -A assets build/res/res.zip --java build/gen --auto-add-overlay
javac -nowarn -source 11 -target 11 -cp $AJ -d build/classes $(find src build/gen -name '*.java') 2>&1 | { grep -v "Picked up\|Note:\|warning" || true; }
(cd build/classes && jar cf ../classes.jar .)
$BT/d8 --min-api 24 --lib $AJ --output build/dex build/classes.jar 2>&1 | { grep -v "Picked up" || true; }
test -f build/dex/classes.dex
cp build/base.apk build/unsigned.apk && (cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks "${KEYSTORE:-breachkit.keystore}" --ks-pass env:KS_PASS --key-pass env:KS_PASS --out BreachKit.apk build/aligned.apk 2>&1 | { grep -v "Picked up" || true; }
$BT/apksigner verify BreachKit.apk 2>&1 | { grep -v "Picked up" || true; }
echo BUILD_OK
