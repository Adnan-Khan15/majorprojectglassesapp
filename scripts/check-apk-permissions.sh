#!/usr/bin/env bash
# Prints the permissions inside a built APK and checks the offline build has no network access.
#
#   scripts/check-apk-permissions.sh                       # checks the offline release + debug APKs if built
#   scripts/check-apk-permissions.sh path/to/app.apk ...   # any APKs
#
# Reads the APK itself (aapt2 dump permissions), not our manifest: libraries such as
# ML Kit merge permissions in, so only the built file tells the truth.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' android-app/local.properties 2>/dev/null)}}
AAPT=$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
[ -x "${AAPT:-}" ] || { echo "aapt2 not found (set ANDROID_HOME to your Android SDK)"; exit 2; }

if [ $# -eq 0 ]; then
  set -- $(ls android-app/app/build/outputs/apk/offline/*/*.apk android-app/app/build/outputs/apk/withDownload/*/*.apk 2>/dev/null || true)
  [ $# -gt 0 ] || { echo "No APKs built yet: cd android-app && ./gradlew assembleOfflineRelease"; exit 2; }
fi

NETWORK='android.permission.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE)'
status=0
for apk in "$@"; do
  echo "== $apk ($(du -h "$apk" | cut -f1))"
  perms=$("$AAPT" dump permissions "$apk" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p")
  echo "$perms" | sed 's/^/   /'
  net=$(echo "$perms" | grep -E "$NETWORK" || true)
  case "$apk" in
    *offline*)
      if [ -n "$net" ]; then echo "   FAIL: the offline build has network permissions:"; echo "$net" | sed 's/^/     /'; status=1
      else echo "   PASS: no network permission. This app cannot reach the internet."; fi ;;
    *withDownload*)
      echo "   (withDownload build: network permissions are expected, used only for the optional model download)" ;;
  esac
done
exit $status
