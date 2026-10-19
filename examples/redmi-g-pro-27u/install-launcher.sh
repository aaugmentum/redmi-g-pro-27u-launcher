#!/usr/bin/env bash
# Build the launcher and install it on a Redmi G Pro 27U (or another China HyperOS TV) over network adb.
# Unlike launcher/build-and-install.sh this never disables the stock home: Xiaomi's firmware refuses a
# third-party default home, and with com.mitv.tvhome disabled Home lands on a black screen. The launcher's
# HomeKeyService redirects Home instead, and HomeKeyRestorer turns it back on after every standby.
# Usage: TV=192.168.1.50 examples/redmi-g-pro-27u/install-launcher.sh
# Needs the Android SDK (platform-tools adb, and the build tools Gradle asks for) and a JDK 17.
set -euo pipefail
TV="${TV:-${TV_HOST:?set TV (or TV_HOST) to the monitor IP address, e.g. TV=192.168.1.50}}"
cd "$(dirname "$0")/../../launcher"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB=adb   # else the one on PATH
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew assembleDebug -q
"$ADB" connect "$TV:5555" >/dev/null
A=("$ADB" -s "$TV:5555")
"${A[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${A[@]}" shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION
# First install only: turn on the Home redirect, keeping any other enabled services. Updates keep it on,
# and the service reconnects by itself.
SVC=com.gathin.tvhome/.HomeKeyService
CUR=$("${A[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
# Match the whole entry: InputKeyService is in the same package.
if ! grep -qxE 'com\.gathin\.tvhome/(com\.gathin\.tvhome)?\.HomeKeyService' <<<"${CUR//:/$'\n'}"; then
  [[ -z "$CUR" || "$CUR" == null ]] && NEW=$SVC || NEW="$CUR:$SVC"
  "${A[@]}" shell settings put secure enabled_accessibility_services "$NEW"
  "${A[@]}" shell settings put secure accessibility_enabled 1
fi
# HyperOS force-stops every third-party app at standby, which turns the Home redirect off. These let the
# launcher restart and turn it back on by itself (HomeKeyRestorer).
"${A[@]}" shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS \
  && "${A[@]}" shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.HomeKeyRestorer \
  || echo "Warning: Home won't come back by itself after standby (grant or listener failed)."
echo "Installed."
