#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java -jar dist/AudioALL.jar --help >/dev/null
dist/linux/AudioALL/bin/AudioALL --help >/dev/null
dist/linux/AudioALL/lib/runtime/bin/java -jar dist/linux/AudioALL/lib/app/AudioALL.jar encode --text 'Проверка готовой Linux-сборки 🎵' --profile FAST --repeat 1 --out build/artifact-check.wav
dist/linux/AudioALL/lib/runtime/bin/java -jar dist/linux/AudioALL/lib/app/AudioALL.jar decode --wav build/artifact-check.wav --out build/artifact-received
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
"$SDK/build-tools/35.0.0/apksigner" verify --verbose dist/AudioALL-android.apk
"$SDK/build-tools/35.0.0/zipalign" -c -p 4 dist/AudioALL-android.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('dist/AudioALL-android.apk') as z:
    assert z.testzip() is None
    assert {'AndroidManifest.xml','classes.dex','resources.arsc'}.issubset(z.namelist())
    assert z.read('classes.dex').startswith(b'dex\n')
    print('PASS APK archive, manifest, resources and DEX')
PY
