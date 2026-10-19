#!/usr/bin/env bash
# Re-enable every package disabled from debloat.txt (all batches, plus the "Later" lines if they were
# applied), make the stock Xiaomi launcher the home screen again and turn the launcher's Home redirect off.
# The launcher itself stays installed: uninstall it from Settings, or adb uninstall com.gathin.tvhome.
# Usage: TV=192.168.1.50 examples/redmi-g-pro-27u/restore.sh
set -euo pipefail
TV="${TV:-${TV_HOST:?set TV (or TV_HOST) to the monitor IP address, e.g. TV=192.168.1.50}}"
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB=adb   # else the one on PATH
"$ADB" connect "$TV:5555" >/dev/null
A=("$ADB" -s "$TV:5555")
# every package name in debloat.txt, including commented "Later" ones
grep -oE '^#? *[a-z][a-z0-9_]*(\.[a-z0-9_]+)+' debloat.txt | sed -E 's/^# *//' | sort -u | while read -r p; do
  "${A[@]}" shell pm enable "$p" </dev/null 2>/dev/null | tr -d '\r' || true   # adb would eat the list
done
"${A[@]}" shell cmd package set-home-activity --user 0 com.mitv.tvhome/.MainActivityUserMode \
  || echo "Warning: couldn't make Xiaomi's launcher the home screen again."
# Turn off the launcher's Home redirect (HomeKeyService), so Home goes to Xiaomi's home again. Other
# accessibility services in the list are kept. First stop the launcher from turning it back on.
"${A[@]}" shell cmd notification disallow_listener com.gathin.tvhome/com.gathin.tvhome.HomeKeyRestorer \
  || echo "Warning: couldn't remove the launcher's notification listener."
"${A[@]}" shell pm revoke com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS \
  || echo "Warning: couldn't revoke WRITE_SECURE_SETTINGS; the launcher may turn its Home redirect back on."
CUR=$("${A[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
NEW=$(tr ':' '\n' <<<"$CUR" | sed -e '/^com\.gathin\.tvhome\//d' -e '/^null$/d' -e '/^$/d' | paste -sd: -)
if [[ -z "$NEW" ]]; then
  "${A[@]}" shell settings delete secure enabled_accessibility_services
  "${A[@]}" shell settings put secure accessibility_enabled 0
else
  "${A[@]}" shell settings put secure enabled_accessibility_services "$NEW"
fi
"${A[@]}" shell pm list packages -d
echo "Restored. Disabled packages still listed above (should be empty)."
