# CLAUDE.md

Smart assistive glasses (BE major project). A Seeed XIAO ESP32S3 Sense on the glasses captures a photo, distance, and (Phase 3) voice. An Android app runs all AI on-device (Gemma 4 E2B via LiteRT-LM, ML Kit OCR) and sends speech back. No cloud.

## Layout
- `android-app/`: Kotlin, Jetpack Compose, Nordic BLE library, minSdk 31, arm64 only.
- `esp32-firmware/`: PlatformIO, Arduino-ESP32 core **2.0.17** (ESP-IDF 4.4, legacy `driver/i2s.h`), NimBLE-Arduino 2.x.
- `scripts/`: model fetch helper, latency report, test tools. Python tests: `python3 -m unittest discover -s scripts/tests`.
- `docs/`: `PHASE3_PLAN.md` (current plan and milestone status), hardware test protocols.

## Build and test
```bash
# Android (JDK 17)
cd android-app && ./gradlew testOfflineDebugUnitTest assembleOfflineRelease   # flavors: offline (no network), withDownload
# Firmware (needs `pip install platformio`)
cd esp32-firmware && pio run            # build for the board
cd esp32-firmware && pio test -e native # host tests of pure C/C++ logic (once added in Phase 3)
```

## Rules for working in this repo
- **Evidence rule.** Anything that touches the board, speaker, mic, BLE radio, or phone GPU is "implemented, awaiting hardware verification" until the user runs the steps in `docs/HARDWARE_TESTS.md`. Never state a measurement that wasn't taken, and never present a guessed root cause as fact (e.g. why 512-byte BLE chunks stalled, or the one-off black 3.6 KB image).
- **The BLE contract has two copies**: `esp32-firmware/include/glasses_protocol.h` and `android-app/.../ble/GlassesProtocol.kt`. Change both together.
- **A tap must keep working** at every commit: button → photo + distance → spoken description.
- **The model is never bundled.** `ai/ModelStore` imports/verifies it (manifest: `app/src/main/assets/model-manifest.json`).
- **Never commit** model files (`*.litertlm`), keystores, or `local.properties`.
- One commit per milestone on feature branches; delete dead code rather than keeping two paths.

## Hardware (Phase 3 wiring; the firmware moves to it in milestone M2)
| Function | Pin |
|---|---|
| Button | D0 / GPIO1, active HIGH, internal pull-down |
| Status LED | onboard user LED GPIO21 (active LOW; also the microSD CS, so the SD card is never used) |
| Speaker (MAX98357A, I2S1 TX) | BCLK GPIO5 (D4), LRC GPIO6 (D5), DOUT GPIO4 (D3) |
| Mic (onboard PDM, I2S0 RX) | CLK GPIO42, DATA GPIO41 |
| ToF (VL53L0X, I2C) | SDA GPIO9 (D10), SCL GPIO7 (D8) |
| Camera | onboard, pin map in `esp32-firmware/reference/hardware_bringup.ino` |
