#!/usr/bin/env bash
# Start Shizuku on the monitor, so Aurora Store can install and update apps without a confirmation per app.
# The China ROM has no Google Play. HyperOS stops Shizuku at every standby and reboot, so run it again then.
# Usage: TV=192.168.1.50 examples/redmi-g-pro-27u/shizuku-start.sh   (Shizuku installed: moe.shizuku.privileged.api)
set -euo pipefail
TV="${TV:-${TV_HOST:?set TV (or TV_HOST) to the monitor IP address, e.g. TV=192.168.1.50}}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB=adb   # else the one on PATH
"$ADB" connect "$TV:5555" >/dev/null
A=("$ADB" -s "$TV:5555")
# split installs list one APK per line; the base one gives the install dir
D=$("${A[@]}" shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed -n 's#^package:\(.*\)/base\.apk$#\1#p')
# lib/arm64 or lib/arm, whichever ABI the package was installed for
L=$("${A[@]}" shell ls "$D/lib" | tr -d '\r' | awk 'NR==1')
"${A[@]}" shell "$D/lib/$L/libshizuku.so"
