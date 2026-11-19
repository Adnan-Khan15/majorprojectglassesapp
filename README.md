# Smart Assistive Glasses

Camera glasses that tell a blind or low-vision wearer what's in front of them, and answer questions about it.

- **Tap** the button on the frame: *"a blue pen with Pentel written on it, about 35 centimetres"*
- **Hold** the button and ask: *"Where are my keys?"* → *"Your keys are on the table in front of you. It's about 45 centimetres away."*

Everything runs on the glasses and an Android phone, **offline**. The AI model lives on the phone, nothing is sent to the cloud, and the offline build of the app has no internet permission at all.

This is a BE major project with two parts:

| Folder | What it is |
|---|---|
| [`android-app/`](android-app) | Android app (Kotlin, Jetpack Compose): Bluetooth link, on-device AI, speech, UI |
| [`esp32-firmware/`](esp32-firmware) | Firmware for the Seeed XIAO ESP32S3 Sense on the glasses (PlatformIO / Arduino) |

**Status:** Phase 3 (milestones M0–M8) is implemented on the `phase3` branch. The link, a tap with distance, and throughput have been run on the real glasses. Speech on the glasses, hold-to-talk and the new UI are **implemented, awaiting hardware verification**. Details: [docs/PROGRESS.md](docs/PROGRESS.md).

---

## How it works

```
 ┌──────────── Glasses (XIAO ESP32S3 Sense) ────────────┐            ┌──────────────── Android phone ────────────────┐
 │ button ─ tap ──► camera JPEG + VL53L0X distance ──────┼─ BLE ────►│ photo (CRC-checked)                            │
 │        └ hold ─► PDM mic → IMA ADPCM (while held) ────┼─ BLE ────►│ question audio                                 │
 │                                                       │            │   ML Kit OCR + Gemma 4 E2B (vision + audio)    │
 │                                                       │            │   → JSON answer → AnswerComposer               │
 │                                                       │            │     (distance from the sensor, safety rules)   │
 │ MAX98357A speaker ◄─ I2S ◄─ ADPCM decode (2 s buffer) ◄─ BLE ◄────┼── Android offline TTS → 16 kHz ADPCM frames   │
 └───────────────────────────────────────────────────────┘            └────────────────────────────────────────────────┘
```

**Why it's split this way:** the ESP32 can't run a vision model, and a phone can't sit on your face. The board captures the photo, distance and voice and plays the answer; the phone does the AI and turns the answer into speech.

**What the wearer hears:**
- **Tap:** the main object straight ahead, plus "about N centimetres" from the distance sensor, which points straight ahead. With no trustworthy reading (nothing in range, over 1.2 m, a dark surface): "I couldn't get a reliable distance."
- **Hold to ask:**
  - A rising beep means "speak now"; release, and two short beeps mean "thinking".
  - The photo is taken **at release**, so it shows where you're now facing.
  - Distances are given **only** for a target in the middle of the view (where the sensor points). Otherwise: "It's on your left. Turn left and ask again for the distance."
  - The app never lets the AI say a path, stairs or a road is safe ("I can't tell if it's safe"). If the target isn't visible it says "I can't see it", and when unsure it says "I think…".
- **Pressing the button while it's talking** stops the speech at once.
- Every failure is spoken, for example "I didn't catch that, please try again", "I couldn't get a photo", or "I'm still starting up, about 20 seconds".

---

## Install (tester)

You need: an **Android 12+** phone with Bluetooth (8 GB RAM recommended; tested on a Galaxy S24 / Android 17), about **5.5 GB free** while importing the model, the glasses, and a computer with Chrome or Edge to flash them.

The latest test build is on the [**Releases page**](../../releases) (the newest `v0.x.x-test` pre-release).

### 1. App
1. Uninstall any older version of the app (v0.3 bundled the model and takes ~5 GB).
2. Download **`SmartGlasses-…-test.apk`** from the release onto the phone and open it. Allow "install from this source"; if Play Protect complains: **More details → Install anyway**.
3. The setup wizard asks for **Nearby devices** (Bluetooth, required), notifications (recommended) and microphone (optional, for asking with the phone's mic).

### 2. AI model (once)
1. On the phone, download **[gemma-4-E2B-it.litertlm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm)**: 2.59 GB, Google Gemma 4 E2B, Apache 2.0, no account needed. Use Wi-Fi.
2. **Where the file goes:** leave it in **Downloads**. In the app, tap **Import from file** (setup wizard, or Settings → AI model) and pick it.
3. The app copies it into its own storage and checks it (exact size 2,588,147,712 bytes + SHA-256). Wait for **Model installed and verified ✓**, then **AI model: Ready**.
4. Delete the copy in Downloads. Installing a newer APK over the app keeps the model.

Without the model the app still works: taps are described with ML Kit's basic labeller, but questions need Gemma.

<details><summary>Developers: skip the import with adb</summary>

```bash
scripts/fetch-model.sh --push   # download + verify once, then adb push to the app's drop-in folder (used in place)
```
</details>

### 3. Firmware
1. Download **`smartglasses-firmware-…-merged.bin`** from the same release.
2. In Chrome or Edge, open **https://espressif.github.io/esptool-js/**.
3. Plug the board in with a USB-C **data** cable (hold **B/BOOT** while plugging in if it isn't detected), then **Connect**.
4. Flash address **`0x0`**, choose the file, **Program**, then press **R/RESET**.

**Or with the Arduino IDE:** open [`esp32-firmware/arduino/SmartGlasses/SmartGlasses.ino`](esp32-firmware/arduino/SmartGlasses). Its [README](esp32-firmware/arduino/SmartGlasses/README.md) lists the exact setup: esp32 core **2.0.17**, libraries NimBLE-Arduino + Pololu VL53L0X, board XIAO_ESP32S3, **PSRAM: OPI PSRAM**, USB CDC On Boot: Enabled.

The firmware and the app go together: install both from the same release.

---

## Wiring (Phase 3)

| Part | Pin | Notes |
|---|---|---|
| Push-button | **D0** (GPIO1) to **3V3** | Pressed = HIGH; internal pull-down |
| Status light | onboard **user LED** (GPIO21) | Lit while a photo is captured and sent |
| MAX98357A amp | **BCLK → D4** (GPIO5), **LRC → D5** (GPIO6), **DIN → D3** (GPIO4), **VIN → 5V**, GND → GND | 1 W / 8 Ω speaker on the amp's + and − |
| VL53L0X distance sensor | **SDA → D10** (GPIO9), **SCL → D8** (GPIO7), VIN → 3V3, GND → GND | |
| Microphone | onboard PDM mic (GPIO42/41) | nothing to wire |
| Camera | onboard OV2640/OV3660 | mounted upside down; the firmware flips the image |

- **No microSD card.** GPIO7/8/9 and GPIO21 are also the Sense board's SD-card lines.
- **Loudness** is capped in software at **50 % of full scale** (volume is a % of that ceiling) to protect the speaker. The amp's **VIN must be on 5V**: on 3V3 it's much quieter. The amp's **GAIN** pin sets the boost (floating 9 dB; to GND 12 dB; via 100 kΩ to GND 15 dB).
- **Recommended:** 100 µF across the amp's VIN–GND. Speaker current peaks can dip the supply; the app shows a warning if the glasses last restarted from a brownout.
- Pins are defined in [`include/pins.h`](esp32-firmware/include/pins.h).

---

## Using the app

| Screen | What's there |
|---|---|
| **Home** | Status chips: Glasses, AI model, Speech (tap one for details). **Describe what's ahead**, **Hold to ask with phone mic**, **Try with a photo**. The latest result: photo, question, answer, distance quality, **Replay / Stop** |
| **History** | Every interaction with thumbnail, question and answer. The detail view shows the photo, transcript, distance reading (median, valid samples, spread, flags), latency per stage and the raw model reply. **Export JSON / CSV**, delete |
| **Settings** | Speak on (glasses / phone / both), volume, loudness ceiling, speech rate, announce status, answer length, units, speech recogniser, hold time, distance region, fast photo transfer, AI model |
| **Diagnostics** | Test tones, test sentence, ping; live distance and the **calibration walk-through**; mic level meter and mic test with playback; Bluetooth throughput; latency log export; speech-recognition spike; Bluetooth log |

Built for TalkBack: labelled controls, polite live regions for status and results, targets ≥ 48 dp, status never shown by colour alone, larger type, haptics on connection changes, and (optional) spoken status ("Glasses connected", "AI ready").

---

## Bluetooth protocol (v2)

The glasses are a BLE GATT server named `SmartGlasses`, service `6e1a0001-4b7d-4f2a-9c3e-5a1b2c3d4e5f`.

| Characteristic | UUID suffix | Direction | Type | Contents |
|---|---|---|---|---|
| CONTROL | `…0002…` | glasses → phone | indicate | Image header v2 (16 B: version, flags, image id, size, CRC-32, width, height). The 6-byte v1 header from old firmware is still accepted |
| IMAGE_DATA | `…0003…` | glasses → phone | indicate | Photo in ≤ 244 B chunks, each acknowledged (slow path, used for resends) |
| RESULT_TEXT | `…0004…` | phone → glasses | write | The spoken sentence as text (logged on the serial console) |
| COMMAND | `…0005…` | phone → glasses | write | Capture, stop playback, volume, tone, capabilities, ping, resend/abort photo, throughput test, settings, mic monitor/test, distance read, earcon |
| EVENT | `…0006…` | glasses → phone | notify | Button down/up, hold, recording start/stop, photo captured, **distance result**, playback started/done, underrun, **credits**, errors, capabilities, mic level |
| AUDIO_UP | `…0007…` | glasses → phone | notify | 8-byte header (stream, flags, sequence, ADPCM state) + data: question audio (stream 1), mic test (4), **fast photo chunks** (5), throughput test (3) |
| AUDIO_DOWN | `…0008…` | phone → glasses | write without response | Spoken answer (stream 2): 25 ms IMA ADPCM frames, sent only within the glasses' credits |

Measured on the first hardware run (Galaxy S24): notifications at 66.8 KB/s up and write-without-response at 48.5 KB/s down, both with no loss. Acknowledged indications ran at only 5.3 KB/s, which is why photos now go as sequence-numbered notifications (with a CRC, and a resend over the slow path if a chunk is lost).

The definitions live in two twins that must change together: [`glasses_protocol.h`](esp32-firmware/include/glasses_protocol.h) + [`glasses_frames.h`](esp32-firmware/lib/glasses_core/src/glasses_frames.h) (board) and [`GlassesProtocol.kt`](android-app/app/src/main/java/com/smartglasses/app/ble/GlassesProtocol.kt) + [`Frames.kt`](android-app/app/src/main/java/com/smartglasses/app/ble/Frames.kt) (app). Tests on both sides check the same byte strings, and the C and Kotlin ADPCM codecs are checked against the same golden vectors.

---

## Build from source

```bash
# App (JDK 17 + Android SDK 35, or Android Studio → open android-app/)
cd android-app
./gradlew testOfflineDebugUnitTest      # unit tests
./gradlew assembleOfflineDebug          # test build (what the releases ship for now)
./gradlew assembleOfflineRelease        # R8-shrunk, ~50 MB (not yet tried on a phone)
./gradlew assembleWithDownloadRelease   # same + optional in-app model download (adds INTERNET)
../scripts/check-apk-permissions.sh     # proves the offline APKs have no network permission

# Firmware (pip install platformio)
cd esp32-firmware
pio run -t upload -t monitor            # build, flash, serial monitor at 115200
pio test -e native                      # host tests: ADPCM, CRC-32, ToF filter, frame layouts
python3 ../scripts/make_arduino_sketch.py   # regenerate the Arduino IDE sketch after ANY firmware change

# Scripts
python3 -m unittest discover -s scripts/tests
scripts/latency_report.py latency.csv [--mode tap|question]   # p50/p90 per stage (Diagnostics → Export)
scripts/wer.py results.csv references.csv                      # speech-recognition spike scoring
```

### Code map

```
android-app/app/src/main/java/com/smartglasses/app/
├── GlassesApp.kt          process-wide wiring: link, model, speech, session, history
├── ai/        ModelStore (import/verify) · GemmaVision (LiteRT-LM, vision + audio) · SceneDescriber
│              AnswerComposer (distance + safety rules) · TofCalibration · MlKitVision
├── assistant/ AssistantSession (the one pipeline) · StateAnnouncer
├── audio/     ImaAdpcm · Wav · Resampler · SpeechFramer + CreditWindow · PcmPlayer
├── ble/       GlassesLink · GlassesBleManager · Frames · ImageAssembler · ThroughputTest
├── speech/    SpeechOutput (glasses/phone/both) · GlassesSpeaker (credit-paced stream) · TtsEngine
├── voice/     PhoneMic · SpeechToText (Android on-device) · GlassesMicReceiver · SttSpike
├── history/   HistoryStore    metrics/ InteractionTimeline · LatencyLog    settings/ AppSettings
└── ui/        AppRoot · SetupWizard · Home · History · Settings · Diagnostics screens

esp32-firmware/
├── include/   pins.h · glasses_protocol.h
├── lib/glasses_core/  ima_adpcm · crc32 · tof_filter · glasses_frames   (pure C, host-tested)
└── src/       main (button state machine, capture) · ble_link · camera · tof · speaker · mic
               · console (serial test commands) · settings (NVS) · throughput
```

### Serial console (115200 baud)

`p` tap · `h` 2 s hold-to-talk · `caps` · `tone 1000 500` · `vol 100` · `ceil 50` · `mic 3` (level meter) · `tof 10` · `set hold|tofn|tofb|micgain <v>` · `?`

---

## Testing

- **Automated** (computer only): 64 Android unit tests, 10 firmware native tests and 4 Python tests. They cover the protocol bytes, ADPCM golden vectors, photo reassembly (including lost chunks and CRC), the model store state machine, the answer composer (distance rules, rounding, units, safety wording, centre rule), the JSON contract, the mic stream, history export, calibration and the scripts.
- **On hardware:** [docs/HARDWARE_TESTS.md](docs/HARDWARE_TESTS.md) (per milestone, with expected output), [docs/TOF_TEST_PROTOCOL.md](docs/TOF_TEST_PROTOCOL.md) (distance accuracy) and [docs/STT_SPIKE.md](docs/STT_SPIKE.md) (speech recognition).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Stuck on "Looking…" | Serial should show `advertising started … ok`. Make sure nothing else (e.g. nRF Connect) is connected to the glasses |
| Speaker very quiet | Settings → volume 100 %. Check the amp's VIN is on **5V** and how GAIN is wired. Try the ceiling at 70 % (Diagnostics tones) and listen for distortion |
| Glasses restart while speaking | Brownout: lower the ceiling, add 100 µF at the amp, use a stronger supply |
| "I couldn't get a reliable distance" | The VL53L0X reads about 5 cm – 1.2 m, straight ahead, and struggles with dark or shiny surfaces. Diagnostics → live reading |
| "I didn't catch that" | Speak after the rising beep; check Diagnostics → mic level; raise `set micgain` on the serial console |
| Questions don't work | They need Gemma: AI model chip must say Ready. If logcat says `audio input NOT available`, set Settings → Speech recogniser → Android on-device |
| "Not enough free storage" on import | Free about 3 GB (plus the download itself) |
| Import says the file is wrong | Re-download; the size must be exactly 2,588,147,712 bytes |
| Photo "didn't arrive complete" | Wait for the reconnect and try again; turn off Settings → Fast photo transfer to compare |
