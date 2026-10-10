#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then printf 'Set ANDROID_SDK_ROOT to an SDK with platforms;android-35 and build-tools;35.0.0\n' >&2; exit 1; fi
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
mkdir -p build/android/classes build/android/gen build/android/dex dist
"$BT/aapt2" compile --dir android/res -o build/android/resources.zip
"$BT/aapt2" link -o build/android/resources.apk -I "$PLATFORM" --manifest android/AndroidManifest.xml --java build/android/gen --min-sdk-version 26 --target-sdk-version 35 build/android/resources.zip
find core/src android/src build/android/gen -name '*.java' -print > build/android-sources.txt
javac --release 8 -encoding UTF-8 -cp "$PLATFORM" -d build/android/classes @build/android-sources.txt
jar cf build/android/classes.jar -C build/android/classes .
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/android/dex build/android/classes.jar
python3 - <<'PY'
import zipfile,shutil
shutil.copyfile('build/android/resources.apk','build/android/unsigned.apk')
with zipfile.ZipFile('build/android/unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('build/android/dex/classes.dex','classes.dex')
PY
"$BT/zipalign" -f -p 4 build/android/unsigned.apk build/android/aligned.apk
KEYSTORE="${AUDIOALL_KEYSTORE:-build/android/debug.keystore}"
if [[ ! -f "$KEYSTORE" ]]; then
    if [[ -n "${AUDIOALL_KEYSTORE:-}" ]]; then printf 'Configured signing keystore does not exist\n' >&2; exit 1; fi
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey -dname 'CN=AudioALL Development,O=AudioALL,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
export AUDIOALL_STORE_PASSWORD="${AUDIOALL_STORE_PASSWORD:-android}"
export AUDIOALL_KEY_PASSWORD="${AUDIOALL_KEY_PASSWORD:-$AUDIOALL_STORE_PASSWORD}"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${AUDIOALL_KEY_ALIAS:-androiddebugkey}" --ks-pass env:AUDIOALL_STORE_PASSWORD --key-pass env:AUDIOALL_KEY_PASSWORD --out dist/AudioALL-android.apk build/android/aligned.apk
"$BT/apksigner" verify --verbose dist/AudioALL-android.apk
"$BT/aapt2" dump badging dist/AudioALL-android.apk
printf 'Created dist/AudioALL-android.apk\n'
