# CLAUDE.md

Smart assistive glasses (BE major project). A Seeed XIAO ESP32S3 Sense on the glasses captures a photo, distance, and (Phase 3) voice. An Android app runs all AI on-device (Gemma 4 E2B via LiteRT-LM, ML Kit OCR) and sends speech back. No cloud.

## Layout
- `android-app/`: Kotlin, Jetpack Compose, Nordic BLE library, minSdk 31, arm64 only.
- `esp32-firmware/`: PlatformIO, Arduino-ESP32 core **2.0.17** (ESP-IDF 4.4, legacy `driver/i2s.h`), NimBLE-Arduino 2.x.
- `scripts/`: model fetch helper, latency report, ADPCM golden-vector generator (`gen_adpcm_vectors.py` writes the vectors both the C and Kotlin tests use). Python tests: `python3 -m unittest discover -s scripts/tests`.
- `docs/`: **`PROGRESS.md` (start here: full handoff, what's built/verified/next)**, `PHASE3_PLAN.md` (plan + milestone status), hardware test protocols.

## Build and test
```bash
# Android (JDK 17)
cd android-app && ./gradlew testOfflineDebugUnitTest assembleOfflineDebug   # flavors: offline (no network), withDownload
scripts/check-apk-permissions.sh   # the offline APKs must have no network permission
# Firmware (needs `pip install platformio`)
cd esp32-firmware && pio run            # build for the board
cd esp32-firmware && pio test -e native # host tests of lib/glasses_core (ADPCM, CRC32, ToF filter, frames)
```

## Rules for working in this repo
- **Evidence rule.** Anything that touches the board, speaker, mic, BLE radio, or phone GPU is "implemented, awaiting hardware verification" until the user runs the steps in `docs/HARDWARE_TESTS.md`. Never state a measurement that wasn't taken, and never present a guessed root cause as fact (e.g. why 512-byte BLE chunks stalled, or the one-off black 3.6 KB image).
- **The BLE contract has two copies**: `esp32-firmware/include/glasses_protocol.h` and `android-app/.../ble/GlassesProtocol.kt`. Change both together.
- **A tap must keep working** at every commit: button → photo + distance → spoken description.
- **The model is never bundled.** `ai/ModelStore` imports/verifies it (manifest: `app/src/main/assets/model-manifest.json`).
- **Never commit** model files (`*.litertlm`), keystores, or `local.properties`.
- One commit per milestone on feature branches; delete dead code rather than keeping two paths.

## Hardware (Phase 3 wiring; single source of truth: `esp32-firmware/include/pins.h`)
| Function | Pin |
|---|---|
| Button | D0 / GPIO1, active HIGH, internal pull-down |
| Status LED | onboard user LED GPIO21 (active LOW; also the microSD CS, so the SD card is never used) |
| Speaker (MAX98357A, I2S1 TX) | BCLK GPIO5 (D4), LRC GPIO6 (D5), DOUT GPIO4 (D3) |
| Mic (onboard PDM, I2S0 RX) | CLK GPIO42, DATA GPIO41 |
| ToF (VL53L0X, I2C) | SDA GPIO9 (D10), SCL GPIO7 (D8) |
| Camera | onboard, pin map in `esp32-firmware/reference/hardware_bringup.ino` |

## Where things live (Phase 3)
- Spoken text is built ONLY in `ai/AnswerComposer` (distance + safety rules; unit-tested). Never let model output reach speech unfiltered.
- One pipeline: `assistant/AssistantSession` (tap, glasses question, phone-mic question). Speech goes out through `speech/SpeechOutput`.
- Audio on the link: 16 kHz IMA ADPCM, 400-sample frames, encoder state in each 8-byte header; downlink paced by EVT_CREDITS.
- Settings: `settings/AppSettings` (phone) and `src/settings.cpp` (NVS on the board); the phone pushes its values once per connection (`GlassesApp` `onFirstCaps`).
