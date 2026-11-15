#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
platform="$sdk/platforms/android-35/android.jar"
tools="$sdk/build-tools/35.0.0"
mkdir -p build/classes build/apk build/generated out
"$tools/aapt2" compile --dir src/android/res -o build/android-res.zip
"$tools/aapt2" link -I "$platform" --manifest src/android/AndroidManifest.xml --java build/generated -o build/unsigned.apk build/android-res.zip
javac -source 8 -target 8 -cp "$platform" -d build/classes src/android/*.java build/generated/dev/relaycam/android/R.java
mapfile -t classes < <(find build/classes -name '*.class' -type f)
"$tools/d8" --min-api 23 --lib "$platform" --output build/apk "${classes[@]}"
(cd build/apk && zip -q ../unsigned.apk classes.dex)
"$tools/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f build/development.jks ]]; then
  keytool -genkeypair -keystore build/development.jks -storepass relaycam-local-build \
    -alias relaycam -dname 'CN=RelayCam Development' -keyalg RSA -keysize 3072 \
    -validity 10000 -noprompt
fi
"$tools/apksigner" sign --v4-signing-enabled false --ks build/development.jks \
  --ks-pass pass:relaycam-local-build --out out/RelayCam.apk build/aligned.apk
"$tools/apksigner" verify out/RelayCam.apk
