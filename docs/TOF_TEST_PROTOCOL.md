# VL53L0X distance test protocol

**Purpose:** measure how accurate and repeatable the glasses' distance reading is, on different surfaces, so the app's spoken distances can be calibrated and their limits stated honestly.

**What the sensor is specified to do** (ST datasheet, default mode): about 50 mm to 1.2 m; accuracy 3–12 % depending on ambient light and target reflectance; a narrow cone straight ahead. Dark or absorbing surfaces may give poor readings or none. This test checks what *our* unit does.

## Setup
- Firmware flashed, serial monitor open at 115200 baud (`pio device monitor`).
- Glasses fixed on a stand (books / clamp) so the sensor faces the target squarely. **Don't hold them by hand.**
- Tape measure from the **front face of the VL53L0X** (not the frame) to the target surface.
- Indoor light, no direct sunlight on the target. Note the lighting in the table.
- Targets:
  - **White card**: A4 paper on cardboard
  - **Dark cloth**: black T-shirt or similar, flat
  - **Shiny**: a mirror-finish or glossy surface (steel tray, glossy book cover), facing the sensor squarely
- Each target must be at least 20 × 20 cm, so it fills the sensor's cone at every distance.

## Procedure (for each target × distance)
1. Place the target at the distance.
2. Type `tof 10` and Enter. The board prints 10 single readings, then a line like:
   `[TOF] over 10 readings: median 362 mm, 10 valid, spread 6 mm, flags 0x01`
3. Copy the 10 readings and the summary into the sheet. Out-of-range readings show as invalid (flags `0x02`), timeouts as `0x04`.

Distances: **100, 200, 300, 500, 800, 1000, 1200 mm**, plus **50 mm** for the white card only.

## Record sheet
Mean error = mean of the valid readings − true distance. Spread = max − min of the valid readings.

| Target | True (mm) | Valid / 10 | Median (mm) | Mean (mm) | Mean error (mm) | Error % | Spread (mm) | Out-of-range | Timeouts | Lighting |
|---|---|---|---|---|---|---|---|---|---|---|
| White card | 50 | | | | | | | | | |
| White card | 100 | | | | | | | | | |
| White card | 200 | | | | | | | | | |
| White card | 300 | | | | | | | | | |
| White card | 500 | | | | | | | | | |
| White card | 800 | | | | | | | | | |
| White card | 1000 | | | | | | | | | |
| White card | 1200 | | | | | | | | | |
| Dark cloth | 100 | | | | | | | | | |
| Dark cloth | 200 | | | | | | | | | |
| Dark cloth | 300 | | | | | | | | | |
| Dark cloth | 500 | | | | | | | | | |
| Dark cloth | 800 | | | | | | | | | |
| Dark cloth | 1000 | | | | | | | | | |
| Dark cloth | 1200 | | | | | | | | | |
| Shiny | 100 | | | | | | | | | |
| Shiny | 200 | | | | | | | | | |
| Shiny | 300 | | | | | | | | | |
| Shiny | 500 | | | | | | | | | |
| Shiny | 800 | | | | | | | | | |
| Shiny | 1000 | | | | | | | | | |
| Shiny | 1200 | | | | | | | | | |

## What to conclude
- **Offset:** if the white-card mean error is roughly constant across distances (e.g. always +12 mm), enter that offset in the app's ToF calibration (Diagnostics → ToF calibration). If it grows with distance, use the two-point calibration (scale + offset).
- **Usable range:** the largest distance where the white card still gives ≥ 8/10 valid readings with error under 10 %. The app says "I couldn't get a reliable distance" beyond about 1.2 m regardless.
- **Surfaces:** note where the dark cloth or the shiny surface stop giving valid readings. These limits go in the project report as stated limitations.

## Camera/ToF alignment (centre region)
The ToF cone covers only a small patch in the middle of the camera's view. To size that patch:
1. Put a small white card (5 × 5 cm) at 50 cm, in the centre of the camera view (check with a capture in the app).
2. Move it sideways in 1 cm steps, running `tof 5` each time, until the reading jumps to the background.
3. Note how far off-centre (in cm) the card could go on each side, and where the card edge sits in the captured photo (as a fraction of image width).
4. Set **Central region** in the app's Settings to about that fraction (default 0.33, the middle third).

| Direction | Card offset where ToF lost it (cm) | Fraction of image width from centre |
|---|---|---|
| Left | | |
| Right | | |
| Up | | |
| Down | | |
