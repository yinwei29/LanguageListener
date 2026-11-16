#!/usr/bin/env bash
set -euo pipefail

delivery=ci-artifacts/apk/LanguageListener-debug.apk
rm -f "$delivery"

# Optional stable debug signing identity for Google OAuth. Configure through CircleCI secrets.
signing_directory=""
cleanup() { if [[ -n "$signing_directory" ]]; then rm -rf "$signing_directory"; fi; }
trap cleanup EXIT
if [[ -n "${LL_DEBUG_KEYSTORE_BASE64:-}" ]]; then
  umask 077
  signing_directory=$(mktemp -d)
  printf '%s' "$LL_DEBUG_KEYSTORE_BASE64" | base64 --decode > "$signing_directory/debug.keystore"
  export LL_DEBUG_KEYSTORE_PATH="$signing_directory/debug.keystore"
  unset LL_DEBUG_KEYSTORE_BASE64
fi

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
