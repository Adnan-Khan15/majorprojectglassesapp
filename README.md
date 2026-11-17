# Smart Assistive Glasses

Glasses that tell you what you're looking at. Press the button on the frame and, a few seconds later, the glasses say something like:

> "a blue pen with Pentel written on it, 36 centimetres"

Everything runs on the phone and the glasses, with no internet connection. The AI model is a file you put on the phone once; the app never sends anything to the cloud. The glasses take the photo and measure the distance, your Android phone works out what the object is, and the glasses speak the answer through their own speaker. Nothing goes to the cloud, and no cable connects the glasses to the phone.

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
 │  button press ─► camera (JPEG) + VL53L0X ToF distance ──────┼── BLE ──►│  reassemble image                     │
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
| CONTROL | `…0002…` | glasses → phone | indicate | Image header. **v2** (16 B): version, flags, image id, size, CRC-32, width, height. v1 (6 B, old firmware): size + distance |
| IMAGE_DATA | `…0003…` | glasses → phone | indicate | JPEG in chunks of min(MTU − 3, 244) bytes, each sent after the previous one is acknowledged. 244 B fits one radio packet; full 512 B chunks were silently dropped by a test phone's Bluetooth stack (cause not established) |
| RESULT_TEXT | `…0004…` | phone → glasses | write | The sentence, as UTF-8 (logged by the board) |
| COMMAND | `…0005…` | phone → glasses | write | Capture, stop playback, volume, test tone, capabilities, ping, resend/abort image, throughput test, settings, mic monitor, ToF read |
| EVENT | `…0006…` | glasses → phone | notify | Button, photo captured, **ToF result** (median, valid samples, spread, flags), playback, credits, errors, capabilities |
| AUDIO_UP | `…0007…` | glasses → phone | notify | Mic audio: 8-byte header (stream, flags, sequence, ADPCM state) + IMA ADPCM |
| AUDIO_DOWN | `…0008…` | phone → glasses | write without response | Speech audio, same frame format |

In v2 the board measures the distance **while** the photo is being sent and reports it as an event afterwards, so the 5 × 200 ms ToF samples add no delay.

Both sides use the same definitions: [`glasses_protocol.h`](esp32-firmware/include/glasses_protocol.h) + [`glasses_frames.h`](esp32-firmware/lib/glasses_core/src/glasses_frames.h) on the board, [`GlassesProtocol.kt`](android-app/app/src/main/java/com/smartglasses/app/ble/GlassesProtocol.kt) + [`Frames.kt`](android-app/app/src/main/java/com/smartglasses/app/ble/Frames.kt) in the app. Tests on both sides check the same byte strings.

### Tech choices

| Part | Choice | Why |
|---|---|---|
| Phone BLE | [Nordic Android BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library) | Queues every GATT operation, which avoids the race conditions of raw `BluetoothGatt` callbacks |
| Board BLE | [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino) | Uses much less memory than the stock ESP32 BLE stack, which matters because the camera shares the heap. The ESP32-S3 only supports BLE (no Classic Bluetooth), so this is the right stack. |
| Text reading | Google ML Kit Text Recognition v2 (on-device) | Reads brand names and labels offline |
| Object description | **[Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)** (Google, Apache 2.0) on **[LiteRT-LM](https://developers.google.com/edge/litert-lm/android)**, using the phone's GPU (falls back to the CPU) | Google's newest small multimodal model. It describes objects in natural language and reads labels itself. The model file (2.6 GB) is imported once from a file on the phone, not bundled in the APK. If it isn't installed yet or can't start on a phone, the app switches to ML Kit Image Labeling so captures still work. |
| Speech | [SAM](https://github.com/0xD34D/SAM-ESP32) → I2S → MAX98357A amp | SAM builds speech from phonemes, so it can say any word, including brand names (a fixed-dictionary synth can't) |

---

## Project status

> **Phase 3 is in progress on the `phase3` branch.** For what has been built, what is verified, and what comes next, see **[docs/PROGRESS.md](docs/PROGRESS.md)**; it is more up to date than the table below.

| Phase | What | Status |
|---|---|---|
| 1 | BLE link: board advertises, phone connects, button sends a test string, phone echoes it back | ✅ Code complete; on-hardware test pending |
| 2 | Camera → JPEG → BLE → image shown on the phone | ✅ Code complete; on-hardware test pending |
| 3 | ToF distance sent along with the image | ✅ Code complete; on-hardware test pending |
| 4 | ML Kit OCR on the received image | ✅ Done ahead of schedule; testable with **Try with a photo** |
| 5 | Object description (Gemma 4 E2B) + latency measurement | ✅ Built in; phone latency not yet measured (see results below) |
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
- about **5.5 GB of free storage** while importing the model (the download + the app's copy); about **2.7 GB** once you delete the download

The app and the AI model are installed **separately**. The APK is about 50 MB; the model (`gemma-4-E2B-it.litertlm`, 2.59 GB) is a file you put on the phone once.

### 1. Install the app
1. From the **[v0.4.0-test pre-release](https://github.com/Adnan-Khan15/majorprojectglassesapp/releases/tag/v0.4.0-test)**, download **`SmartGlasses-v0.4-test.apk`** (107 MB). This build has **no internet permission at all**; everything runs on the phone. If the old v0.3 app is installed, uninstall it first (frees ~5 GB).
2. Open the APK on the phone. Android will warn about installing from an unknown source: tap **Settings**, turn on **Allow from this source**, go back, and tap **Install**. If Play Protect says "unrecognised app", tap **More details → Install anyway**.
3. Open **Glasses** and allow **Nearby devices** (Bluetooth) when asked. The app never uses your location.

### 2. Install the AI model

**Download link (open it on the phone):**
**[⬇ gemma-4-E2B-it.litertlm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm)** (2.59 GB, Google's Gemma 4 E2B, Apache 2.0, from Hugging Face; no account needed). Use Wi-Fi.

**Where does the file go?** Nowhere special: leave it wherever the browser saves it (normally **Downloads**). You don't move or rename it. The app takes it from there:

1. Open the app. On the **AI model** card, tap **Import from file**.
2. In the file picker that opens, go to **Downloads** and tap **`gemma-4-E2B-it.litertlm`**.
3. The app **copies** it into its own private storage and checks it (exact size 2,588,147,712 bytes + SHA-256). This takes a minute or two, with a progress bar. A wrong or incomplete file is rejected with a message.
4. When it says **Model installed and verified ✓**, the app loads Gemma; wait for **Gemma 4 E2B ready on GPU**.
5. **Delete the copy in Downloads** to get 2.6 GB back. The app keeps its own copy (shown on the card as "Storage used by the model").

You only do this once. Installing a newer APK over the app keeps the model; uninstalling the app deletes it.

Until the model is installed, the app still works: photos are described with the basic ML Kit labeller instead.

<details>
<summary>Developers: skip the import with adb</summary>

```bash
scripts/fetch-model.sh --push   # downloads + verifies once, then adb-pushes to the app's drop-in folder
```
The app uses a file at `/sdcard/Android/data/com.smartglasses.app/files/models/gemma-4-E2B-it.litertlm` **in place** (no copy). Tap **Rescan** on the model card after pushing. Install APKs with `adb install -r app-offline-release.apk`.
</details>

---

## Flash the glasses firmware

You need a **Seeed Studio XIAO ESP32S3 Sense** and a USB-C cable that carries data, not just power.

### Option A: prebuilt image (no tools to install)

1. Download **`smartglasses-firmware-v0.4.0-merged.bin`** from the [v0.4.0-test pre-release](https://github.com/Adnan-Khan15/majorprojectglassesapp/releases/tag/v0.4.0-test). It needs the Phase 3 wiring below, and the v0.4 app.
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

### Wiring (Phase 3)

| Part | Pin | Notes |
|---|---|---|
| Push-button | **D0** (GPIO1) to **3V3** | Pressing drives D0 HIGH; the internal pull-down holds it LOW |
| Status light | onboard **user LED** (GPIO21) | Lit while a photo is captured and sent. Nothing to wire |
| MAX98357A amp | **BCLK → D4** (GPIO5), **LRC → D5** (GPIO6), **DIN → D3** (GPIO4), VIN → 5V, GND → GND | 1 W / 8 Ω speaker on the amp's + and − terminals |
| VL53L0X distance sensor | **SDA → D10** (GPIO9), **SCL → D8** (GPIO7), VIN → 3V3, GND → GND | Replaces the earlier VL53L1X |
| Microphone | onboard PDM mic on the Sense board (GPIO42/41) | Nothing to wire |
| Camera | onboard OV2640/OV3660 | Mounted upside down; the firmware flips the image |

- **Don't insert a microSD card.** On the Sense expansion board, GPIO7/8/9 and GPIO21 are the SD card's lines. The firmware uses them for the distance sensor and the LED, and never mounts the card.
- **Recommended:** a **100 µF capacitor** across the amp's VIN and GND, close to the amp. Speaker current peaks can dip the supply; the board logs a brownout as `reset reason 9` at boot.
- D1, D2 and D6 are unused and pulled down in firmware.
- If the distance sensor isn't connected, the glasses still work and the sentence leaves out the distance.

To change pins, edit [`include/pins.h`](esp32-firmware/include/pins.h).

### Serial test console (115200 baud)

| Type | Does |
|---|---|
| `p` | Simulate a button tap |
| `caps` + Enter | What's connected and working, settings, last reset reason |
| `tone 1000 500` + Enter | Play a 1 kHz test tone for 500 ms |
| `vol 60` / `ceil 50` + Enter | Volume (% of the ceiling) / loudness ceiling (% of full scale, default 50) |
| `mic 3` + Enter | Microphone level meter for 3 s |
| `tof 10` + Enter | 10 distance readings, then median and spread |
| `?` + Enter | Full list |

## Test: button press → spoken sentence

1. Flash the firmware and open the serial monitor at 115200 baud. You should see `[CAM] camera OK`, `[TOF] VL53L0X OK`, `[SPK] I2S1 TX ready`, `[MIC] PDM mic ready` and `advertising started … ok`, and hear a short beep.
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

The model is not in git or in the APK. Two build flavors exist:

```bash
cd android-app
./gradlew assembleOfflineRelease        # → app/build/outputs/apk/offline/release/  (no network permission)
./gradlew assembleWithDownloadRelease   # → same app + optional in-app model download
./gradlew installOfflineDebug           # build and install a debug build on a connected phone
./gradlew testOfflineDebugUnitTest      # unit tests
```
Then install the model as described above (or `scripts/fetch-model.sh --push`).

In Android Studio, use **File → Open → `android-app/`**, wait for the Gradle sync, then press **Run ▶**.

### Code map

```
android-app/app/src/main/java/com/smartglasses/app/
├── GlassesApp.kt              # Application; owns the BLE link and AI model for the whole process lifetime
├── ai/
│   ├── ModelStore.kt          # finds / imports / verifies the model file (never bundled)
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
├── src/main.cpp               # button → camera + VL53L0X → BLE; camera/tof/speaker/mic/console modules beside it
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
| `[TOF] !! VL53L0X init failed` | Check SDA→D10, SCL→D8, 3V3 and GND, and that no microSD card is inserted. Captures still work, just without a distance |
| No distance in the sentence | The VL53L0X reads about 5 cm to 1.2 m, straight ahead only. Dark, shiny or far objects often give no reading. Try `tof 10` in the serial monitor |
| Card: "photo … didn't arrive complete" | The link dropped mid-transfer. The board resets the connection, so wait for the green banner (a few seconds) and press again. Send the serial log if it keeps happening |
| `[CAM] warning: very small JPEG` | The photo is nearly black. Check the lens isn't covered and the room isn't too dark |
| Phone doesn't speak | Check the media volume; install or enable a text-to-speech engine in Android settings (Google Speech Services) |
| App closes right after "Allow" | Make sure the phone runs Android 12+; send `adb logcat` output with an issue |
| "Not enough free storage" when importing the model | Free about 3 GB, then import again |
| AI card says Gemma couldn't start | The app keeps working with ML Kit labels. Send `adb logcat -s SceneDescriber` output; tapping **Try loading Gemma again** retries |
| Install fails or "App not installed" | Uninstall the old version first (for example the 2.7 GB v0.3 build that bundled the model) |
| Import says the file "is not the expected model" | The download is incomplete or a different file. Download it again; the size must be exactly 2,588,147,712 bytes |
