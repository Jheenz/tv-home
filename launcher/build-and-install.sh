#!/usr/bin/env bash
# Build and optionally install for testing. Never changes Home, permissions or package enablement.
#   launcher/build-and-install.sh            build + install
#   launcher/build-and-install.sh --build    build only
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-}" in
  ""|--build|--install) ;;
  *) echo "Usage: $0 [--build|--install]" >&2; exit 2 ;;
esac
if [ -n "${TVHOME_JAVA_HOME:-}" ]; then
  JAVA_HOME="$TVHOME_JAVA_HOME"
else
  command -v brew >/dev/null || { echo "Set TVHOME_JAVA_HOME to JDK 17" >&2; exit 1; }
  JAVA_HOME="$(brew --prefix openjdk@17)"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$(cd .. && pwd)/.local/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
API29="$SDK/platforms/android-29/android.jar"
for required in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR" "$API29"; do
  [ -f "$required" ] || { echo "Missing $required; run ./setup.sh" >&2; exit 1; }
done
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 29 --target-sdk-version 30 "$B/res.zip"
# Compile against Android 10 so newer framework API calls fail at build time.
javac -nowarn -Xlint:-options --release 11 -classpath "$API29" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 29 --lib "$API29" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$B/tvhome.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

SERIAL="${ADB_SERIAL:?Set ADB_SERIAL to the intended TV, e.g. 192.168.100.27:5555}"
adb -s "$SERIAL" install -r "$B/tvhome.apk"
adb -s "$SERIAL" shell am start -W -n com.mihir.tvhome/.MainActivity
echo "Installed and opened for testing. Stock Home and package states are unchanged."
