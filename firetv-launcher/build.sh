#!/usr/bin/env bash
# Builds dist/clean-home.apk with the plain Android SDK tools (no Gradle).
# Needs: JDK 8+, and ANDROID_HOME with "platforms;android-34" and "build-tools;35.0.0".
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build
KEYSTORE=debug.keystore

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --java "$OUT/gen" "$OUT/res.zip"

javac -source 8 -target 8 -Xlint:-options -cp "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 22 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# A fixed key is kept in the repo so that every build can be installed over the previous one.
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out dist/clean-home.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 22 dist/clean-home.apk
rm -f dist/clean-home.apk.idsig
echo "Built dist/clean-home.apk"
