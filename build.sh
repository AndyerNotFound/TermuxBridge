#!/usr/bin/env bash
# DEPRECATED since v1.7.0: the app now depends on Material Components, which the
# zero-dep pipeline cannot link. Use the GitHub Actions build (gradle :app:assembleRelease).
set -euo pipefail
echo "build.sh is deprecated since v1.7.0 (Material Components dependency)." >&2
echo "Build via GitHub Actions: push to main -> artifact 'TermuxBridge-apk'," >&2
echo "or run 'gradle :app:assembleRelease' in a full Android build environment." >&2
exit 1
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
PLATFORM="${ANDROID_PLATFORM:-android-36}"
AJ="$SDK/platforms/$PLATFORM/android.jar"
AAPT2="${AAPT2:-aapt2}"
KS="${TBRIDGE_KS:-$HOME/.android/bridge.keystore}"
KS_PASS="${TBRIDGE_KS_PASS:-android}"

[ -f "$AJ" ] || { echo "找不到 $AJ, 请设置 ANDROID_SDK"; exit 1; }

rm -rf build/classes build/apk build/gen
mkdir -p build/classes build/apk build/gen

"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/apk/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml --java build/gen build/res.zip
javac -cp "$AJ" -d build/classes $(find src build/gen -name '*.java')
( cd build/classes && jar cf ../classes.jar lll )
d8 --release --min-api 28 --lib "$AJ" --output build/apk build/classes.jar

cp build/apk/base.apk build/unsigned.apk
zip -q -j build/unsigned.apk build/apk/classes.dex
zipalign -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias bridge \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=TermuxBridge, OU=Dev, O=Local, C=CN"
fi

apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out build/TermuxBridge.apk build/aligned.apk
echo "BUILD OK: $(stat -c%s build/TermuxBridge.apk) bytes"
