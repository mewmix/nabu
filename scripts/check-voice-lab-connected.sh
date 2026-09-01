#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="${ANDROID_HOME}/platform-tools/adb"

cd "$ROOT_DIR"

if ! "$ADB" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
  echo "No attached Android device/emulator found."
  echo "Start an emulator or connect a device, then rerun: scripts/check-voice-lab-connected.sh"
  exit 1
fi

echo "TTS workbench connected smoke gate"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mewmix.nabu.TtsWorkbenchSmokeTest,com.mewmix.nabu.ModelsSmokeTest

echo "TTS workbench connected smoke gate passed."
