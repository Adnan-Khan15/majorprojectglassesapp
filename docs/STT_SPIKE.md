# Speech-recognition spike: Gemma audio vs Android on-device

**Question:** which recogniser should turn the wearer's spoken question into text?

| | Spike A: Gemma 4 E2B audio | Spike B: Android on-device `SpeechRecognizer` |
|---|---|---|
| How | The WAV goes into the same Gemma call as the photo; Gemma returns the question in the JSON `heard` field | Our recording is fed to the phone's on-device recogniser through `EXTRA_AUDIO_SOURCE` (Android 13+), offline only |
| Extra size | 0 (already in the model) | 0 (part of the phone; may need the offline English pack: Settings → Google → Voice → Offline speech) |
| Licence | Apache 2.0 (Gemma 4) | Part of Android / Google app |
| Unknowns | Accuracy on Indian English and noise; whether the audio encoder starts on the S24 (the app logs `audio input available/NOT available`) | Whether the S24's on-device recogniser accepts an audio source and works fully offline |

Both are implemented behind `voice/SpeechToText` and selectable in Settings → Speech recogniser. Default: **Gemma audio** (one model call instead of two), until this spike says otherwise.

Spikes A and B were chosen first because neither adds anything to the APK. Vosk (small-en, ~40 MB) and sherpa-onnx + whisper-tiny.en (~75 MB) remain options if both fail. Each would add a model download, which `ModelStore`'s manifest can already describe.

## Protocol (the user records; the app runs; the script scores)

1. **Record at least 20 questions in your own voice** with the app (Diagnostics → STT spike → hold **Record clip**): about 14 in a quiet room and 6 with background noise (fan, TV, street). Use real questions, for example:
   - "Where are my house keys?"
   - "What does this label say?"
   - "Is there a cup on the table?"
   - "What colour is this shirt?"
   - "Read the expiry date."
   - "Which way is the door?"
   - "What's in front of me?"
   - "Is this the Colgate or the Sensodyne?"

   Clips are saved as `q01.wav`, `q02.wav`, … in `/sdcard/Android/data/com.smartglasses.app/files/stt_spike/`.
2. **Write `references.csv`** on the computer with what you actually said:
   ```
   file,reference,condition
   q01.wav,where are my house keys,quiet
   q02.wav,what does this label say,noisy
   ```
3. Wait for **Gemma ready**, then tap **Run spike on all clips**. The app writes `results.csv` to the same folder.
4. `adb pull /sdcard/Android/data/com.smartglasses.app/files/stt_spike/results.csv`
5. `scripts/wer.py results.csv references.csv`

## Results (fill in)

Phone: ______  Android: ______  Date: ______

| Engine | Clips | WER overall | WER quiet | WER noisy | Latency p50 | Latency p90 | Failures |
|---|---|---|---|---|---|---|---|
| Gemma audio | | | | | | | |
| Android on-device | | | | | | | |

## Decision rule
- Prefer the engine with the lower WER **on noisy clips**, as long as its p90 latency is under 3 s.
- If both WERs are within 3 points of each other, prefer **Gemma audio**: one model call, no extra latency for a separate transcription step.
- If both have WER above 25 % in quiet conditions, evaluate Vosk / whisper-tiny before M6 ships.

Note: in the Gemma-audio path the transcript (`heard`) comes back in the same reply as the answer, so its latency isn't separable. This spike measures Gemma as a transcriber on its own; the latency log measures the real combined call.
