# Smart Assistive Glasses

Glasses that tell you what you're looking at. Press the button on the frame and, a few seconds later, the glasses say something like:

> "a blue pen with Pentel written on it, 36 centimetres"

Everything runs on the phone and the glasses, with no internet connection. The AI model is **packaged inside the app**. The glasses take the photo and measure the distance, your Android phone works out what the object is, and the glasses speak the answer through their own speaker. Nothing goes to the cloud, and no cable connects the glasses to the phone.

This is a BE major project with two parts:

| Folder | What it is |
|---|---|
| [`android-app/`](android-app) | Android app (Kotlin, Jetpack Compose) that pairs with the glasses over Bluetooth Low Energy |
| [`esp32-firmware/`](esp32-firmware) | Firmware for the Seeed XIAO ESP32S3 Sense board on the glasses (PlatformIO / Arduino) |

---

## How it works

```
 ┌──────────────── Glasses (XIAO ESP32S3 Sense) ───────────────┐          ┌──────────── Android phone ────────────┐
 │                                                             │          │                                       │
 │  button press ─► camera (JPEG) + VL53L1X ToF distance ──────┼── BLE ──►│  reassemble image                     │
 │                                                             │          │  ML Kit OCR  (reads text / brands)    │
 │                                                             │          │  Gemma 4 E2B vision model (what it is)│
 │  MAX98357A speaker ◄─ I2S ◄─ SAM speech synth ◄─────────────┼── BLE ◄──│  compose sentence                     │
 │                                                             │          │                                       │
 └─────────────────────────────────────────────────────────────┘          └───────────────────────────────────────┘
```

**Why it's split this way.** The ESP32 can't run a vision model, and a phone can't sit on your face. The board handles capture and speech, and the phone does the heavy AI work. Only two things cross the Bluetooth link:

- **Glasses → phone:** the photo and the distance reading.
- **Phone → glasses:** the finished sentence as plain text, a few hundred bytes. No audio is sent; the board speaks the text itself.

### Bluetooth protocol

The glasses act as a BLE GATT server. They advertise as `SmartGlasses` with service `6e1a0001-4b7d-4f2a-9c3e-5a1b2c3d4e5f`.

| Characteristic | UUID suffix | Direction | Type | Contents |
|---|---|---|---|---|
| CONTROL | `…0002…` | glasses → phone | indicate | 6-byte header, little-endian: image size (uint32) + distance in mm (uint16; `0` means no valid reading) |
| IMAGE_DATA | `…0003…` | glasses → phone | indicate | JPEG, streamed in chunks of min(MTU − 3, 244) bytes. The board sends each chunk only after the phone acknowledges the previous one. 244 B fits one radio packet; full 512 B chunks were silently dropped by a test phone's Bluetooth stack. |
| RESULT_TEXT | `…0004…` | phone → glasses | write | The sentence to speak, as UTF-8 |

Both sides use the same definitions: [`glasses_protocol.h`](esp32-firmware/include/glasses_protocol.h) on the board and [`GlassesProtocol.kt`](android-app/app/src/main/java/com/smartglasses/app/ble/GlassesProtocol.kt) in the app.

### Tech choices

| Part | Choice | Why |
|---|---|---|
| Phone BLE | [Nordic Android BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library) | Queues every GATT operation, which avoids the race conditions of raw `BluetoothGatt` callbacks |
| Board BLE | [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino) | Uses much less memory than the stock ESP32 BLE stack, which matters because the camera shares the heap. The ESP32-S3 only supports BLE (no Classic Bluetooth), so this is the right stack. |
| Text reading | Google ML Kit Text Recognition v2 (on-device) | Reads brand names and labels offline |
| Object description | **[Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)** (Google, Apache 2.0) on **[LiteRT-LM](https://developers.google.com/edge/litert-lm/android)**, using the phone's GPU (falls back to the CPU) | Google's newest small multimodal model. It describes objects in natural language and reads labels itself. The model file (2.6 GB) is bundled in the APK. If it can't start on a phone, the app switches to ML Kit Image Labeling so captures still work. |
| Speech | [SAM](https://github.com/0xD34D/SAM-ESP32) → I2S → MAX98357A amp | SAM builds speech from phonemes, so it can say any word, including brand names (a fixed-dictionary synth can't) |

---

## Project status

| Phase | What | Status |
|---|---|---|
| 1 | BLE link: board advertises, phone connects, button sends a test string, phone echoes it back | ✅ Code complete; on-hardware test pending |
| 2 | Camera → JPEG → BLE → image shown on the phone | ✅ Code complete; on-hardware test pending |
| 3 | ToF distance sent along with the image | ✅ Code complete; on-hardware test pending |
| 4 | ML Kit OCR on the received image | ✅ Done ahead of schedule; testable with **Try with a photo** |
| 5 | Object description (Gemma 4 E2B, bundled) + latency measurement | ✅ Built in; phone latency not yet measured (see results below) |
| 6 | Compose the sentence and send it back | ✅ Sent on RESULT_TEXT and logged by the board; **spoken by the phone for now** |
| 7 | SAM speech over I2S on the board | ⏳ Waiting for the MAX98357A + speaker to be wired |
| 8 | Full end-to-end timed run (target: 5–10 s) | ⏳ |
| 9 | Final UI polish: history list with thumbnails, manual capture button | ⏳ |

The current release, **v0.3**, runs the real loop:

1. You press the glasses button.
2. The glasses send a photo and the distance reading to the phone.
3. Gemma describes the object.
4. The phone speaks the result, for example "a blue pen with Pentel written on it, 36 centimetres".
5. The phone also sends that sentence back to the glasses, which log it.

Until the speaker is wired to the glasses (Phase 7), the **phone** speaks the sentence. That step sits behind one swappable `ResultAnnouncer` (`PhoneTtsAnnouncer` today). **Try with a photo** still works and goes through the same pipeline.

### Model test results

These came from running the exact model file in the APK, with the app's exact prompt and photos downscaled to 512 px. They were measured on a **MacBook (M2, CPU)**, not a phone, and without the OCR hint the phone adds:

| Photo | Gemma 4 E2B's answer | Time |
|---|---|---|
| Two Pentel pens | "two blue Pentel Superball pens on a grid" | 5.5 s |
| Colgate toothpaste tube | "a tube of Colgate Total 12 toothpaste" | 3.9 s |
| Coca-Cola bottle with the label mostly hidden by a hand | "a dark bottle with red and white lettering" (it doesn't guess a brand it can't read) | 3.8 s |

On a recent flagship phone's GPU, Google's benchmark for this model is about 0.3 s to the first word and 52 words per second, so the AI step should be faster than on the Mac. The real phone number is still to be measured.

---

## Download and install the app

**Requirements:**
- an Android phone running **Android 12 or newer**, with Bluetooth
- **8 GB of RAM or more** recommended
- about **8 GB of free storage** while installing; about 5.3 GB once set up (the app plus the unpacked AI model)

The APK is **2.7 GB** because the whole AI model is inside it. GitHub doesn't allow release files over 2 GB, so it's uploaded in **two parts** that you join on a computer.

1. From the [latest release](../../releases/latest), download **both** `SmartGlasses-v0.3.apk.part1` and `SmartGlasses-v0.3.apk.part2` into the same folder.
2. Join them into one APK:
   - **macOS / Linux:** `cat SmartGlasses-v0.3.apk.part1 SmartGlasses-v0.3.apk.part2 > SmartGlasses-v0.3.apk`
   - **Windows (Command Prompt):** `copy /b SmartGlasses-v0.3.apk.part1 + SmartGlasses-v0.3.apk.part2 SmartGlasses-v0.3.apk`
   - Optional check that the file is complete: its SHA-256 should match the one in the release notes (`shasum -a 256 SmartGlasses-v0.3.apk`, or `certutil -hashfile SmartGlasses-v0.3.apk SHA256` on Windows).
3. Put it on the phone. The easiest way is `adb install SmartGlasses-v0.3.apk` (see below). Otherwise, copy it over USB or upload it to Google Drive and open it on the phone.
4. Open the APK file. Android will warn about installing from an unknown source:
   - Tap **Settings**, turn on **Allow from this source** for your browser or Files app, then go back.
   - Tap **Install**.
   - If Google Play Protect shows "unrecognised app", tap **More details → Install anyway**. This happens with any app that isn't from the Play Store.
5. Open **Glasses**. **The first launch spends about a minute unpacking the AI model**, with a progress bar. After that it starts in seconds. The app explains why it needs Bluetooth before asking. Tap **Allow Bluetooth access**, then allow **Nearby devices** (and notifications, if asked).
   - The app never uses your location; Android just groups Bluetooth under "Nearby devices".
   - If you tapped "Don't allow" by mistake, the app shows an **Open Settings** button to fix it.
6. Make sure Bluetooth is on. The status banner at the top shows *Looking for glasses… → Connecting… → Connected to SmartGlasses*.
7. When the AI card says **Gemma 4 E2B ready on GPU**, press the glasses button. Or tap **Try with a photo** and pick any photo. The phone speaks the sentence and shows it with timings.

To update later, install the newer APK over the old one. If Android says the package conflicts, uninstall the old version first.

<details>
<summary>Installing from a computer with adb instead</summary>

Turn on **Developer options → USB debugging** on the phone, connect it by USB, then run:

```bash
adb install -r SmartGlasses-v0.3.apk     # a 2.7 GB install takes a minute or two
```
</details>

---

## Flash the glasses firmware

You need a **Seeed Studio XIAO ESP32S3 Sense** and a USB-C cable that carries data, not just power.

### Option A: prebuilt image (no tools to install)

1. Download **`smartglasses-firmware-v0.3.1-merged.bin`** from the [Releases page](../../releases/latest).
2. In Chrome or Edge on a computer, open **https://espressif.github.io/esptool-js/**.
3. Plug in the board. If it isn't detected, hold the **B (BOOT)** button while plugging it in.
4. Click **Connect** and choose the board's serial port.
5. Set the flash address to **`0x0`**, select the `.bin` file, and click **Program**.
6. Press the board's **R (RESET)** button when it finishes.

### Option B: build from source with PlatformIO

```bash
pip install platformio            # or install the PlatformIO extension in VS Code
cd esp32-firmware
pio run -t upload -t monitor      # build, flash, then open the serial monitor (115200 baud)
```

### Button wiring

This matches the original CameraBLE bring-up sketch:

| Pin | Connect to |
|---|---|
| **D0** | Push-button to **3V3**. Pressing it drives D0 HIGH; the internal pull-down holds it LOW otherwise. |
| **D8** | Status LED (with a resistor) to GND. It stays lit while a photo is being captured and sent. |
| **D4 (SDA), D5 (SCL)** | VL53L1X distance sensor (plus 3V3 and GND), as in `hardware_bringup.ino` |
| D1–D3, D6 | Unused for now; pulled down in firmware so they can't pick up noise. The I2S speaker pins come off this list in Phase 7. |

The camera is the onboard OV2640/OV3660, set to VGA JPEG and flipped 180° because it's mounted upside down. If the distance sensor isn't connected, the glasses still work and the sentence leaves out the distance.

To change pins, edit the constants at the top of [`src/main.cpp`](esp32-firmware/src/main.cpp).
No button yet? Type **`p`** in the serial monitor to simulate a press.

---

## Test: button press → spoken sentence

1. Flash the firmware and open the serial monitor at 115200 baud. You should see `[CAM] camera OK`, `[TOF] VL53L1X OK` and `advertising started … ok`.
2. Open the app and wait for both of these:
   - the banner turns green: **Connected to SmartGlasses**
   - the AI card says **Gemma 4 E2B ready on GPU**
3. Point the glasses at an object with a label, about 30–60 cm away, and press the button (or type `p` in the serial monitor).

**What should happen:**

- [ ] The AI card shows *Receiving photo from glasses… KB*, then the **photo from the glasses** with **From glasses · NN centimetres**
- [ ] The sentence ends with the distance, for example *"…, 36 centimetres"*
- [ ] The phone **speaks** the sentence
- [ ] The card shows **Sent to glasses ✓** and the timing line: *Transfer · OCR · model · speech starts = total*
- [ ] The serial monitor shows:
  ```
  [CAM] frame 640x480, 28431 bytes; [TOF] distance 362 mm  (capture+ToF 61 ms)
  [BLE] CONTROL header sent: size=28431 dist=362 -> acknowledged
  [BLE] streaming 28431 bytes in 244-byte chunks (MTU 515)
  [BLE] IMAGE_DATA sent: 28431 bytes in 117 packets of <=244 B, 1950 ms (14.2 KB/s)
  [BLE] RESULT_TEXT received (52 bytes): "a blue pen with Pentel written on it, 36 centimetres"
  [TIME] button press -> RESULT_TEXT back on board: 4870 ms
  ```
  (These numbers are an illustration of the format, not measurements.)

**Total time from press to speech** = the board's `capture+ToF` time + the card's `total`. The card's total runs from the photo header arriving to the phone's speech actually starting.

To watch the phone's log from a computer:

```bash
adb logcat -s GlassesLink:D AssistantSession:D SceneDescriber:D PhoneTtsAnnouncer:D
```

**Robustness checks:**

| Test | Expected |
|---|---|
| Unplug or reset the board | Banner shows "Connection lost — retrying in Ns", then reconnects without touching the phone |
| Reset the board **while a photo is transferring** | The card says the photo didn't arrive complete (within 4 s); press again once reconnected |
| Turn phone Bluetooth off | Banner shows "Bluetooth is off"; reconnects once it's back on |
| Rotate the phone / leave the app / lock the screen | Connection stays up, and a capture still gets described and spoken |

---

## Build the app from source

You'll need **JDK 17** and the **Android SDK** (platform 35), or simply **Android Studio**.

The model isn't stored in git (it's 2.6 GB). Fetch it first. The script downloads it from Hugging Face (no account needed), checks its SHA-256, and splits it into 256 MB asset parts, because the Android build can't package one asset over 2 GB:

```bash
scripts/fetch-model.sh
cd android-app
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected phone
```

In Android Studio, use **File → Open → `android-app/`**, wait for the Gradle sync, then press **Run ▶**.

### Code map

```
android-app/app/src/main/java/com/smartglasses/app/
├── GlassesApp.kt              # Application; owns the BLE link and AI model for the whole process lifetime
├── ai/
│   ├── ModelInstaller.kt      # first launch: joins the bundled model parts into one file
│   ├── GemmaVision.kt         # Gemma 4 E2B on LiteRT-LM (GPU, then CPU), prompt
│   ├── MlKitVision.kt         # ML Kit OCR + image-labelling fallback
│   └── SceneDescriber.kt      # downscale → OCR → Gemma (30 s timeout) → "…, 36 centimetres"
├── ble/
│   ├── GlassesProtocol.kt     # UUIDs: the GATT contract with the firmware
│   ├── GlassesBleManager.kt   # Nordic BleManager: MTU, indications, writes
│   ├── ImageAssembler.kt      # CONTROL header + IMAGE_DATA chunks → one verified JPEG
│   └── GlassesLink.kt         # scan → connect → reconnect; capture events; RESULT_TEXT
├── assistant/
│   ├── AssistantSession.kt    # the one pipeline: photo → SceneDescriber → announce (+ RESULT_TEXT)
│   └── ResultAnnouncer.kt     # swap point: PhoneTtsAnnouncer now, board speaker in Phase 7
├── service/GlassesService.kt  # foreground service so the link survives backgrounding
└── ui/                        # Compose: permissions, status banner, AI card + photo test, log

esp32-firmware/
├── platformio.ini
├── include/glasses_protocol.h # UUIDs: the same contract, firmware side
├── src/main.cpp               # button → camera + VL53L1X → header + chunked JPEG; logs RESULT_TEXT
└── reference/hardware_bringup.ino  # original camera + ToF bring-up sketch the firmware is built on
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| App stuck on "Looking for glasses…" | Check that the serial monitor shows `advertising started … ok`. Make sure no other phone or app (for example nRF Connect) is already connected to the board; it accepts one connection at a time. |
| "Connect failed: …" repeating | Toggle phone Bluetooth off and on, then reset the board |
| Board not detected over USB | Use a data-capable USB-C cable; hold **BOOT** while plugging in |
| Button press says `not sent: connected=0` | The phone isn't connected yet; wait for the green banner |
| `[TOF] !! VL53L1X FAILED` | Check SDA→D4, SCL→D5, 3V3 and GND. Captures still work, just without a distance |
| `[TOF] no valid reading` | The object is too close (under ~4 cm), too far (over ~4 m), or too dark or shiny for the sensor |
| Card: "photo … didn't arrive complete" | The link dropped mid-transfer. The board resets the connection, so wait for the green banner (a few seconds) and press again. Send the serial log if it keeps happening |
| `[CAM] warning: very small JPEG` | The photo is nearly black. Check the lens isn't covered and the room isn't too dark |
| Phone doesn't speak | Check the media volume; install or enable a text-to-speech engine in Android settings (Google Speech Services) |
| App closes right after "Allow" | Make sure the phone runs Android 12+; send `adb logcat` output with an issue |
| "Not enough free storage to unpack the AI model" | Free about 3 GB, then tap **Retry** |
| AI card says Gemma couldn't start | The app keeps working with ML Kit labels. Send `adb logcat -s SceneDescriber` output; tapping **Try loading Gemma again** retries |
| Install fails or "App not installed" | Re-join the parts and check the SHA-256; make sure about 8 GB is free |
