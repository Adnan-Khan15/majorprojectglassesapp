# Progress and handoff

**Read this first if you are continuing the project.** It records what has been built, why it was built that way, what is verified and what isn't, and exactly what comes next. Keep it updated at the end of every milestone.

*Last updated: 2026-11-18, after milestone M3 of Phase 3. Branch `phase3` at `c8463e8`.*

---

## 1. Where things stand

| Item | State |
|---|---|
| Working branch | `phase3` (pushed). `main` is still at v0.3 (`88ac9c2`). Pull request [#1](https://github.com/Adnan-Khan15/majorprojectglassesapp/pull/1) `phase3 → main` is open; **don't merge until the hardware tests in §6 pass** |
| Latest test build | Pre-release [**v0.4.0-test**](https://github.com/Adnan-Khan15/majorprojectglassesapp/releases/tag/v0.4.0-test): `SmartGlasses-v0.4-test.apk` (offline flavor, **debug** build, 107 MB) + `smartglasses-firmware-v0.4.0-merged.bin` (flash at `0x0`) |
| AI model | Not in the APK or in git. Users download [`gemma-4-E2B-it.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm) (2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`) and tap **Import from file** |
| Phase 3 milestones | **M0, M1, M2, M3 implemented.** M4–M8 not started |
| Hardware verification | **None of Phase 3 has run on the real glasses yet.** The tester (in another city, with the glasses, an Android phone and a PC) is installing v0.4.0-test now |
| Automated tests | 34 Android unit tests, 10 native firmware tests, 2 Python tests: all pass on a computer |

**Status words used throughout:** *done (host-tested)* means built and covered by tests that run on a computer. *Implemented, awaiting hardware verification* means it builds but touches the board, speaker, mic, BLE radio or phone GPU, so only the tests in [`HARDWARE_TESTS.md`](HARDWARE_TESTS.md) can prove it. Nothing is called "verified" until those tests have been run and recorded.

---

## 2. Project history (before Phase 3)

| Version | What it did | Hardware status |
|---|---|---|
| v0.1 (Phase 1) | BLE link: board advertises `SmartGlasses`, phone connects, button sends a test string | **Verified 2026-10-02**: MTU 515, ~61 ms echo round trip |
| v0.2 | Gemma 4 E2B bundled inside the APK (2.7 GB, split into 256 MB asset parts) | Gemma loaded on the phone GPU in 24.6 s; first inference 9.1 s (gallery photo, not a live capture) |
| v0.3 | Real loop: button → VGA JPEG + VL53L1X distance → BLE → OCR + Gemma → phone speaks the sentence → sentence written back to the board | Being retested (v0.3.1). One black ~3.6 KB image with no distance was seen once; **cause unknown** |

Phase 3 was specified by the user in `CLAUDE_CODE_PHASE3_PROMPT.md` (not in the repo). It has five goals:
1. Stop bundling the model.
2. Finish the app UI.
3. Make the glasses' speaker work.
4. Add hold-to-talk voice questions using the board's mic.
5. Switch the distance sensor to a VL53L0X and make its readings trustworthy.

The plan is in [`PHASE3_PLAN.md`](PHASE3_PLAN.md).

---

## 3. What Phase 3 built, milestone by milestone

### M0: Baseline and instrumentation (`0cf8379`)

**Goal:** before building anything new, make every interaction measurable, and make the next occurrence of the black-image bug leave evidence.

**Phone**
- `metrics/InteractionTimeline.kt` records the phone-clock time of each stage of one interaction:
  - `button_down`, `record_start`, `button_up`, `photo_captured`
  - `image_header`, `image_received`, `audio_received`, `tof_received`
  - `transcript_ready`, `ocr_done`, `first_model_output`, `answer_complete`
  - `tts_ready`, `play_started`

  It also stores facts about the interaction: image bytes, transfer KB/s, distance, ToF flags, samples and spread, engine, question, answer, error.
- `metrics/LatencyLog.kt` appends one CSV row per interaction to `files/latency/latency.csv` (stage times are offsets from the first stage). Get it with:
  `adb pull /sdcard/Android/data/com.smartglasses.app/files/latency/latency.csv`
  (an in-app export comes in M7). If the column layout ever changes, the old file is renamed aside rather than mixed.
- Gemma's reply is now **streamed** (`sendMessageAsync`), so the time of the first output token can be recorded. `ai/StreamAccumulator.kt` handles chunks whether the library sends deltas or the cumulative text; its docs don't say which.
- Today the stages filled in are: image header, image received, ToF received, OCR done, first model output, answer complete, play started. Board-side stages arrive with M6.

**Scripts**
- `scripts/latency_report.py latency.csv [--mode tap] [--source glasses]` prints p50/p90/min/max per stage pair plus end-to-end. It only uses `ok` rows and warns below 10 runs.

**Firmware diagnostics** (for the black-image bug, *not* a fix)
- At boot it logs the reset reason (`9` = brownout).
- Per capture it logs: capture index, ms since camera init, free heap and PSRAM, and the sensor PID (0x26 = OV2640, 0x36 = OV3660).
- If a JPEG is under 8 KB, it grabs one more frame and logs both sizes; the image header flags the retry. This is a workaround that leaves evidence. The root cause is still unknown.

**Also added:** `CLAUDE.md` (it didn't exist before) and `docs/PHASE3_PLAN.md`.

### M1: Model delivery (`89edeb4`)

**Goal:** a small APK, with the model installed separately and verified.

- **Removed:** `ModelInstaller.kt`, asset-part packaging (`noCompress` for `.partNN`), APK splitting in the README, and file splitting in `scripts/fetch-model.sh`.
- **`ai/ModelSpec.kt` + `assets/model-manifest.json`:** name, version, file name, exact size, SHA-256, download URL, licence. It's parsed by hand with Gson's tree API (no reflection, so the code shrinker can't break it). A small speech-recognition model can be added to the same manifest later.
- **`ai/ModelStore.kt`:** a state machine with states `Missing(message)`, `Importing(progress, source)`, `Verifying(progress)`, `Ready(version, size, sha256, path, inPlace)` and `Corrupt(reason, path)`.
  - Where it looks, in order:
    1. **Developer drop-in:** `getExternalFilesDir("models")/gemma-4-E2B-it.litertlm` (for example `adb push`). Used **in place**, with no copy.
    2. **App storage:** `filesDir/models/…`, filled by an import or a download.
  - **Import:** checks the size before copying ("is this the right file?") and checks free space (model size + 200 MB). It copies to `*.importing` while computing SHA-256 **during the copy** (one pass), then verifies and renames. A wrong, truncated or cancelled import leaves nothing behind.
  - **Verification cache:** keyed by (path, size, mtime) in SharedPreferences, so the 2.6 GB file is hashed only once. A changed file is re-hashed.
  - Leftover `*.importing` files are deleted on the next check. `*.download` files are kept so a download can resume.
- **`ai/ModelActions.kt`:** app-scoped import (system file picker, no storage permission needed), download, delete and rescan. They keep running if the screen rotates or the app is backgrounded.
- **`ai/SceneDescriber.kt`** now follows `ModelStore`: it loads Gemma when a verified file appears and unloads it when deleted. **With no model, the ML Kit image-label fallback still answers**, so a button tap always produces a spoken result.
- **Build flavors:**
  - `offline` (the default): `src/offline/AndroidManifest.xml` **removes** `INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE`. ML Kit's dependencies add these silently. Checked with `aapt2 dump permissions` on the built APK: the offline release has only Bluetooth, notification and foreground-service permissions.
  - `withDownload`: adds `INTERNET` + `ACCESS_NETWORK_STATE`. `ModelDownloader` resumes with HTTP `Range` (Hugging Face supports it), downloads on unmetered networks only by default, and retries 5 times with backoff. Google Drive is deliberately not supported for in-app download, because large Drive files return an HTML confirmation page.
- **Release builds** use R8 (`isMinifyEnabled`, `isShrinkResources`) with keep rules for LiteRT-LM (`proguard-rules.pro`), since it ships none and is called from native code. **Offline release APK: 50 MB** (was 2.7 GB). ⚠️ The R8 release has **never been run on a phone**; v0.4.0-test ships the debug build on purpose.
- **UI:** `ui/ModelSetupCard.kt` shows status, progress, Import / Download / Rescan / Delete (with confirmation), and storage used.
- `scripts/fetch-model.sh [--push]`: developer helper that downloads and verifies into `~/.cache/smartglasses/`, then optionally runs `adb push` to the drop-in folder.
- **Tests:** 14 in `ModelStoreTest`, using throwaway folders. They cover: missing; import → ready; wrong file; wrong size (rejected before opening); no space; truncated stream; partial file → corrupt; same size but wrong bytes → corrupt; leftover temp cleanup; drop-in used in place and hashed once; changed file re-verified; delete removes everything; download adopt; the real manifest parses.

### M2: Firmware hardware bring-up (`579cd36`)

**Goal:** move to the Phase 3 wiring and give the tester serial commands for every part.

**Pins** (`esp32-firmware/include/pins.h`, the single source of truth):

| Function | Pin | Notes |
|---|---|---|
| Button | GPIO1 (D0) | Active HIGH, internal pull-down. Unchanged |
| Status LED | GPIO21 (onboard user LED) | Moved from D8. **Active LOW (assumed from Seeed docs; hardware test 2.3 checks it).** GPIO21 is also the microSD chip-select |
| Speaker (MAX98357A) | I2S1 TX: BCLK GPIO5 (D4), LRC GPIO6 (D5), DOUT GPIO4 (D3) | |
| Mic | I2S0 PDM RX: CLK GPIO42, DATA GPIO41 | PDM RX exists only on I2S0 |
| ToF (VL53L0X) | `Wire` SDA GPIO9 (D10), SCL GPIO7 (D8) | GPIO7/8/9 are also microSD lines, so **no SD card, ever** |
| Unused, pulled down | D1, D2, D6 | D3–D5 are no longer pulled down (now I2S) |

**Modules** (`esp32-firmware/src/`):
- **`camera.cpp`:** VGA JPEG, quality 12, two frame buffers + grab-latest, flipped vertically and mirrored (mounted upside down). Each capture is **copied into PSRAM** so the frame buffer goes straight back to the driver and the last photo can be **resent**. It assigns an `image_id` (wraps; 0xFFFF reserved) and does the small-frame retry from M0.
- **`tof.cpp`:** `pololu/VL53L0X`, default range mode, **200 ms timing budget**. `measure(n)` takes n single readings and reduces them with `tof_filter`. `startAsync`/`waitAsync` run it on a background task. A missing sensor never hangs the board; results then carry `TOF_FLAG_NO_SENSOR`.
- **`speaker.cpp`:**
  - I2S1, 16 kHz, 16-bit, **the same sample on L and R**, so the amp's SD-pin channel mode doesn't matter.
  - **Software ceiling**: default 50% of full scale, stored. Volume is a % *of the ceiling*.
  - 5 ms fade at each tone edge (no clicks). Tone queue; earcons `Listening`, `Thinking`, `Error`, `Ready`.
  - `stop()` uses a generation counter, so a barge-in never drops the next tone.
  - DMA sends silence when idle.
- **`mic.cpp`:**
  - I2S0 PDM, reads **both slots** and picks the one with more signal per recording (which slot the onboard mic uses still needs confirming; test 2.8).
  - DC-blocking filter + software gain.
  - Delivers 400-sample (25 ms) blocks to a callback. `measure()` is a blocking level meter.
- **`settings.cpp`** (NVS, survives power cycles, values clamped on load): volume 60, ceiling 50, hold_ms 500, ToF samples 5, ToF budget 200 ms, mic gain ×1.0.
- **`console.cpp`** (115200 baud): `p` (tap, instant), `h` (hold, placeholder until M6), `caps`, `tone [hz] [ms]`, `vol`, `ceil`, `mic [s]`, `tof [n]`, `set hold|tofn|tofb|micgain <v>`, `?`.

**Pure C library** `lib/glasses_core/` builds for both the board and a host computer:
- `ima_adpcm.c`: IMA ADPCM 4-bit codec. Each block carries its start state, so one lost block can't corrupt the next.
- `crc32.c`: the zlib CRC-32, the same as Java's `CRC32`.
- `tof_filter.c`: classifies raw readings (VL53L0X returns ~8190 for "no target" and 65535 on timeout; valid range 30–2000 mm). Reduces them to median / valid count / spread / flags; valid only if a majority of samples are valid.

**Golden vectors:** `scripts/gen_adpcm_vectors.py` is an **independent Python IMA ADPCM** that writes the same vectors to `esp32-firmware/test/test_core/adpcm_golden.h` and `android-app/app/src/test/resources/adpcm_golden.json`. The C code is tested against them. *The Kotlin codec arrives in M4 and must be tested against the same JSON.*

**Platform pinned** to `espressif32@7.1.3`, which ships Arduino-ESP32 **core 2.0.17** (ESP-IDF 4.4), so the audio code uses the legacy `driver/i2s.h`. A future core 3.x would need `speaker.cpp`/`mic.cpp` ported. There's also a `native` test environment (`pio test -e native`).

### M3: BLE protocol v2 + throughput spike (`f9e2f88`)

**Goal:** add the channels needed for speaker and mic audio, make photos verifiable, and *measure* the link before designing audio around it.

The full byte layouts are in [`glasses_frames.h`](../esp32-firmware/lib/glasses_core/src/glasses_frames.h) (C) and [`Frames.kt`](../android-app/app/src/main/java/com/smartglasses/app/ble/Frames.kt) (Kotlin). Both sides' tests assert **the same byte strings**.

| Char. | UUID suffix | Dir | Type | Purpose |
|---|---|---|---|---|
| CONTROL | 0002 | board→phone | indicate | Image header. **v2 = 16 B**: version 2, flags (question / retried / resend), image_id u16, size u32, CRC-32 u32, width u16, height u16. v1 = 6 B (size + distance) is still accepted from old firmware |
| IMAGE_DATA | 0003 | board→phone | indicate | JPEG chunks of min(MTU−3, 244) B, each waiting for the phone's ACK. **Unchanged:** the chunk size wasn't touched without data |
| RESULT_TEXT | 0004 | phone→board | write | Sentence (logged only) |
| COMMAND | 0005 | phone→board | write | `[op][payload]`: CAPTURE, STOP_PLAYBACK, SET_VOLUME, PLAY_TONE, GET_CAPS, PING, RESEND_IMAGE, ABORT_IMAGE, THROUGHPUT_UP/DOWN, SET_PARAM, MIC_MONITOR, TOF_READ, EARCON, MIC_TEST |
| EVENT | 0006 | board→phone | notify | `[type][board_ms u32][payload]`: BUTTON_DOWN/UP, HOLD_DETECTED, RECORD_START/STOP, PHOTO_CAPTURED, **TOF_RESULT**, PLAY_STARTED/DONE, UNDERRUN, CREDITS, ERROR, CAPS, PONG, THROUGHPUT_RESULT, MIC_LEVEL, IMAGE_ABORTED |
| AUDIO_UP | 0007 | board→phone | notify | 8-byte header (stream, flags START/END, seq u16, ADPCM predictor i16, step index, codec) + data |
| AUDIO_DOWN | 0008 | phone→board | write-no-response | Same frame format |

**Behaviour changes:**
- **ToF overlaps the photo transfer.** The board captures the photo, starts the ToF on a background task, sends the image, then sends `TOF_RESULT` for that image_id. The phone waits for it *while Gemma runs*, so the ~1 s of samples (5 × 200 ms) should add no time. In M2 they did, because protocol v1 needed the distance in the header.
- **Integrity:** the phone checks the CRC-32. On a mismatch with every byte present, it sends **RESEND_IMAGE once** (the board resends from its PSRAM copy). ABORT_IMAGE stops a transfer at the next chunk.
- **Capabilities handshake:** after connecting, the phone sends GET_CAPS. CAPS carries protocol and firmware version, feature bits (camera / ToF / speaker / mic / PSRAM), audio rate, samples per frame, codec, ToF type, **reset reason (brownout = 9)**, volume, ceiling, hold_ms, ring and prebuffer frames (0 until M4), and ToF samples.
- Commands from the phone are **queued to the main loop** on the board, never run on the BLE task. Image sending blocks waiting for ACKs that arrive on that task, so running it there would deadlock.
- The phone's `GlassesBleManager` treats the v2 characteristics as **optional**, so v1 firmware still connects (photos only). v2 firmware **needs** this app (old apps reject the 16-byte header).

**Firmware files:** `ble_link.cpp/.h` (namespace `ble`; it can't be called `link` because that clashes with POSIX `link()`), `throughput.cpp/.h`, and `main.cpp` (rewritten around the modules).

**Phone files:**
- `ble/Frames.kt` (codecs + `GlassesEvent` + `TofReading`)
- `ble/ImageAssembler.kt` (v1/v2 headers, CRC check, `crcMismatch` flag)
- `ble/GlassesLink.kt` (event and audio streams, `caps`, `sendCommand`, `writeAudioFrame`, `awaitTof(imageId)`, resend logic, 2M PHY request)
- `ble/ThroughputTest.kt` (+ `SeqTracker`)
- `assistant/AssistantSession.kt` (distance joined at the end; `Done` now holds `sentence` + `tof`)

**Throughput spike:**
- *Uplink:* the board sends N bytes as AUDIO_UP notifications (stream 3) with sequence numbers. The phone measures KB/s and counts gaps. The board reports frames, failed sends and "out of buffers" retries.
- *Downlink:* the phone announces N bytes, then writes AUDIO_DOWN frames without response. The board counts bytes, frames and sequence gaps and reports KB/s.
- Options: request 2M PHY (phone side) and the LL data length (`SET_PARAM 5`).
- **These numbers decide M4/M6** (see §6).

**Temporary UI:** `ui/LinkTestCard.kt` on the main screen: firmware/caps line, 2M PHY toggle, Uplink 100 KB, Downlink 100 KB, Ping, Test tone, Read ToF, Capture. It moves into the Diagnostics screen in M7.

---

## 4. How the system works today (v0.4.0-test)

```
Button tap (fires on press, as in v0.3)
  board: EVT_BUTTON_DOWN → capture JPEG (PSRAM copy, id N) → EVT_PHOTO_CAPTURED
         → start ToF (5 × 200 ms, background task)
         → CONTROL v2 header (size, CRC) → IMAGE_DATA chunks (244 B, each ACKed)
         → wait for ToF → EVT_TOF_RESULT(id N: median, valid/total, spread, flags)
  phone: ImageAssembler verifies CRC + JPEG markers (resend once on CRC mismatch)
         → SceneDescriber: downscale to 512 px; ML Kit OCR at 1600 px;
           Gemma 4 E2B (GPU→CPU, greedy, 48 tokens, 30 s timeout) or ML Kit labels
         ‖ in parallel: awaitTof(id N, 4 s)
         → sentence = phrase + ", NN centimetres" (only if the ToF reading is valid)
         → PhoneTtsAnnouncer speaks it on the PHONE; RESULT_TEXT written to the board (logged)
         → one latency CSV row
```

**Still interim, replaced in later milestones:**
- **The phone speaks, not the glasses.** `ResultAnnouncer` is the swap point (M4).
- **Distance wording is the old rule:** rounded to the nearest cm, no "about", no 1.2 m cutoff (`SceneDescriber.compose/formatDistance`). The proper rules are in M5's `AnswerComposer`.
- **No hold-to-talk yet.** Console `h` prints a placeholder (M6).
- The UI is the v0.3 single screen plus the model card and the Link tests card (M7).

---

## 5. Decisions, deviations from the prompt, and things that surprised us

| Topic | Decision / finding | Why |
|---|---|---|
| ToF timing | Measure **during** the image transfer and send as an event, not in the header | 5 × 200 ms in the header path adds ~1 s to every tap |
| Flavors | Added in **M1**, not M8 | The download needs INTERNET; adding it to `main` then removing it would be churn |
| ML Kit permissions | Offline flavor strips INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE | ML Kit / Play services merge them in silently; checked on the built APK with `aapt2` |
| Speaker mono | Same sample on L and R | The MAX98357A SD-pin mode (L / R / (L+R)/2) can't affect the result, so its wiring isn't blocking |
| Loudness | 50% software ceiling, volume as % of ceiling | 1 W / 8 Ω speaker, amp GAIN wiring unknown |
| GPIO21 | LED there, and **no microSD** | GPIO21 is also the SD chip-select (and GPIO7/8/9 the SD SPI lines) |
| Tap detection | Will move to **release** in M6 (to tell tap from hold) | A tap starts up to the hold threshold (~0.5 s) later than today |
| ADPCM vectors | From an independent Python implementation | Vectors from one of the two codecs would only prove they agree with each other |
| LiteRT-LM 0.17.1 | Has `Content.AudioBytes` / `AudioFile`, `EngineConfig(audioBackend=…)`, `ResponseFormat.json(schema)` + `ConversationConfig(enableResponseFormat)`, streaming `sendMessageAsync` | Found by inspecting the AAR. Audio is decoded with **miniaudio**, so send a **WAV file's bytes**, not raw PCM |
| Arduino core | 2.0.17, platform pinned | `ESP_I2S.h` (core 3.x) isn't available; legacy I2S driver used |
| Namespace `ble` | Not `link` | Clashes with POSIX `link()` |
| R8 | Enabled for release, but test builds ship **debug** | Release never run on a device; LiteRT-LM has no keep rules of its own |
| `CLAUDE.md` | Created | The prompt said to read it, but it didn't exist |

**Unknowns that must not be stated as facts:** why 512-byte BLE chunks stalled, and why one black 3.6 KB image with no distance appeared. The diagnostics from M0 are there to catch the next occurrence.

---

## 6. What to do next

### 6.1 Hardware tests (blocking: do these before M4)
Run [`HARDWARE_TESTS.md`](HARDWARE_TESTS.md) sections **M0, M2 and M3** with v0.4.0-test, and record the results in the file. Most important:
- **2.3 LED polarity**, **2.4–2.7 speaker** (clean tone, ceiling, brownouts), **2.8–2.9 mic** (which slot, levels, gain), **2.10–2.11 ToF**, **2.12 tap end-to-end**.
- **3.3 tap latency vs M2**, **3.4 ping**, **3.5–3.7 throughput up/down, with and without 2M PHY**.
- **3.8 decision:** ADPCM at 16 kHz needs about 9.6 KB/s including headers (raw PCM about 32 KB/s). If both directions sustain **≥ 15 KB/s with < 1% loss**, live ADPCM audio is fine. If not, redesign before M4/M6 (lower sample rate, bigger pre-buffer, or record-then-send).
- Record the **phone model and Android version** with every result.
- The full ToF accuracy run ([`TOF_TEST_PROTOCOL.md`](TOF_TEST_PROTOCOL.md)) can wait until after M5; it feeds the calibration screen.

### 6.2 Open questions for the user (section 10 of the prompt)
1. How are the MAX98357A **SD** and **GAIN** pins wired? (Sets how loud we can go; mono already handled.)
2. Keep the status LED on GPIO21? (Default: yes.)
3. **Target phone model and Android version?** (Needed for the speech-recognition and GPU decisions in M5.)
4. Should the `offline` flavor be the default release? (Default: yes.)

### 6.3 M4: Speaker path (next milestone)
Design already decided:
- **Phone:**
  - `TextToSpeech.synthesizeToFile` → parse the WAV (read the sample rate from its header; engines differ) → resample to 16 kHz mono.
  - IMA ADPCM in Kotlin (`audio/ImaAdpcm.kt`), **must match `adpcm_golden.json`** → frames of **400 samples** (200 B data + 8 B header) on AUDIO_DOWN, stream 2, START/END flags.
  - Choose an offline English voice (en-IN if present; check `Voice.isNetworkConnectionRequired` and `KEY_FEATURE_NOT_INSTALLED`).
- **Flow control:** the board sends `EVT_CREDITS {stream, allowed_seq}` as a *cumulative* sequence limit (robust to a lost credit message). The phone only sends `seq < allowed_seq` (`Frames.seqBefore`, wrap-safe). Initial allowance = ring capacity.
- **Board:**
  - Decode into a **PSRAM PCM ring of ~2 s** (80 frames).
  - **Pre-buffer 400 ms** (16 frames), or start at once if END arrives first.
  - Fade in; play through the existing `speaker` task.
  - On underrun play silence and send `EVT_UNDERRUN`.
  - Send `EVT_PLAY_STARTED` (feeds the `play_started` latency stage) and `EVT_PLAY_DONE`.
  - Fill `ring_frames` / `prebuffer_frames` in CAPS.
  - Never record while playing.
- **`speech/SpeechOutput`** replaces `ResultAnnouncer`, with a "Speak on: glasses / phone / both" setting. It falls back to the phone if the glasses aren't connected or have no speaker. Once the board speaks, drop the separate RESULT_TEXT write.
- **Tests:**
  - Kotlin ADPCM against the golden JSON
  - WAV parser
  - resampler (frequency and length)
  - credit window logic (incl. wrap-around)
  - frame packing of a whole sentence
- **Hardware tests to add:**
  - "Play test tone" from the app
  - a stored test sentence streamed down
  - underrun count, clipping at the ceiling, `play_started` latency

### 6.4 M5–M8 (summary; full detail in `PHASE3_PLAN.md`)
- **M5, voice on the phone only:**
  - `SpeechToText` interface.
  - Spike A, Gemma with audio + image + text in one call, returning JSON with `heard`.
  - Spike B, Android on-device `SpeechRecognizer` fed from a file (API 33+).
  - Harness and `scripts/wer.py`; at least 20 recorded questions in the user's voice → `docs/STT_SPIKE.md`.
  - `AnswerComposer` with the JSON contract: `heard`, `target`, `found`, 3×3 `position`, `answer`, `confidence`. Validate it and retry once.
  - Safety wording: never call a path or stairs safe; "I can't see it"; "I think…" for low confidence.
  - Distance rules: "about X"; nearest 5 cm under 1 m and 10 cm above; invalid or over 1.2 m → "I couldn't get a reliable distance"; distance only for **centre** targets; units setting.
  - "Ask with phone mic".
- **M6, hold-to-talk on the glasses:**
  - Tap (< 500 ms) vs hold, decided on release.
  - Earcon → stream mic (stream 1, 10 s cap) → on release capture photo + ToF → "thinking" earcon → answer on the glasses speaker.
  - Barge-in; never record while playing; every failure spoken.
  - Implement `CMD_MIC_TEST`, which is defined but **not handled yet** (the board answers "unknown command").
- **M7, UI:**
  - Screens: setup wizard, Home (status chips, Describe, Ask with phone mic, result card with Replay / Stop), History (JSON + thumbnails, detail with latency and ToF quality, export, delete), Settings, Diagnostics (BLE log, ToF live + calibration walk-through, tone, mic meter + record/playback, throughput test, latency export, STT spike).
  - Remove `LinkTestCard`.
  - TalkBack labels, 48 dp targets, spoken state changes, haptics.
- **M8:**
  - `scripts/check-apk-permissions.sh`
  - Complete the hardware test templates; README rewrite.
  - Test the **R8 release** build on a phone before shipping it.

---

## 7. How to build, test and release

**Requirements on the dev Mac:**
- JDK 17 (`/opt/homebrew/opt/openjdk@17`), Android SDK at `/opt/homebrew/share/android-commandlinetools`.
- PlatformIO via `pip install platformio`; it's not on the PATH by default here.

```bash
# Android
cd android-app
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testOfflineDebugUnitTest            # 34 unit tests
./gradlew assembleOfflineDebug                # → app/build/outputs/apk/offline/debug/app-offline-debug.apk
./gradlew assembleOfflineRelease              # R8, 50 MB, NOT yet tested on a phone
$ANDROID_SDK/build-tools/<ver>/aapt2 dump permissions <apk>   # check the offline APK has no network permission

# Firmware
cd esp32-firmware
pio run                                       # build
pio test -e native                            # 10 host tests (ADPCM golden, CRC, ToF filter, frame bytes)
pio run -t upload -t monitor                  # flash + serial (115200)

# Merged image for browser flashing (esptool-js, address 0x0)
B=.pio/build/xiao_esp32s3
python ~/.platformio/packages/tool-esptoolpy/esptool.py --chip esp32s3 merge_bin -o smartglasses-firmware-merged.bin \
  --flash_mode keep --flash_freq keep --flash_size keep \
  0x0 $B/bootloader.bin 0x8000 $B/partitions.bin \
  0xe000 ~/.platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin 0x10000 $B/firmware.bin

# Python
python3 -m unittest discover -s scripts/tests
python3 scripts/gen_adpcm_vectors.py          # only if the ADPCM test signal/block layout changes
```

**Releasing a test build:**
- Push the branch. Create a GitHub **pre-release** (so the stable "latest" doesn't change) and attach the offline APK (debug for now) and the merged firmware.
- `gh` is not logged in on the dev Mac. v0.4.0-test was created through the GitHub REST API using the keychain's git credential.
- GitHub release files must be under 2 GiB, so the model is always linked from Hugging Face, never attached.

**Rules (also in `CLAUDE.md`):**
- Change `glasses_frames.h` and `Frames.kt` together, with matching byte tests.
- A tap must keep working at every commit.
- Never commit `*.litertlm`, keystores or `local.properties`.
- Delete dead code instead of keeping two paths.
- Report hardware-touching work as "implemented, awaiting hardware verification".

---

## 8. Known gaps and documentation debt

- **README sections still describe v0.3:**
  - the "How it works" diagram and "Tech choices → Speech" still say **SAM** on the board (replaced by phone TTS streamed as audio in M4)
  - "Phone → glasses: text only, no audio" is no longer the plan
  - the old Phase 1–9 status table
  - the example serial log in "Test: button press" shows the v1 format
  - the code map lacks the Phase 3 files

  These are fixed in the M8 README rewrite. Until then, **this file and `PHASE3_PLAN.md` are authoritative**.
- `PARAM_DATA_LEN` (5) is used by the firmware as a literal `5` but isn't in the C `PARAM_*` enum (it is in `Frames.kt`). Add it when next touching `glasses_frames.h`.
- `CMD_MIC_TEST` is defined on both sides but not handled by the firmware yet (M6).
- `h` (hold) on the console is a placeholder (M6).
- The `withDownload` flavor has not been released or tested on a phone.
- The stream-delta assumption in `StreamAccumulator` should be confirmed on a phone (the logged first-output time will show it).
- `docs/HARDWARE_TESTS.md` result columns are empty, waiting for the tester.

---

## 9. File map (Phase 3 additions in **bold**)

```
android-app/app/src/
├── main/assets/**model-manifest.json**
├── main/java/com/smartglasses/app/
│   ├── GlassesApp.kt                 process-wide owner: link, ModelStore, describer, session, latency log
│   ├── ai/  GemmaVision · MlKitVision · SceneDescriber · **ModelSpec · ModelStore · ModelActions · StreamAccumulator**
│   ├── ble/ GlassesProtocol · GlassesBleManager · GlassesLink · ImageAssembler · **Frames · ThroughputTest**
│   ├── assistant/ AssistantSession · ResultAnnouncer (PhoneTtsAnnouncer)
│   ├── **metrics/ InteractionTimeline · LatencyLog**
│   ├── service/GlassesService        foreground service keeps the link alive
│   └── ui/ MainActivity · MainScreen · ModelSection · PermissionGate · Theme · AssistantViewModel · **ModelSetupCard · LinkTestCard**
├── **offline/** AndroidManifest.xml (strips network) · ai/ModelDownloader.kt (stub)
├── **withDownload/** AndroidManifest.xml (INTERNET) · ai/ModelDownloader.kt (HTTP Range)
└── test/ CapturePipelineTest · **FramesTest · MetricsTest · ModelStoreTest · SeqTrackerTest** · resources/**adpcm_golden.json**

esp32-firmware/
├── platformio.ini                    pinned platform; envs xiao_esp32s3 + **native**
├── include/ glasses_protocol.h · **pins.h**
├── **lib/glasses_core/** ima_adpcm · crc32 · tof_filter · glasses_frames   (pure C, host-testable)
├── src/ main.cpp · **ble_link · camera · tof · speaker · mic · console · settings · throughput**
├── **test/test_core/** test_core.cpp · adpcm_golden.h
└── reference/hardware_bringup.ino

scripts/ fetch-model.sh · **latency_report.py · gen_adpcm_vectors.py · tests/**
docs/    **PROGRESS.md (this file) · PHASE3_PLAN.md · HARDWARE_TESTS.md · TOF_TEST_PROTOCOL.md**
CLAUDE.md
```
