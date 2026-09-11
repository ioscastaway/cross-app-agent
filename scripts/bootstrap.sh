#!/usr/bin/env bash
# One-time setup for a fresh Mac. Idempotent.
#  1. Installs the Gradle wrapper scripts + jar (pinned to the version in gradle-wrapper.properties)
#  2. Points local.properties at the Android SDK if Android Studio has installed one
#  3. Reminds you to add ANTHROPIC_API_KEY to local.properties
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
BASE="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}"

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Fetching Gradle wrapper ${GRADLE_VERSION} ..."
  curl -fsSL "${BASE}/gradle/wrapper/gradle-wrapper.jar" -o gradle/wrapper/gradle-wrapper.jar
  curl -fsSL "${BASE}/gradlew" -o gradlew
  curl -fsSL "${BASE}/gradlew.bat" -o gradlew.bat
  chmod +x gradlew
else
  echo "Gradle wrapper already present."
fi

SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
touch local.properties
if ! grep -q '^sdk.dir=' local.properties; then
  if [ -d "$SDK_DIR" ]; then
    echo "sdk.dir=$SDK_DIR" >> local.properties
    echo "Wrote sdk.dir=$SDK_DIR to local.properties"
  else
    echo "!! No Android SDK found at $SDK_DIR."
    echo "   Install Android Studio (brew install --cask android-studio), open it once, then re-run this script."
  fi
fi

if ! grep -q '^ANTHROPIC_API_KEY=' local.properties; then
  echo "ANTHROPIC_API_KEY=" >> local.properties
  echo "!! Add your key to local.properties: ANTHROPIC_API_KEY=sk-ant-..."
fi

if [ ! -x "/Applications/Android Studio.app/Contents/MacOS/studio" ]; then
  echo "!! Android Studio not found. Install with: brew install --cask android-studio"
fi

echo "Done. Next: open this folder in Android Studio, or run ./gradlew assembleDebug"
