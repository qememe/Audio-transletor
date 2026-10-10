#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/build-desktop.sh
mkdir -p build/package-input
cp dist/AudioALL.jar build/package-input/
# Recreate only generated packaging directories.
rm -rf build/linux-runtime build/linux/AudioALL
jlink --add-modules java.desktop,java.logging --strip-debug --no-header-files --no-man-pages --compress=2 --output build/linux-runtime
jpackage --type app-image --name AudioALL --app-version 1.0.0 --vendor AudioALL --description 'Images, text, audio and video files over sound' --input build/package-input --main-jar AudioALL.jar --main-class org.audioall.desktop.Main --runtime-image build/linux-runtime --dest build/linux --java-options '-Xmx512m'
cp README.md build/linux/AudioALL/README.md
cp docs/PROTOCOLS.md build/linux/AudioALL/PROTOCOLS.md
cp docs/QUICKSTART.md build/linux/AudioALL/QUICKSTART.md
cp LICENSE build/linux/AudioALL/LICENSE
mkdir -p dist/linux
cp -a build/linux/AudioALL dist/linux/
tar -czf dist/AudioALL-linux-x86_64.tar.gz -C build/linux AudioALL
printf 'Created dist/linux/AudioALL/bin/AudioALL and dist/AudioALL-linux-x86_64.tar.gz\n'
