# Hardware tests

Everything here needs the real glasses, so none of it can be checked by the automated tests. Each milestone in `PHASE3_PLAN.md` is **"implemented, awaiting hardware verification"** until the tests listed for it pass. Fill in the Result column and keep the serial log or the exported CSV.

Serial monitor: `cd esp32-firmware && pio device monitor` (115200 baud). Single keys (`p`, `h`) act immediately; other commands need Enter.

---

## M0: photo transfer retest (v0.3.1 issue)

| # | Step | Expected | Result |
|---|---|---|---|
| 0.1 | Flash, connect the app, press the button 20 times, about 5 s apart, pointing at varied scenes (lit desk, dim corner, a label) | 20 photos arrive. For each: `[DIAG] capture #N … bytes`, `IMAGE_DATA sent … KB/s` | |
| 0.2 | Search the serial log for `[DIAG] small frame` | Ideally none. If any appear, copy those lines **and the 10 lines before them** into an issue: they show the frame's size, time since camera init, heap/PSRAM, and the retry's size | |
| 0.3 | Note the reset reason printed at boot | `1` (power-on) or `3` (software). **`9` = brownout**: record what was happening before | |
| 0.4 | Export the latency CSV (`adb pull /sdcard/Android/data/com.smartglasses.app/files/latency/latency.csv`; an in-app export arrives in M7), run `scripts/latency_report.py latency.csv --source glasses` | A table of p50/p90 per stage (needs ≥ 10 runs) | |

## M2: hardware bring-up (serial console)

| # | Step | Expected | Result |
|---|---|---|---|
| 2.1 | Power on | Boot log shows `[CAM] camera OK`, `[TOF] VL53L0X OK`, `[SPK] I2S1 TX ready`, `[MIC] PDM mic ready`; a short beep | |
| 2.2 | `caps` | camera OK, ToF VL53L0X OK, speaker OK, mic OK; volume 60 % of ceiling 50 % | |
| 2.3 | **LED polarity.** Type `p` (no phone connected) | The onboard LED blinks briefly. If it stays ON when idle and goes OFF during capture, the polarity in `include/pins.h` (`LED_ON`) is inverted: report it | |
| 2.4 | **Speaker tone.** `tone 1000 500` | A clean 1 kHz beep, no crackle | |
| 2.5 | **Clipping / ceiling.** `tone 1000 1000` at `vol 100` (ceiling 50 %) | Loud but clean. Note whether the board resets (reset reason 9 at next boot = brownout) | |
| 2.6 | `ceil 70` then `tone 1000 1000`, then `ceil 50` again | Louder; if it distorts or rattles, keep the ceiling at 50 and note it | |
| 2.7 | **Sweep.** `tone 300 500`, `tone 3000 500`, `tone 6000 500` | All audible; note any rattle | |
| 2.8 | **Mic.** `mic 5`; stay quiet 2 s, then speak normally 30 cm away, then clap once | One slot (L or R) rises by ≥ 20 dB when speaking; the other stays low. Quiet rms below about −50 dBFS. Clap peak below 0 dBFS (no clipping). Note which slot | |
| 2.9 | **Mic gain.** If speech rms is below about −35 dBFS: `set micgain 20` (2×) and repeat 2.8 | Speech around −30 to −20 dBFS | |
| 2.10 | **ToF.** `tof 10` with a white card at 30 cm | Median within ±36 mm of 300 (12 %, the datasheet worst case), 10 valid, spread under 20 mm | |
| 2.11 | **ToF missing.** Unplug the sensor's SDA, reboot | `[TOF] !! VL53L0X init failed`; the board still boots, and `p` still sends a photo (no distance) | |
| 2.12 | **Tap end-to-end.** Phone connected, Gemma ready, press the button | Same as v0.3: photo + distance → spoken sentence. The capture+ToF time in the log is now about 1 s longer (5 ToF samples × 200 ms); M3 overlaps this with the transfer | |

The full ToF accuracy test is in `TOF_TEST_PROTOCOL.md`.

## M3: BLE protocol v2 + throughput spike

Flash the new firmware **and** install the new app together: v2 firmware sends a 16-byte image header that older apps reject. (The new app still accepts v1 firmware.)

| # | Step | Expected | Result |
|---|---|---|---|
| 3.1 | Connect; check the app's link log and the **Link tests** card | `Glasses firmware speaks protocol v2`, then `Glasses firmware 0.4.0, protocol v2: camera=true ToF=true speaker=true mic=true, reset reason 1` |PASS (2026-11-18): `firmware 0.4.0, protocol v2: camera=true ToF=true speaker=true mic=true, reset reason 1`, MTU 515 |
| 3.2 | **Tap.** Press the button | Serial: `image N header sent … crc …`, `IMAGE_DATA sent`, then `[TOF] 5 samples in ~1000 ms`, `[CAP] … press -> done … ms`. App: speaks the sentence with the distance. Log: `← ToF for image N: … mm` arrives **after** the image |PASS: photo 2, 13652 B in 2519 ms; ToF event 49 ms after the image (255 mm, 5/5 valid, spread 1 mm); spoken sentence ended "…, 26 centimetres". Photo 1 (0/5 valid, flags 0x02) correctly gave a sentence with no distance |
| 3.3 | **Tap latency vs M2.** 10 taps; compare `press -> done` with M2 | Shorter than M2's capture+ToF+transfer, because the ToF is now measured during the transfer | |
| 3.4 | **Ping** button, 10 times | Round trip in ms (record min/median/max) | |
| 3.5 | **Uplink 100 KB**, 3 runs | `uplink (notify): X KB/s, … missing (Y %)` |1 run so far: board view 100 000 B in 1461 ms = **66.8 KB/s**, 424 frames, 0 failed, 319 out-of-buffer retries. Phone-side KB/s/loss not captured |
| 3.6 | **Downlink 100 KB**, 3 runs | `downlink (write without response): X KB/s, … missing (Y %)` |1 run so far: 100 000 B, 424 frames, **0 missing**, 2013 ms = **48.5 KB/s** (board measured) |
| 3.7 | Tick **Request 2M PHY**, repeat 3.5 and 3.6 | Serial shows `PHY now tx 2 rx 2` if accepted; record KB/s again | |
| 3.8 | **Decision:** ADPCM 16 kHz needs ~9.6 KB/s including headers (raw PCM: ~32 KB/s). If both directions sustain ≥ 15 KB/s with < 1 % loss, live ADPCM audio is fine. If not, report the numbers before M4/M6 build on it | |Provisional: both directions far above 15 KB/s with 0 loss in 1 run each, so live ADPCM is fine. Needs 3 runs each + phone model to confirm |
| 3.9 | **Test tone / Read ToF / Capture** buttons | Beep from the glasses / a `ToF for image 65535` line in the log / a photo described and spoken |Read ToF works: 0/5 (out of range) ×2, then 34 mm (5/5, spread 2) and 52 mm (5/5, spread 1). Test tone: audible but VERY QUIET (volume 60 % of 50 % ceiling = 30 % full scale). Capture: worked (photo 1) |
| 3.10 | **Resend.** (Can't be forced from the app.) If the log ever shows `checksum mismatch` → `asking the glasses to resend photo N`, check the photo still gets described | | |

Record the phone model and Android version with every throughput result.

**Observed on the first M3 run (not one of the tests above, but important):** photos go over IMAGE_DATA (one acknowledged indication per 244-byte chunk) at **5.3 KB/s**: 12.8–13.7 KB took 2.4–2.5 s. The same link carried notifications at 66.8 KB/s. Image transfer is therefore the largest fixed cost in a tap after the model itself.

## M3.1: fast photo transfer, warm-up, speaker volume

| # | Step | Expected | Result |
|---|---|---|---|
| 3.1.1 | Connect; serial log | `[BLE] photo transfer mode: notify (fast)` right after connecting | |
| 3.1.2 | 10 taps with **Fast photo transfer** on (Link tests card) | Log: `IMAGE_DATA complete … KB/s` well above the 5.3 KB/s of M3. Serial: `image sent: … notify mode`. Record KB/s and button → speech time | |
| 3.1.3 | Untick **Fast photo transfer**, 10 taps | Back to ~5.3 KB/s (A/B comparison) | |
| 3.1.4 | Watch for `lost photo chunk(s)` → `asking the glasses to resend photo N` | If it happens: the photo still gets described (resent over the slow path). Count how often | |
| 3.1.5 | **Warm-up.** Restart the app, wait for "Gemma ready", then tap | `adb logcat -s SceneDescriber` shows `warm-up inference took … ms`; the FIRST real answer is about as fast as later ones (compare image complete → RESULT_TEXT with M3's 8.2 s vs 3.3 s) | |
| 3.1.6 | **Volume.** Tone 1000 Hz at volume 100 %, ceiling 50 % (the new default) | Clearly louder than M3's test tone (30 % → 50 % of full scale, +4.4 dB) | |
| 3.1.7 | Tones 500 / 1000 / 2000 / 3000 Hz | Note which is loudest (small speakers are often weakest at low frequencies) | |
| 3.1.8 | Ceiling 70 %, Tone 1000 Hz, then back to 50 % | Louder. Note any distortion/rattle; reconnect and check the caps line doesn't say BROWNOUT | |
| 3.1.9 | Report amp wiring: VIN on 5V or 3V3? GAIN and SD connected where? | | |

## M4: speech on the glasses' speaker

Settings → Speak on: **Glasses** (default). Volume and ceiling as set in M3.1.

| # | Step | Expected | Result |
|---|---|---|---|
| 4.1 | Link tests → **Speak test sentence** | The sentence plays **from the glasses**, clearly. The card shows `spoken on glasses, audio started after N ms` and the voice name (ideally en-IN, offline). Serial: `speech playback started after … ms of buffering`, then `speech finished: … s, 0 underruns, 0 lost frames, 0 overflow frames` | |
| 4.2 | Repeat 4.1 ten times; record "audio started after" | Typical value (it includes rendering the speech on the phone) | |
| 4.3 | Tap the glasses button | The description + distance is spoken **on the glasses**, not the phone | |
| 4.4 | **Barge-in.** Speak test sentence, then press the button while it's speaking | Speech stops at once (serial: `speech STOPPED`), and the new photo is described | |
| 4.5 | Walk ~5 m away during a sentence | Note underruns (serial). Short gaps of silence are acceptable; no crash, no garbage noise | |
| 4.6 | Turn the glasses off, then Speak test sentence | Spoken **on the phone** instead, with "glasses not available; spoke on the phone" | |
| 4.7 | Settings → Speak on: **Both** | Glasses and phone both speak (slight offset is expected) | |
| 4.8 | Speech at volume 100 / ceiling 50 for 2 minutes of repeated sentences | No brownout reset (caps line after reconnect), no distortion | |

## M5: voice questions with the phone's mic (no glasses audio yet)

| # | Step | Expected | Result |
|---|---|---|---|
| 5.1 | `adb logcat -s GemmaVision` after Gemma loads | `Gemma started on GPU, audio input available` (or `NOT available`: then questions use Android's recogniser) | |
| 5.2 | Glasses connected. **Hold to ask with phone mic**, say "What is this?", release | The glasses take a photo; the answer is spoken on the glasses. The card shows `Heard: "what is this"` and the answer | |
| 5.3 | Point at a mug in the **centre**, ask "Where is the mug?" | Answer ends "It's about NN centimetres away." | |
| 5.4 | Mug at the **left** edge of the view, same question | "It's on your left. Turn left and ask again for the distance." **No number** | |
| 5.5 | Ask about something not there ("Where are my keys?") | "I can't see your keys." | |
| 5.6 | "Is it safe to cross?" (any photo) | Contains "I can't tell if it's safe." Never "it's safe" | |
| 5.7 | Label in view: "What does this say?" | Reads the label text | |
| 5.8 | Press the mic button and say nothing | "I didn't catch that, please try again." | |
| 5.9 | Glasses off; pick a gallery photo with **Try with a photo**, then ask with the phone mic | The question is answered about that photo, spoken on the phone | |
| 5.10 | Settings → Speech recogniser: Android on-device; repeat 5.2 | Works the same (if `on-device recognition` is available on the S24) | |
| 5.11 | **STT spike**: follow `docs/STT_SPIKE.md` (20+ clips, run, `scripts/wer.py`) | Results table filled in | |

## M6: hold-to-talk on the glasses

| # | Step | Expected | Result |
|---|---|---|---|
| 6.1 | **Tap** (press < 0.5 s) | Same as before: photo + distance, described on the glasses. Serial: `[BTN] tap (N ms)` | |
| 6.2 | **Hold**, wait for the rising "listening" beep, ask "What is this?", release | Two short "thinking" beeps on release, then the answer from the glasses. Card: `Heard: "what is this"`. Serial: `[MIC] stream 1: N frames sent, 0 failed` | |
| 6.3 | Hold and start talking **before** the beep | The first words may be lost (recording starts after the beep, by design: never record while playing). Note how it feels | |
| 6.4 | Hold for more than 10 s | Recording stops at 10 s (`question hit the 10 s cap`), the question is answered | |
| 6.5 | Hold, say nothing, release | "I didn't catch that, please try again." spoken on the glasses | |
| 6.6 | **Barge-in**: while an answer is being spoken, tap | Speech stops immediately; the new photo is described | |
| 6.7 | Centre / left / not-there / safety / reading questions from 5.3–5.7, asked by **holding the glasses button** | Same results as with the phone mic | |
| 6.8 | 10 hold-to-talk questions; export the latency CSV; `scripts/latency_report.py latency.csv --mode question` | p50/p90 for button_down → record_start → button_up → image_received → answer_complete → play_started | |
| 6.9 | Glasses mic quality: Diagnostics → **Mic test** (record 3 s on the glasses, play back on the phone) | Speech intelligible; adjust `set micgain` if too quiet or clipping | |
| 6.10 | Serial `h` (simulated 2 s hold) | Listening beep, 2 s recording, thinking beeps, answer | |

## M7: app UI (any phone; no glasses needed for most)

| # | Step | Expected | Result |
|---|---|---|---|
| 7.1 | Fresh install | Setup wizard: Bluetooth (required), notifications, microphone, AI model, glasses, each with live ✓ and plain instructions; Continue only once Bluetooth is allowed | |
| 7.2 | Home | Three chips (Glasses / AI model / Speech) in words, not just colour; tapping each opens details. "Describe what's ahead", "Hold to ask with phone mic", "Try with a photo" | |
| 7.3 | Describe, then **Replay** and **Stop** on the result card | Replay repeats the last answer; Stop cuts it off (glasses and phone) | |
| 7.4 | **TalkBack on**: navigate Home, Settings, History | Every control is announced with a meaningful label; the result card and chips announce changes | |
| 7.5 | History after a few interactions | Thumbnails, question, answer, time; detail shows transcript, photo, ToF median/samples/spread/flags, latency per stage; Export JSON and CSV open the system "save as"; Delete works | |
| 7.6 | Settings: change Speak on, volume, ceiling, hold time, fast photos | Takes effect immediately on connected glasses (serial shows the new values / caps) | |
| 7.7 | Diagnostics: Ping, tones, Speak test sentence, ToF live, mic meter, mic test + Play, throughput, latency export, STT spike, Bluetooth log | All work as in M3–M6 | |
| 7.8 | **ToF calibration**: Diagnostics walk-through at 20 / 50 / 100 cm with a tape measure, Save | Fit line shown (scale, offset, worst error); later distances use it (History shows the raw median; the spoken distance is calibrated) | |
| 7.9 | Announce status on: switch the glasses off and on | "Glasses disconnected." (phone) … "Glasses connected." | |
| 7.10 | Dark mode / large system font | Readable, nothing cut off | |
