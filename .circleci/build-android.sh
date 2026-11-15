#!/usr/bin/env bash
set -euo pipefail

delivery=ci-artifacts/apk/LanguageListener-debug.apk
rm -f "$delivery"

# Keep the 4 GiB medium Docker executor within its memory budget.
# Run all checks once and preserve failed test/lint reports for CircleCI's upload steps.
./gradlew \
  --no-daemon \
  --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' \
  testDebugUnitTest lintDebug assembleDebug --continue --stacktrace

test -s app/build/outputs/apk/debug/app-debug.apk
mkdir -p ci-artifacts/apk
cp app/build/outputs/apk/debug/app-debug.apk "$delivery"
