#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/desktop dist
find core/src desktop/src -name '*.java' -print > build/desktop-sources.txt
javac --release 8 -encoding UTF-8 -d build/desktop @build/desktop-sources.txt
jar cfe dist/AudioALL.jar org.audioall.desktop.Main -C build/desktop .
cp scripts/run-linux.sh dist/run-linux.sh
cp scripts/run-windows.bat dist/run-windows.bat
printf 'Created dist/AudioALL.jar\n'
