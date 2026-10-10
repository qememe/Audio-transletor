#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/tests
find core/src tests/src -name '*.java' -print > build/test-sources.txt
javac --release 8 -encoding UTF-8 -d build/tests @build/test-sources.txt
java -Xmx768m -cp build/tests org.audioall.tests.CoreTests
