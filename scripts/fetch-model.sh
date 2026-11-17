#!/usr/bin/env bash
# Developer helper: download Gemma 4 E2B (LiteRT-LM, Apache-2.0, ungated), check its
# SHA-256, and optionally push it to a connected phone's drop-in folder, where the app
# uses it in place (no copy, no import step).
#
#   scripts/fetch-model.sh            # download + verify into $MODEL_CACHE (default ~/.cache/smartglasses)
#   scripts/fetch-model.sh --push     # ...then adb push to the phone (app must have been opened once)
#
# The model is never part of the APK or the git repo. End users import it from a file
# in the app instead (see README, "Install the AI model").
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFEST=android-app/app/src/main/assets/model-manifest.json
read -r NAME SIZE SHA256 URL < <(python3 -c '
import json,sys
m=json.load(open(sys.argv[1]))["models"][0]
print(m["fileName"], m["size"], m["sha256"], m["url"])' "$MANIFEST")

CACHE=${MODEL_CACHE:-$HOME/.cache/smartglasses}
FILE=$CACHE/$NAME
DROP_IN=/sdcard/Android/data/com.smartglasses.app/files/models

mkdir -p "$CACHE"
if [ -f "$FILE" ] && [ "$(stat -f%z "$FILE" 2>/dev/null || stat -c%s "$FILE")" = "$SIZE" ] \
   && [ "$(shasum -a 256 "$FILE" | cut -d' ' -f1)" = "$SHA256" ]; then
  echo "Already have a verified $NAME in $CACHE"
else
  echo "Downloading $NAME ($(python3 -c "print(f'{$SIZE/1e9:.2f}')") GB)…"
  if command -v aria2c >/dev/null; then
    aria2c -x 16 -s 16 -k 20M --file-allocation=none -c -d "$CACHE" -o "$NAME" "$URL"
  else
    curl -L --retry 10 -C - -o "$FILE" "$URL"
  fi
  echo "$SHA256  $FILE" | shasum -a 256 -c -
fi

if [ "${1:-}" = "--push" ]; then
  command -v adb >/dev/null || { echo "adb not found (Android SDK platform-tools)"; exit 1; }
  adb shell mkdir -p "$DROP_IN"
  adb push "$FILE" "$DROP_IN/$NAME"
  echo "Pushed. In the app, tap Rescan on the AI model card (or restart the app)."
fi
