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
# Voice input from the remote's mic button: a speech model for English and one for Russian (about 130 MB each,
# downloaded once and checked), and the mic permission. See docs/specs/voice-input.md. VOICE=0 skips it.
if [[ "${VOICE:-1}" != 0 ]]; then
  CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/redmi-g-pro-27u-launcher"
  DIR=/sdcard/Android/data/com.gathin.tvhome/files/asr
  # language:model:SHA-256 of its model.int8.onnx
  for ENTRY in en:sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8:9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a \
               ru:sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8:e3ea888bcc90cafdeeedeea26f6e8ca2186d8af7939236e0eb246206f5098c00; do
    IFS=: read -r LANG_CODE MODEL SHA <<<"$ENTRY"
    if [[ ! -f "$CACHE/$MODEL/model.int8.onnx" ]]; then
      mkdir -p "$CACHE"
      TMP=$(mktemp -d "$CACHE/.download.XXXXXX")   # unpack aside, so a cut download is never taken for the model
      trap 'rm -rf "$TMP"' EXIT
      echo "Downloading the $LANG_CODE speech model (about 100 MB)..."
      curl -fL --progress-bar "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL.tar.bz2" \
        | tar xj -C "$TMP"
      echo "$SHA  $TMP/$MODEL/model.int8.onnx" | sha256sum -c --quiet \
        || { echo "The $LANG_CODE speech model doesn't match its checksum." >&2; exit 1; }
      mv "$TMP/$MODEL" "$CACHE/"
    fi
    SIZE=$(stat -c %s "$CACHE/$MODEL/model.int8.onnx")
    if [[ "$("${A[@]}" shell "stat -c %s $DIR/$LANG_CODE/model.int8.onnx 2>/dev/null" | tr -d '\r')" != "$SIZE" ]]; then
      "${A[@]}" shell mkdir -p "$DIR/$LANG_CODE"
      "${A[@]}" push "$CACHE/$MODEL/model.int8.onnx" "$CACHE/$MODEL/tokens.txt" "$DIR/$LANG_CODE/" >/dev/null
    fi
  done
  "${A[@]}" shell pm grant com.gathin.tvhome android.permission.RECORD_AUDIO \
    || echo "Warning: voice input won't work (mic permission not granted)."
fi
echo "Installed."
