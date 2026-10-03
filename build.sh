#!/usr/bin/env bash
# Manual build without Gradle: javac + R8/d8 + aapt2 + zipalign + apksigner.
# Verified pipeline for this project. Requires JDK 17+.
set -euo pipefail
SDK="${SDK:-$HOME/sdk}"
BT="$SDK/build-tools"; PJ="$SDK/platform/android.jar"; CORE="$SDK/platform/core-for-system-modules.jar"; R8="$SDK/r8.jar"
APP=app/src/main; OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"
javac -source 8 -target 8 -bootclasspath "$PJ:$CORE" -d "$OUT/classes" $(find "$APP/java" -name '*.java')
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 24 --lib "$PJ" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PJ" --manifest "$APP/AndroidManifest.xml" "$OUT/res.zip" -A "$APP/assets" --auto-add-overlay
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f release.keystore ]; then
  keytool -genkeypair -keystore release.keystore -alias mtkfrp -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass mtkfrp2026 -keypass mtkfrp2026 -dname "CN=MTK FRP Tool, OU=Diagnostics, O=Aedora, L=MSK, C=RU"
fi
"$BT/apksigner" sign --ks release.keystore --ks-key-alias mtkfrp --ks-pass pass:mtkfrp2026 --key-pass pass:mtkfrp2026 --out MtkFrpTool-v2.3.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs MtkFrpTool-v2.3.apk
