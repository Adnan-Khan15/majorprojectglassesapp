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
