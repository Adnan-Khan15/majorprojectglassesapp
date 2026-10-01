#!/usr/bin/env bash
# Downloads Gemma 4 E2B (LiteRT-LM, Apache-2.0, ungated) and splits it into
# 256 MB asset parts. The Android build can't package a single asset over 2 GB,
# so the app rejoins the parts into one file on first launch.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=android-app/app/src/main/assets/models
URL=https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
SHA256=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
TMP=${TMPDIR:-/tmp}/gemma-4-E2B-it.litertlm

mkdir -p "$OUT"
if [ ! -f "$TMP" ] || [ "$(shasum -a 256 "$TMP" | cut -d' ' -f1)" != "$SHA256" ]; then
  echo "Downloading model (2.6 GB)…"
  if command -v aria2c >/dev/null; then
    aria2c -x 16 -s 16 -k 20M --file-allocation=none -d "$(dirname "$TMP")" -o "$(basename "$TMP")" "$URL"
  else
    curl -L --retry 10 -C - -o "$TMP" "$URL"
  fi
  echo "$SHA256  $TMP" | shasum -a 256 -c -
fi
rm -f "$OUT"/gemma-4-E2B-it.litertlm*
split -b 256m -d -a 2 "$TMP" "$OUT/gemma-4-E2B-it.litertlm.part"
ls -la "$OUT"
