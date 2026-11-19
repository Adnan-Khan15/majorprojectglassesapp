# Progress and handoff

**Read this first if you are continuing the project.** It records what has been built, why it was built that way, what is verified and what isn't, and exactly what comes next. Keep it updated at the end of every milestone.

*Last updated: 2026-11-19, after the first v0.6.0-test results (v0.6.1). All Phase 3 milestones (M0–M8, plus M3.1) are implemented on branch `phase3`. App 0.6.0, firmware 0.6.0.*

---

## 1. Where things stand

| Item | State |
|---|---|
| Branch | `phase3` (pushed). `main` is still v0.3. Pull request [#1](https://github.com/Adnan-Khan15/majorprojectglassesapp/pull/1) `phase3 → main` is open; **merge after the hardware tests in §6 pass** |
| Test builds | GitHub pre-releases: `v0.4.0-test` (M0–M3) and **`v0.6.0-test`** (M0–M8). Each has the offline **debug** APK + the merged firmware image (flash at `0x0`) |
| AI model | Not in the APK or git. [`gemma-4-E2B-it.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm), 2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, imported in the app |
| Tester | The user's friend (another city) has the glasses, a **Samsung Galaxy S24 (Android 17)** and a PC. Amp: VIN 5V, GAIN and SD unconnected. He installs from the release page; results come back as screenshots |
| Verified on hardware | **v0.4.0-test only (M0–M3), 2026-11-18.** Protocol v2 handshake; tap → photo + ToF → spoken sentence with distance; ToF overlap; live ToF; throughput 66.8 KB/s up / 48.5 KB/s down, 0 loss. Test tone audible but very quiet |
| v0.6.0-test results (2026-11-19) | Tester: mic, tones, distance, throughput, tap and hold-to-talk all **work**. Problems: **speech on the glasses is too quiet** (tones are fine) and answers take **~8 s**. v0.6.1 adds speech loudness processing; latency CSV requested to find where the 8 s goes |
| Not yet verified | Speech loudness fix (v0.6.1), ToF calibration, STT spike, TalkBack pass, R8 release build |
| Automated tests | 64 Android unit tests, 10 firmware native tests, 4 Python tests, all passing. The offline APKs are checked for network permissions by `scripts/check-apk-permissions.sh` (pass) |

**Status words:**
- *Done (host-tested)*: built and covered by tests that run on a computer.
- *Implemented, awaiting hardware verification*: touches the board, speaker, mic, BLE radio or phone GPU, so only [`HARDWARE_TESTS.md`](HARDWARE_TESTS.md) can prove it.

---

## 2. History before Phase 3

| Version | What it did | Hardware status |
|---|---|---|
| v0.1 | BLE link test | Verified 2026-10-02: MTU 515, ~61 ms echo |
| v0.2 | Gemma 4 E2B bundled in a 2.7 GB APK | GPU load 24.6 s; first inference 9.1 s (gallery photo) |
| v0.3 | Button → photo + VL53L1X distance → Gemma → phone speaks | Retested as v0.3.1; one black 3.6 KB image without distance was seen once, **cause unknown** |

Phase 3 was specified in the user's `CLAUDE_CODE_PHASE3_PROMPT.md` (not in the repo). Its five goals:
1. Stop bundling the model.
2. Finish the app UI.
3. Make the glasses' speaker work.
4. Add hold-to-talk voice questions.
5. Switch to a VL53L0X with trustworthy distances.

The plan and deviations are in [`PHASE3_PLAN.md`](PHASE3_PLAN.md).

---

## 3. What Phase 3 built

| Milestone | Commit | One line |
|---|---|---|
| M0 Baseline + instrumentation | `0cf8379` | Latency CSV per interaction, `latency_report.py`, black-image diagnostics, `CLAUDE.md` |
| M1 Model delivery | `89edeb4` | `ModelStore`: import from file / adb drop-in / download; SHA-256; offline + withDownload flavors; APK 2.7 GB → 50 MB |
| M2 Firmware bring-up | `579cd36` | Phase 3 pins, VL53L0X median filter, I2S speaker tones with ceiling, PDM mic meter, serial console, host-tested C core |
| M3 Protocol v2 + throughput spike | `f9e2f88` | COMMAND / EVENT / AUDIO_UP / AUDIO_DOWN, image id + CRC + resend, ToF during transfer, capabilities, throughput test |
| M3.1 Fast photos, warm-up, volume | `4f895d5` | Photo chunks as notifications; Gemma warm-up; volume 100 % of the 50 % ceiling; AppSettings |
| M4 Speaker path | `28752e2` | Phone TTS → 16 kHz IMA ADPCM → credit-paced stream → 2 s PSRAM buffer → I2S; phone fallback |
| M5 Voice pipeline (phone) | `5038899` | Gemma audio questions with the JSON contract, Android STT alternative, AnswerComposer rules, STT spike tools |
| M6 Hold-to-talk | `3d69a3d` | Firmware tap/hold state machine, earcons, mic streaming, barge-in; phone reassembly with gap fill |
| M7 App UI | `041df03` | Setup wizard, Home, History, Settings, Diagnostics (calibration, mic test, throughput, latency, spike) |
| M8 Release hygiene | this commit | `check-apk-permissions.sh`, README rewrite, docs, v0.6.0-test |

### M0: Baseline and instrumentation
- **`metrics/InteractionTimeline` + `LatencyLog`:** one CSV row per interaction (`files/latency/latency.csv`). It records the phone-clock time of each stage:
  - `button_down`, `record_start`, `button_up`, `photo_captured`
  - `image_header`, `image_received`, `audio_received`, `tof_received`
  - `transcript_ready`, `ocr_done`, `first_model_output`, `answer_complete`
  - `tts_ready`, `play_started`

  plus image bytes, KB/s, ToF median/flags/samples/spread, engine, question, answer and error. Board stages include one BLE hop.
- **Model output is streamed** (`sendMessageAsync`), so the first-output time is real. `StreamAccumulator` handles both delta and cumulative chunks.
- **`scripts/latency_report.py`:** p50/p90 per stage pair over successful runs; warns below 10.
- **Firmware diagnostics for the black image** (a workaround, not a fix): reset reason (9 = brownout), capture index, time since camera init, heap/PSRAM, sensor PID. A frame under 8 KB is retaken once and both sizes are logged.

### M1: Model delivery
- **`ai/ModelSpec` + `assets/model-manifest.json`** describe the file. **`ai/ModelStore`** is a state machine: Missing / Importing / Verifying / Ready / Corrupt.
- **Sources:**
  - developer drop-in `getExternalFilesDir("models")`, used in place
  - app storage `files/models`, filled by an **import** (system file picker, no storage permission; checks size and free space; hashes during the copy; temp file + rename) or a **download** (`withDownload` flavor only: HTTP Range resume, Wi-Fi only by default)
- **SHA-256 result cached** by (path, size, mtime).
- **`offline` flavor removes INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE**, which ML Kit merges in. Release builds use R8 with LiteRT-LM keep rules: **50 MB**. The R8 release has **never been run on a phone**; test builds are debug.
- **Without a model, taps still work** with the ML Kit labeller.

### M2: Firmware bring-up
- **Pins** (`include/pins.h`):
  - button GPIO1
  - LED GPIO21 (onboard, **assumed active-LOW**)
  - MAX98357A on I2S1: BCLK 5, LRC 6, DOUT 4
  - PDM mic on I2S0: 42/41
  - VL53L0X on SDA 9 / SCL 7
  - pull-downs on D1, D2, D6
  - no SD card (GPIO7/8/9/21 are SD lines)
- **Modules:** `camera` (PSRAM copy per shot for resend), `tof` (200 ms budget, median of N, async), `speaker`, `mic` (both PDM slots read, the active one picked per recording, DC blocker + gain), `settings` (NVS), `console`.
- **`lib/glasses_core`** (pure C, `pio test -e native`): IMA ADPCM, CRC-32, ToF filter, frame layouts. **Golden ADPCM vectors** come from an independent Python implementation (`scripts/gen_adpcm_vectors.py`); the C and Kotlin codecs both match them.
- **Platform pinned** to `espressif32@7.1.3` (Arduino core 2.0.17, legacy `driver/i2s.h`).

### M3: BLE protocol v2 + throughput spike
- **New characteristics:** COMMAND (0005, write), EVENT (0006, notify), AUDIO_UP (0007, notify) and AUDIO_DOWN (0008, write-no-response). The byte layouts are in `glasses_frames.h` ↔ `Frames.kt`, and both sides' tests assert the same bytes.
- **Image header v2** (16 B, with CRC-32). The phone verifies it and asks for a resend once. v1 headers are still accepted.
- **ToF measured during the transfer**, sent as `TOF_RESULT` per image id. The phone waits for it in parallel with the model.
- **Capabilities handshake** (incl. reset reason), ping, tone, ToF read.
- **Throughput test** both directions, plus a 2M PHY request.

### M3.1: Fast photos, warm-up, volume (from the first hardware results)
- **Photos:** chunks go as **sequence-numbered AUDIO_UP notifications** (stream 5, `IMG_FLAG_NOTIFY`), because indications measured 5.3 KB/s vs 66.8 KB/s for notifications.
  - Opt-in per connection (`PARAM_IMAGE_MODE`, reset on disconnect).
  - A sequence gap fails the photo as resendable; resends always use indications.
  - Settings → Fast photo transfer toggles it, for A/B tests.
- **Gemma warm-up** inference after loading: the first answer was 8.2 s vs 3.3 s for the second.
- **Volume default** 100 % of the 50 % ceiling (the test tone was very quiet at 60 %).
- **`settings/AppSettings`:** every user setting. The app pushes volume / ceiling / hold time / photo mode once per connection.

### M4: Speech on the glasses
- **Phone:** `TtsEngine.synthesize` renders the sentence with offline TTS (prefers an offline en-IN voice) → `Wav` → `Resampler` (windowed-sinc + interpolation) → `SpeechFramer`: 400-sample IMA ADPCM frames, each starting with its encoder state.
- **`GlassesSpeaker`** streams frames on AUDIO_DOWN **within the credits** (`CreditWindow`: cumulative, wrap-safe, never backwards).
- **Board:**
  - decodes into a **2 s PSRAM ring**, **pre-buffers 400 ms** (or starts at END), 10 ms fade-in
  - plays at the volume ceiling
  - silence + `UNDERRUN` on underrun; lost frames are skipped
  - `PLAY_STARTED` / `PLAY_DONE`; `stop()` for barge-in; tones keep priority
- **`SpeechOutput`** replaces `ResultAnnouncer`: speak on glasses / phone / both, with a phone fallback. `play_started` comes from the glasses' event.

### M5: Voice pipeline (phone mic first)
- **`GemmaVision`** starts with an **audio backend (CPU)** if it can: GPU+audio → GPU → CPU+audio → CPU. `audioSupported` tells the app which.
- **`ask()`** sends photo + WAV (or text) + OCR hint and expects the **JSON contract**: `heard`, `target`, `found`, 3×3 `position`, `answer`, `confidence`. `AiAnswer.parse` is lenient on formatting and strict on content; the call is retried once.
- **`SpeechToText`:** Gemma audio (default) or **Android on-device** (`createOnDeviceSpeechRecognizer`, our audio via `EXTRA_AUDIO_SOURCE`, Android 13+, offline).
- **`AnswerComposer`**, the single source of spoken text (fully unit-tested):
  - **every number comes from the ToF**, always "about"
  - rounding: nearest 5 cm under 1 m, 10 cm above; imperial option; calibration (scale + offset)
  - invalid reading or > 1.2 m → "I couldn't get a reliable distance."
  - question mode: a distance only if the target is in the ToF region (centre, or centre column by setting); otherwise a turn-left/right/up/down hint
  - "I can't see X" when not found; "I think…" when unsure
  - distances stated by the model are removed; "safe / clear to cross" claims are replaced with "I can't tell if it's safe."
  - brief = 2 sentences, detailed = 4
- **OCR always runs** in question mode (~0.2 s). With Gemma hearing the audio, the question isn't known before the call, so "read this" detection can't come first.
- **`AssistantSession`:** one pipeline for taps, glasses questions and phone-mic questions. For a phone-mic question the glasses take a photo at release, otherwise the latest photo is used. **Every failure is spoken.** `InteractionRecord` events feed History.
- **STT spike:** record clips, run both engines → `results.csv`; `scripts/wer.py`; [`STT_SPIKE.md`](STT_SPIKE.md).

### M6: Hold-to-talk on the glasses
- **Firmware button state machine:**
  - **Tap:** released before `hold_ms` (default 500) → photo.
  - **Hold:** `HOLD_DETECTED` → rising earcon → waits until the speaker is idle (**never records while playing**) → mic streams ADPCM on stream 1 while held (cap 10 s).
  - **Release:** header-only END frame, `RECORD_STOP`, "thinking" earcon, **photo + ToF at release**.
  - **Barge-in:** a press while speaking stops speech at once.
  - Serial `h` simulates a hold. `CMD_MIC_TEST` streams N s on stream 4.
- **Phone:** `MicStreamAssembler` (lost frames → equal-length silence; END or `RECORD_STOP` closes) and `GlassesMicReceiver`. The board's button / record / capture events are added to the latency timeline. A button press also stops phone speech.

### M7: App UI
- **Setup wizard:**
  - Bluetooth (required), notifications, microphone, AI model, glasses, each with live ✓ and plain instructions
  - The old `PermissionGate`, `MainScreen`, `ModelSection` and `LinkTestCard` were removed.
- **Home:**
  - status chips in words + icon (details: firmware, brownout warning, voice)
  - Describe what's ahead, Hold to ask with phone mic, Try with a photo
  - result card: photo, question, answer, distance quality, Replay / Stop, timing
- **History:** `history/HistoryStore` (JSON + 320 px thumbnail, max 300). The detail view shows transcript, ToF quality, per-stage latency and the raw reply. Export JSON / CSV via the system "save as"; delete.
- **Settings:** every option from the prompt. Changes are pushed to the glasses immediately.
- **Diagnostics:**
  - tones, test sentence, ping
  - **ToF live + calibration walk-through** (least squares; saved)
  - mic meter, mic test + playback
  - throughput, latency export / clear, STT spike, BLE log
- **Accessibility:** `StateAnnouncer` speaks connect / disconnect / AI ready. Haptics, TalkBack labels, live regions, ≥ 48 dp targets, larger type.

### M8: Release hygiene
- **`scripts/check-apk-permissions.sh`** reads the built APK with `aapt2`. PASS on both offline APKs: Bluetooth, notifications, record audio and foreground service only.
- README rewritten; this file; `PHASE3_PLAN.md` status; `CLAUDE.md`.
- Pre-release **v0.6.0-test**.

---

## 4. How one interaction works now

```
TAP  board: BUTTON_DOWN → (release < hold_ms) BUTTON_UP → capture JPEG (id N) → PHOTO_CAPTURED
            → ToF 5 × 200 ms on a background task ‖ header v2 (CRC) + chunks (fast: AUDIO_UP stream 5)
            → TOF_RESULT(N)
     phone: verify CRC (resend once if bad / chunk lost) → OCR + Gemma phrase ‖ await ToF(N)
            → AnswerComposer.composeDescribe → SpeechOutput → TTS → ADPCM → AUDIO_DOWN (credits)
            → board buffers 400 ms → PLAY_STARTED → speaker

HOLD board: BUTTON_DOWN → HOLD_DETECTED → earcon → RECORD_START → mic ADPCM frames (stream 1)
            → release: END + RECORD_STOP + BUTTON_UP → thinking earcon → photo + ToF (MODE_QUESTION)
     phone: MicStreamAssembler → speech check → OCR → Gemma(photo + WAV) → JSON (retry once)
            → AnswerComposer.composeAnswer (ToF only if centred) → SpeechOutput → glasses speaker
```

---

## 5. Decisions and findings

| Topic | Decision / finding | Why |
|---|---|---|
| ToF timing | Measured during the transfer, sent as an event | 5 × 200 ms would add ~1 s per tap. Hardware: the ToF event arrived 49–85 ms after the image |
| Photo transport | Notifications + sequence numbers + CRC, indications only for resends | Measured 66.8 vs 5.3 KB/s (the prompt said to change only on data; this is the data) |
| Audio codec | IMA ADPCM, 16 kHz, 25 ms frames with per-frame state | ~9.6 KB/s incl. headers; 5–7× headroom on the measured link; a lost frame costs only 25 ms |
| Flow control | Cumulative credits from the glasses | A lost credit event can't stall or overflow the buffer |
| Mono on MAX98357A | Same sample on L and R | SD-pin wiring can't change what's heard |
| Loudness | Software ceiling 50 %, volume % of it, warning above 50 % | Speaker protection; GAIN wiring unknown |
| Distance wording | Composer in code, never the model | The prompt's rule: numbers only from the sensor |
| Grid vs boxes | 3×3 grid; "distance region" setting = centre cell or centre column | Small VLMs are more reliable with a grid; a finer region would need boxes (not measured) |
| OCR in questions | Always (≈0.2 s) | The question is only known after Gemma answers in audio mode |
| STT default | Gemma audio, with Android on-device as a switch | No extra size; the spike decides |
| Flavors | Added in M1 (not M8) | Download needs INTERNET; avoids adding then removing it |
| ML Kit permissions | Stripped in the offline manifest | ML Kit merges INTERNET; checked on the built APK |
| Tap timing | Decided at release (M6) | Needed to tell tap from hold; a tap starts up to `hold_ms` later than v0.3 |
| R8 | On for release; test builds are debug | Release never run on a device |
| Arduino core | 2.0.17 pinned | Legacy I2S API used for PDM RX (I2S0) + TX (I2S1) |

**Unknowns that must not be stated as facts:** why 512-byte chunks stalled, and why one black 3.6 KB image appeared.

---

## 6. What to do next

### 6.1 Hardware tests for v0.6.0-test (in this order)
1. **M3.1** (3.1.x): fast photo KB/s vs slow; warm-up; volume and the four tones; **amp wiring answers** (VIN 5V or 3V3, GAIN, SD).
2. **M4** (4.x): test sentence on the glasses, barge-in, underruns at distance, phone fallback, brownout over 2 minutes.
3. **M5** (5.x): phone-mic questions, centre / left / not found / safety / reading, `audio input available` in logcat.
4. **M6** (6.x): tap vs hold, 10 s cap, silence, barge-in, 10 timed questions → `latency_report.py --mode question`.
5. **M7** (7.x): setup wizard, TalkBack, History export, **ToF calibration**.
6. **M2 leftovers**: LED polarity (2.3), mic slot and levels (2.8–2.9).
7. **Evaluations:** `TOF_TEST_PROTOCOL.md` (full accuracy table) and `STT_SPIKE.md` (20+ clips in the user's voice) → decide the default recogniser.

Record phone, Android version and firmware version with every result. Update `HARDWARE_TESTS.md` and §1 of this file.

### 6.2 Open questions for the user
1. ~~MAX98357A wiring~~ **Answered: VIN on 5V; GAIN and SD not connected** (GAIN floating = 9 dB). If speech is still too quiet at volume 100 % / ceiling 50 %: first try ceiling 70 % in the app; only then GAIN → GND (12 dB, +3 dB), keeping the ceiling at 50 %. Never tie SD to GND (that shuts the amp down).
2. Keep the status LED on GPIO21? (Default yes.)
3. ~~Target phone~~: Galaxy S24, Android 17.
4. `offline` as the default release flavor? (Default yes; releases ship offline.)

### 6.3 After the tests
- Fix whatever the tests find, then **merge PR #1**.
- Run the **R8 release** build on the phone and ship it instead of debug (50 MB vs 107 MB).
- **Stretch (only if measured usable):** a closed "find" loop (turn, re-capture). It's limited by the ~3–9 s per AI call.
- If the STT spike rejects both engines: evaluate Vosk small-en / sherpa-onnx whisper-tiny.en via the model manifest.

---

## 7. How to build, test and release

```bash
# Android (JDK 17)
cd android-app && export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testOfflineDebugUnitTest assembleOfflineDebug    # 64 tests + test APK
./gradlew assembleOfflineRelease assembleWithDownloadRelease
../scripts/check-apk-permissions.sh

# Firmware (pip install platformio)
cd esp32-firmware && pio run && pio test -e native
B=.pio/build/xiao_esp32s3
python ~/.platformio/packages/tool-esptoolpy/esptool.py --chip esp32s3 merge_bin -o firmware-merged.bin \
  --flash_mode keep --flash_freq keep --flash_size keep 0x0 $B/bootloader.bin 0x8000 $B/partitions.bin \
  0xe000 ~/.platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin 0x10000 $B/firmware.bin

# Python
python3 -m unittest discover -s scripts/tests
```

**Releases:**
- Push `phase3`, then create a GitHub **pre-release** named after the firmware version (`v0.6.0-test`).
- Attach the offline debug APK and the merged firmware.
- `gh` isn't logged in on the dev Mac; releases were made with the REST API using the keychain git credential.
- The model is always linked from Hugging Face (GitHub's 2 GiB file limit).

**Rules (also in `CLAUDE.md`):**
- Protocol twins change together, with byte tests on both sides.
- A tap must always work.
- Never commit `*.litertlm`, keystores or `local.properties`.
- Delete dead code.
- Hardware-touching work is "implemented, awaiting hardware verification".

---

## 8. Known gaps

- Phase 3 after M3 has **no hardware evidence yet** (see §6.1).
- The R8 release APK has never run on a phone.
- The `withDownload` flavor (in-app download) is untested on a phone and not attached to releases.
- LED polarity (GPIO21 active-LOW) is assumed from Seeed's docs.
- Which PDM slot carries the mic is decided at runtime per recording; the level check at the start of each recording should be confirmed (2.8).
- `StreamAccumulator`'s delta-vs-cumulative handling is untested on a phone; the first-output time in the latency log will show it.
- Hold-to-talk records only **after** the listening beep finishes (~150 ms), so words spoken before it are lost by design.
- The distance region is limited to grid cells (centre or centre column), not a continuous fraction.
- With Gemma audio, the transcript time isn't separable from the answer time (both marked at answer complete).

---

## 9. File map

```
android-app/app/src/main/java/com/smartglasses/app/
  GlassesApp.kt · ai/ (ModelSpec, ModelStore, ModelActions, GemmaVision+Prompts, SceneDescriber, StreamAccumulator,
  AnswerComposer, TofCalibration, MlKitVision) · assistant/ (AssistantSession, StateAnnouncer) · audio/ (ImaAdpcm, Wav,
  Resampler, SpeechFramer+CreditWindow, PcmPlayer) · ble/ (GlassesProtocol, Frames, GlassesBleManager, GlassesLink,
  ImageAssembler, ThroughputTest) · history/HistoryStore · metrics/ (InteractionTimeline, LatencyLog) ·
  service/GlassesService · settings/AppSettings · speech/ (TtsEngine, GlassesSpeaker, SpeechOutput) ·
  voice/ (PhoneMic, SpeechToText+AudioCheck+AndroidOnDeviceStt, GlassesMicReceiver+MicStreamAssembler, SttSpike) ·
  ui/ (MainActivity, AppRoot, SetupWizard, HomeScreen, HistoryScreen, SettingsScreen, DiagnosticsScreen, Components,
  ModelSetupCard, PhoneMicButton, SttSpikeSection, AssistantViewModel, Theme)
android-app/app/src/{offline,withDownload}/   flavor manifests + ModelDownloader
android-app/app/src/test/                     11 test classes + adpcm_golden.json
esp32-firmware/include/                       pins.h, glasses_protocol.h
esp32-firmware/lib/glasses_core/              ima_adpcm, crc32, tof_filter, glasses_frames (C)
esp32-firmware/src/                           main, ble_link, camera, tof, speaker, mic, console, settings, throughput
esp32-firmware/test/test_core/                native tests + adpcm_golden.h
scripts/                                      fetch-model.sh, latency_report.py, gen_adpcm_vectors.py, wer.py,
                                              check-apk-permissions.sh, tests/
docs/                                         PROGRESS.md, PHASE3_PLAN.md, HARDWARE_TESTS.md, TOF_TEST_PROTOCOL.md, STT_SPIKE.md
```
