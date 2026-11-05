#!/usr/bin/env bash
# Install project-local Android SDK pieces and JDK 17; reuse existing ADB.
set -euo pipefail
cd "$(dirname "$0")"
if [ "${1:-}" = "--no-launcher" ]; then
  python3 -m venv tools/venv
  tools/venv/bin/pip install --no-cache-dir -r tools/requirements.txt
  exit 0
fi
if [ -n "${TVHOME_JAVA_HOME:-}" ]; then
  JAVA_HOME="$TVHOME_JAVA_HOME"
else
  command -v brew >/dev/null || { echo "Install JDK 17 and set TVHOME_JAVA_HOME, or install Homebrew" >&2; exit 1; }
  if ! brew list openjdk@17 >/dev/null 2>&1; then
    brew install openjdk@17
  fi
  JAVA_HOME="$(brew --prefix openjdk@17)"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$PWD/.local/android-sdk}"
mkdir -p "$SDK/cmdline-tools" "$SDK/downloads"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 2 -o "$SDK/downloads/commandlinetools.zip" \
    https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip
  unzip -q "$SDK/downloads/commandlinetools.zip" -d "$SDK/downloads/unpacked"
  mv "$SDK/downloads/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
# Reuse licenses already accepted in Android Studio on this Mac.
if [ -d "$HOME/Library/Android/sdk/licenses" ] && [ ! -d "$SDK/licenses" ]; then
  cp -R "$HOME/Library/Android/sdk/licenses" "$SDK/licenses"
fi
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platforms;android-29" "platforms;android-34" "build-tools;34.0.0"
if ! command -v adb >/dev/null; then
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platform-tools"
  echo "Add $SDK/platform-tools to PATH for installation."
fi
echo "SDK ready: $SDK (Android 10 API 29 checks, platform 34, build-tools 34.0.0)"
